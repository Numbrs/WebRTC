/*
 *  Copyright 2016 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.webrtc;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.view.Surface;
import android.view.WindowManager;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.webrtc.CameraEnumerationAndroid.CaptureFormat;

@SuppressWarnings("deprecation")
class Camera1Session implements CameraSession {

  private static final String TAG = "Camera1Session";
  private static final int NUMBER_OF_CAPTURE_BUFFERS = 3;

  private static final Histogram camera1StartTimeMsHistogram =
          Histogram.createCounts("WebRTC.Android.Camera1.StartTimeMs", 1, 10000, 50);
  private static final Histogram camera1StopTimeMsHistogram =
          Histogram.createCounts("WebRTC.Android.Camera1.StopTimeMs", 1, 10000, 50);
  private static final Histogram camera1ResolutionHistogram = Histogram.createEnumeration(
          "WebRTC.Android.Camera1.Resolution", CameraEnumerationAndroid.COMMON_RESOLUTIONS.size());

  private static enum SessionState {RUNNING, STOPPED}

  private final Handler cameraThreadHandler;
  private final Events events;
  private final boolean captureToTexture;
  private final SurfaceTextureHelper surfaceTextureHelper;
  private final int cameraId;
  private final android.hardware.Camera camera;
  private final RtcCameraInfo info;
  // Used only for stats. Only used on the camera thread.
  private final long constructionTimeNs; // Construction time of this class.

  private SessionState state;
  private boolean firstFrameReported;

  private final int displayRotation;
  private final boolean isCameraFrontFacing;
  private final int cameraOrientation;
  private boolean isFlashTorchOn;

  // TODO(titovartem) make correct fix during webrtc:9175
  @Nullable @SuppressWarnings("ByteBufferBackingArray")
  private static Camera1Session create(
          CreateSessionCallback callback,
          Events events,
          boolean captureToTexture,
          SurfaceTextureHelper surfaceTextureHelper,
          RtcCameraInfo cameraInfo,
          int displayRotation) {

    final int cameraId = cameraInfo.getCameraIndex();
    final long constructionTimeNs = System.nanoTime();
    Logging.d(TAG, "Open camera " + cameraId);
    events.onCameraOpening();

    final android.hardware.Camera camera;
    try {
      camera = android.hardware.Camera.open(cameraId);
    } catch (RuntimeException e) {
      callback.onFailure(FailureType.ERROR, e.getMessage());
      return null;
    }

    if (camera == null) {
      callback.onFailure(FailureType.ERROR,
              "android.hardware.Camera.open returned null for camera id = " + cameraId);
      return null;
    }

    try {
      camera.setPreviewTexture(surfaceTextureHelper.getSurfaceTexture());
    } catch (IOException | RuntimeException e) {
      camera.release();
      callback.onFailure(FailureType.ERROR, e.getMessage());
      return null;
    }

    cameraInfo.init(camera.getParameters());
    final CaptureFormat captureFormat = cameraInfo.getCaptureFormat();
    updateCameraParameters(camera, cameraInfo, captureToTexture, false);

    if (!captureToTexture) {
      final int frameSize = captureFormat.frameSize();
      for (int i = 0; i < NUMBER_OF_CAPTURE_BUFFERS; ++i) {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(frameSize);
        camera.addCallbackBuffer(buffer.array());
      }
    }

    // Calculate orientation manually and send it as CVO insted.
    camera.setDisplayOrientation(0 /* degrees */);

    Camera1Session session = new Camera1Session(
            events,
            captureToTexture,
            surfaceTextureHelper,
            camera,
            cameraInfo,
            cameraId,
            constructionTimeNs,
            displayRotation);
    callback.onDone(session);
    return session;
  }

  private static void updateCameraParameters(
          android.hardware.Camera camera,
          RtcCameraInfo info,
          boolean captureToTexture,
          boolean isTorchStateOn) {

    CaptureFormat captureFormat = info.getCaptureFormat();
    if (captureFormat == null) {
      return;
    }

    final Size pictureSize = info.getBestSize();
    CameraEnumerationAndroid.reportCameraResolution(camera1ResolutionHistogram, pictureSize);

    final android.hardware.Camera.Parameters parameters = camera.getParameters();
    final List<String> focusModes = parameters.getSupportedFocusModes();

    parameters.setPreviewFpsRange(captureFormat.framerate.min, captureFormat.framerate.max);
    parameters.setPreviewSize(captureFormat.width, captureFormat.height);
    parameters.setPictureSize(pictureSize.width, pictureSize.height);
    if (!captureToTexture) {
      parameters.setPreviewFormat(captureFormat.imageFormat);
    }

    if (parameters.isVideoStabilizationSupported()) {
      parameters.setVideoStabilization(true);
    }
    if (focusModes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
      parameters.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
    }

    String flashMode = isTorchStateOn
            ? android.hardware.Camera.Parameters.FLASH_MODE_TORCH
            : android.hardware.Camera.Parameters.FLASH_MODE_OFF;
    parameters.setFlashMode(flashMode);

    camera.setParameters(parameters);
  }

  private Camera1Session(
          Events events,
          boolean captureToTexture,
          SurfaceTextureHelper surfaceTextureHelper,
          android.hardware.Camera camera,
          RtcCameraInfo cameraInfo,
          int cameraId,
          long constructionTimeNs,
          int displayRotation) {

    Logging.d(TAG, "Create new camera1 session on camera " + cameraId);

    this.cameraThreadHandler = new Handler();
    this.events = events;
    this.captureToTexture = captureToTexture;
    this.surfaceTextureHelper = surfaceTextureHelper;
    this.cameraId = cameraId;
    this.camera = camera;
    this.info = cameraInfo;
    this.constructionTimeNs = constructionTimeNs;
    this.displayRotation = displayRotation;
    this.cameraOrientation = cameraInfo.getOrientation();
    this.isCameraFrontFacing = cameraInfo.isFrontFacing();

    CaptureFormat captureFormat = info.getCaptureFormat();
    surfaceTextureHelper.setTextureSize(captureFormat.width, captureFormat.height);

    startCapturing();
  }

  @Override
  public void stop() {
    Logging.d(TAG, "Stop camera1 session on camera " + cameraId);
    checkIsOnCameraThread();
    if (state != SessionState.STOPPED) {
      final long stopStartTime = System.nanoTime();
      stopInternal();
      final int stopTimeMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStartTime);
      camera1StopTimeMsHistogram.addSample(stopTimeMs);
    }
  }

  private void startCapturing() {
    Logging.d(TAG, "Start capturing");
    checkIsOnCameraThread();

    state = SessionState.RUNNING;

    camera.setErrorCallback(new android.hardware.Camera.ErrorCallback() {
      @Override
      public void onError(int error, android.hardware.Camera camera) {
        String errorMessage;
        if (error == android.hardware.Camera.CAMERA_ERROR_SERVER_DIED) {
          errorMessage = "Camera server died!";
        } else {
          errorMessage = "Camera error: " + error;
        }
        Logging.e(TAG, errorMessage);
        stopInternal();
        if (error == android.hardware.Camera.CAMERA_ERROR_EVICTED) {
          events.onCameraDisconnected(Camera1Session.this);
        } else {
          events.onCameraError(Camera1Session.this, errorMessage);
        }
      }
    });

    if (captureToTexture) {
      listenForTextureFrames();
    } else {
      listenForBytebufferFrames();
    }
    try {
      camera.startPreview();
    } catch (RuntimeException e) {
      stopInternal();
      events.onCameraError(this, e.getMessage());
    }
  }

  private void stopInternal() {
    Logging.d(TAG, "Stop internal");
    checkIsOnCameraThread();
    if (state == SessionState.STOPPED) {
      Logging.d(TAG, "Camera is already stopped");
      return;
    }

    state = SessionState.STOPPED;
    surfaceTextureHelper.stopListening();
    // Note: stopPreview or other driver code might deadlock. Deadlock in
    // android.hardware.Camera._stopPreview(Native Method) has been observed on
    // Nexus 5 (hammerhead), OS version LMY48I.
    camera.stopPreview();
    camera.release();
    events.onCameraClosed(this);
    Logging.d(TAG, "Stop done");
  }

  private void listenForTextureFrames() {
    surfaceTextureHelper.startListening((VideoFrame frame) -> {
      checkIsOnCameraThread();

      if (state != SessionState.RUNNING) {
        Logging.d(TAG, "Texture frame captured but camera is no longer running.");
        return;
      }

      if (!firstFrameReported) {
        final int startTimeMs =
                (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - constructionTimeNs);
        camera1StartTimeMsHistogram.addSample(startTimeMs);
        firstFrameReported = true;
      }

      // Undo the mirror that the OS "helps" us with.
      // http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
      final VideoFrame modifiedFrame = new VideoFrame(
              CameraSession.createTextureBufferWithModifiedTransformMatrix(
                      (TextureBufferImpl) frame.getBuffer(),
                      /* mirror= */
                      isCameraFrontFacing,
                      /* rotation= */ 0),
              /* rotation= */ getFrameOrientation(), frame.getTimestampNs());
      events.onFrameCaptured(Camera1Session.this, modifiedFrame);
      modifiedFrame.release();
    });
  }

  private void listenForBytebufferFrames() {
    camera.setPreviewCallbackWithBuffer(new android.hardware.Camera.PreviewCallback() {
      @Override
      public void onPreviewFrame(final byte[] data, android.hardware.Camera callbackCamera) {
        checkIsOnCameraThread();

        if (callbackCamera != camera) {
          Logging.e(TAG, "Callback from a different camera. This should never happen.");
          return;
        }

        if (state != SessionState.RUNNING) {
          Logging.d(TAG, "Bytebuffer frame captured but camera is no longer running.");
          return;
        }

        final long captureTimeNs = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime());

        if (!firstFrameReported) {
          final int startTimeMs =
                  (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - constructionTimeNs);
          camera1StartTimeMsHistogram.addSample(startTimeMs);
          firstFrameReported = true;
        }

        final CaptureFormat captureFormat = info.getCaptureFormat();
        VideoFrame.Buffer frameBuffer = new NV21Buffer(
                data, captureFormat.width, captureFormat.height,
                () -> cameraThreadHandler.post(() -> {
                  if (state == SessionState.RUNNING) {
                    camera.addCallbackBuffer(data);
                  }
                }));
        final VideoFrame frame = new VideoFrame(frameBuffer, getFrameOrientation(), captureTimeNs);
        events.onFrameCaptured(Camera1Session.this, frame);
        frame.release();
      }
    });
  }

  private int getDeviceOrientation() {
    int orientation = 0;
    switch (displayRotation) {
      case Surface.ROTATION_90:
        orientation = 90;
        break;
      case Surface.ROTATION_180:
        orientation = 180;
        break;
      case Surface.ROTATION_270:
        orientation = 270;
        break;
      case Surface.ROTATION_0:
      default:
        orientation = 0;
        break;
    }
    return orientation;
  }

  private int getFrameOrientation() {
    int rotation = getDeviceOrientation();
    if (!isCameraFrontFacing) {
      rotation = 360 - rotation;
    }
    return (cameraOrientation + rotation) % 360;
  }

  private void checkIsOnCameraThread() {
    if (Thread.currentThread() != cameraThreadHandler.getLooper().getThread()) {
      throw new IllegalStateException("Wrong thread");
    }
  }

  @Override
  public void setFlashState(boolean isFlashTorchOn) {
    this.isFlashTorchOn = isFlashTorchOn;
  }

  @Override
  public void restartVideoRequest() throws CameraAccessException {
    updateCameraParameters(camera, info, captureToTexture, isFlashTorchOn);
  }

  @Override
  public void triggerAutofocus() throws CameraAccessException {
    // no op
  }

  @Nullable
  public static CameraSession create(
          CreateSessionCallback callback,
          Events events,
          boolean captureToTexture,
          Context applicationContext,
          SurfaceTextureHelper surfaceTextureHelper,
          int cameraIndex,
          int width,
          int height,
          int frameRate) {

    final WindowManager windowManager = (WindowManager) applicationContext
            .getSystemService(Context.WINDOW_SERVICE);
    final int displayRotation = windowManager != null ? windowManager
            .getDefaultDisplay()
            .getRotation() : 0;

    final RtcCameraInfo cameraInfo = new Camera1InfoImpl(
            cameraIndex,
            width,
            height,
            frameRate,
            false,
            false);

    return create(
            callback,
            events,
            captureToTexture,
            surfaceTextureHelper,
            cameraInfo,
            displayRotation);
  }

  @Nullable
  public static CameraSession create(
          Context applicationContext,
          CreateSessionCallback callback,
          Events events,
          boolean captureToTexture,
          SurfaceTextureHelper surfaceTextureHelper,
          RtcCameraInfo cameraInfo) {

    final WindowManager windowManager = (WindowManager) applicationContext.getSystemService(Context.WINDOW_SERVICE);
    final int displayRotation = windowManager != null
            ? windowManager.getDefaultDisplay().getRotation()
            : 0;

    return create(
            callback,
            events,
            captureToTexture,
            surfaceTextureHelper,
            cameraInfo,
            displayRotation);
  }

}
