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

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.media.MediaRecorder;
import android.os.Handler;
import android.util.Range;
import android.view.Surface;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.webrtc.CameraEnumerationAndroid.CaptureFormat;

@TargetApi(21)
public class Camera2Session implements CameraSession {
  private static final String TAG = "Camera2Session";

  private static final Histogram camera2StartTimeMsHistogram =
      Histogram.createCounts("WebRTC.Android.Camera2.StartTimeMs", 1, 10000, 50);
  private static final Histogram camera2StopTimeMsHistogram =
      Histogram.createCounts("WebRTC.Android.Camera2.StopTimeMs", 1, 10000, 50);
  private static final Histogram camera2ResolutionHistogram = Histogram.createEnumeration(
      "WebRTC.Android.Camera2.Resolution", CameraEnumerationAndroid.COMMON_RESOLUTIONS.size());

  private static enum SessionState { RUNNING, STOPPED }

  private static final int SIDE_RELATIVE_SIZE = 4;

  private static final int DEFAULT_BLACK_FRAME_PIXELS_TO_CHECK = 0;
  private static final int DEFAULT_BLACK_COLOR_THRESHOLD = -1;

  private final boolean videoFrameEmitTrialEnabled;

  private final Handler cameraThreadHandler;
  private final CreateSessionCallback callback;
  private final Events events;
  private final CameraManager cameraManager;
  private final SurfaceTextureHelper surfaceTextureHelper;
  private final Surface mediaRecorderSurface;
  private final String cameraId;
  private final int width;
  private final int height;
  private final int framerate;

  private final int displayRotation;
  private final int cameraOrientation;
  private final boolean isCameraFrontFacing;
  private final MeteringRectangle focusArea;

  private final RtcCameraInfo camera;
  private final CaptureSessionCallback captureSessionCallback = new CaptureSessionCallback();

  private CaptureFormat captureFormat;

  private boolean isTorchStateOn;
  private CaptureRequest.Builder captureRequestBuilder;

  // Initialized when camera opens
  private CameraDevice cameraDevice;
  private Surface surface;

  // Initialized when capture session is created
  private CameraCaptureSession captureSession;

  // State
  private SessionState state = SessionState.RUNNING;
  private boolean firstFrameReported = false;

  // Used only for stats. Only used on the camera thread.
  private final long constructionTimeNs; // Construction time of this class.

  // black frames filter (PM-46184)
  private final int blackFramesFilterPixelsToCheck;
  private final int blackFramesFilterBlackThreshold;
  private final boolean shouldUseBlackFramesFilter;
  private boolean blackFramesFilterOn;

  private class CameraStateCallback extends CameraDevice.StateCallback {
    private String getErrorDescription(int errorCode) {
      switch (errorCode) {
        case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
          return "Camera device has encountered a fatal error.";
        case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
          return "Camera device could not be opened due to a device policy.";
        case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
          return "Camera device is in use already.";
        case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
          return "Camera service has encountered a fatal error.";
        case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
          return "Camera device could not be opened because"
              + " there are too many other open camera devices.";
        default:
          return "Unknown camera error: " + errorCode;
      }
    }

    @Override
    public void onDisconnected(CameraDevice camera) {
      checkIsOnCameraThread();
      final boolean startFailure = (captureSession == null) && (state != SessionState.STOPPED);
      state = SessionState.STOPPED;
      stopInternal();
      if (startFailure) {
        callback.onFailure(FailureType.DISCONNECTED, "Camera disconnected / evicted.");
      } else {
        events.onCameraDisconnected(Camera2Session.this);
      }
    }

    @Override
    public void onError(CameraDevice camera, int errorCode) {
      checkIsOnCameraThread();
      reportError(getErrorDescription(errorCode));
    }

    @Override
    public void onOpened(CameraDevice camera) {
      checkIsOnCameraThread();

      Logging.d(TAG, "Camera opened.");
      cameraDevice = camera;

      final SurfaceTexture surfaceTexture = surfaceTextureHelper.getSurfaceTexture();
      surfaceTexture.setDefaultBufferSize(captureFormat.width, captureFormat.height);
      surface = new Surface(surfaceTexture);
      List<Surface> surfaces = new ArrayList<Surface>();
      surfaces.add(surface);
      if (mediaRecorderSurface != null) {
        Logging.d(TAG, "Add MediaRecorder surface to capture session.");
        surfaces.add(mediaRecorderSurface);
      }
      try {
        camera.createCaptureSession(surfaces, captureSessionCallback, cameraThreadHandler);
      } catch (CameraAccessException e) {
        reportError("Failed to create capture session. " + e);
        return;
      }
    }

    @Override
    public void onClosed(CameraDevice camera) {
      checkIsOnCameraThread();

      Logging.d(TAG, "Camera device closed.");
      events.onCameraClosed(Camera2Session.this);
    }
  }

  private class CaptureSessionCallback extends CameraCaptureSession.StateCallback {
    @Override
    public void onConfigureFailed(CameraCaptureSession session) {
      checkIsOnCameraThread();
      session.close();
      reportError("Failed to configure capture session.");
    }

    @Override
    public void onConfigured(CameraCaptureSession session) {
      checkIsOnCameraThread();
      Logging.d(TAG, "Camera capture session configured.");
      captureSession = session;
      try {
        startVideoRequest();
      } catch (CameraAccessException e) {
        reportError("Failed to start capture request. " + e);
        return;
      }

      blackFramesFilterOn = shouldUseBlackFramesFilter;
      Logging.d(TAG, "reset blackFramesFilterOn to " + shouldUseBlackFramesFilter);

      surfaceTextureHelper.startListening(
          new SurfaceTextureHelper.OnTextureFrameAvailableListener() {
            @Override
            public void onTextureFrameAvailable(
                int oesTextureId, float[] transformMatrix, long timestampNs) {
              checkIsOnCameraThread();

              if (state != SessionState.RUNNING) {
                Logging.d(TAG, "Texture frame captured but camera is no longer running.");
                surfaceTextureHelper.returnTextureFrame();
                return;
              }

              if(filterBlackFrame(transformMatrix)){
                  return;
              }

              if (!firstFrameReported) {
                firstFrameReported = true;
                final int startTimeMs =
                    (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - constructionTimeNs);
                camera2StartTimeMsHistogram.addSample(startTimeMs);
              }

              int rotation = getFrameOrientation();
              if (isCameraFrontFacing) {
                // Undo the mirror that the OS "helps" us with.
                // http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
                transformMatrix = RendererCommon.multiplyMatrices(
                    transformMatrix, RendererCommon.horizontalFlipMatrix());
              }

              // Undo camera orientation - we report it as rotation instead.
              transformMatrix =
                  RendererCommon.rotateTextureMatrix(transformMatrix, -cameraOrientation);

              if (videoFrameEmitTrialEnabled) {
                VideoFrame.Buffer buffer = surfaceTextureHelper.createTextureBuffer(
                    captureFormat.width, captureFormat.height,
                    RendererCommon.convertMatrixToAndroidGraphicsMatrix(transformMatrix));
                final VideoFrame frame = new VideoFrame(buffer, rotation, timestampNs);
                events.onFrameCaptured(Camera2Session.this, frame);
                frame.release();
              } else {
                events.onTextureFrameCaptured(Camera2Session.this, captureFormat.width,
                    captureFormat.height, oesTextureId, transformMatrix, rotation, timestampNs);
              }
            }
          });
      Logging.d(TAG, "Camera device successfully started.");
      callback.onDone(Camera2Session.this);
    }

    public void startVideoRequest() throws CameraAccessException {
      if (cameraDevice == null) {
        throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED);
      }

      // PM-46409: Use preview for some devices whose image gets stretched.
      final int templateType = !camera.applyImageStretchedFix()
              ? CameraDevice.TEMPLATE_RECORD
              : CameraDevice.TEMPLATE_PREVIEW;
      captureRequestBuilder = cameraDevice.createCaptureRequest(templateType);
      // Set auto exposure fps range.
      Range<Integer> bestFpsRange = camera.getBestFpsRange();
      Logging.d(TAG, "Fps range: " + bestFpsRange);
      captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestFpsRange);
      captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
      captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
      chooseStabilizationMode(captureRequestBuilder);
      chooseFocusMode(captureRequestBuilder);
      chooseTorchState(captureRequestBuilder);

      captureRequestBuilder.addTarget(surface);
      if (mediaRecorderSurface != null) {
        Logging.d(TAG, "Add MediaRecorder surface to CaptureRequest.Builder");
        captureRequestBuilder.addTarget(mediaRecorderSurface);
      }
      captureSession.setRepeatingRequest(
              captureRequestBuilder.build(), new CameraCaptureCallback(), cameraThreadHandler
      );
    }

    // Prefers optical stabilization over software stabilization if available. Only enables one of
    // the stabilization modes at a time because having both enabled can cause strange results.
    private void chooseStabilizationMode(CaptureRequest.Builder captureRequestBuilder) {
      final List<Integer> availableOpticalStabilization = camera.getAvailableOpticalStabilizationModes();
      if (availableOpticalStabilization != null) {
        for (int mode : availableOpticalStabilization) {
          if (mode == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) {
            captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
            captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            Logging.d(TAG, "Using optical stabilization.");
            return;
          }
        }
      }
      // If no optical mode is available, try software.
      final List<Integer> availableVideoStabilization = camera.getAvailableVideoStabilizationModes();
      for (int mode : availableVideoStabilization) {
        if (mode == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
          captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
              CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
          captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
              CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
          Logging.d(TAG, "Using video stabilization.");
          return;
        }
      }
      Logging.d(TAG, "Stabilization not available.");
    }

    /**
     * Sets a specific focus mode, only if the autofocus_samsung_fix flag is passed.
     */
    private void chooseFocusMode(CaptureRequest.Builder captureRequestBuilder) {
      if (camera.applyAutoFocusFix()) {
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
        return;
      }
      final List<Integer> availableFocusModes = camera.getAvailableAutofocusModes();
      for (int mode : availableFocusModes) {
        if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) {
          captureRequestBuilder.set(
              CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
          Logging.d(TAG, "Using continuous video auto-focus.");
          return;
        }
      }
      Logging.d(TAG, "Auto-focus is not available.");
    }

    private void chooseTorchState(final CaptureRequest.Builder captureRequestBuilder) {
      final int flashMode =
              isTorchStateOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF;
      captureRequestBuilder.set(CaptureRequest.FLASH_MODE, flashMode);
    }
  }

  private class CameraCaptureCallback extends CameraCaptureSession.CaptureCallback {
    @Override
    public void onCaptureFailed(
        CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
      Logging.d(TAG, "Capture failed: " + failure);
    }
  }

  private Camera2Session(
          CreateSessionCallback callback,
          Events events,
          CameraManager cameraManager,
          SurfaceTextureHelper surfaceTextureHelper,
          MediaRecorder mediaRecorder,
          RtcCameraInfo camera,
          int displayRotation,
          int blackFramesFilterPixelsToCheck,
          int blackFramesFilterBlackThreshold) {

    this.camera = camera;
    this.displayRotation = displayRotation;
    cameraId = camera.getCameraId();
    width = camera.getWidth();
    height = camera.getHeight();
    framerate = camera.getMinFps();
    this.blackFramesFilterPixelsToCheck = blackFramesFilterPixelsToCheck;
    this.blackFramesFilterBlackThreshold = blackFramesFilterBlackThreshold;
    this.shouldUseBlackFramesFilter = blackFramesFilterBlackThreshold > -1 && blackFramesFilterPixelsToCheck > 0;

    Logging.d(TAG, "Create new camera2 session on camera " + cameraId);
    videoFrameEmitTrialEnabled =
            PeerConnectionFactory.fieldTrialsFindFullName(PeerConnectionFactory.VIDEO_FRAME_EMIT_TRIAL)
                    .equals(PeerConnectionFactory.TRIAL_ENABLED);

    constructionTimeNs = System.nanoTime();

    cameraThreadHandler = new Handler();
    mediaRecorderSurface = (mediaRecorder != null) ? mediaRecorder.getSurface() : null;

    this.callback = callback;
    this.events = events;
    this.cameraManager = cameraManager;
    this.surfaceTextureHelper = surfaceTextureHelper;

    focusArea = measureFocusArea();

    cameraOrientation = camera.getOrientation();
    isCameraFrontFacing = camera.isFrontFacing();

    start();
  }

  private void start() {
    checkIsOnCameraThread();
    Logging.d(TAG, "start");

    findCaptureFormat();
    openCamera();
  }

  private void findCaptureFormat() {
    checkIsOnCameraThread();
    camera.set(width, height, framerate);

    captureFormat = camera.getCaptureFormat();
    if (captureFormat == null) {
      reportError("No supported capture formats.");
      return;
    }

    Size bestSize = camera.getBestSize();
    CameraEnumerationAndroid.reportCameraResolution(camera2ResolutionHistogram, bestSize);
    Logging.d(TAG, "Using capture format: " + captureFormat);
  }

  @SuppressLint("MissingPermission")
  private void openCamera() {
    checkIsOnCameraThread();

    Logging.d(TAG, "Opening camera " + cameraId);
    events.onCameraOpening();

    try {
      cameraManager.openCamera(cameraId, new CameraStateCallback(), cameraThreadHandler);
    } catch (CameraAccessException e) {
      reportError("Failed to open camera: " + e);
      return;
    }
  }

  @Override
  public void stop() {
    Logging.d(TAG, "Stop camera2 session on camera " + cameraId);
    checkIsOnCameraThread();
    if (state != SessionState.STOPPED) {
      final long stopStartTime = System.nanoTime();
      state = SessionState.STOPPED;
      stopInternal();
      final int stopTimeMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStartTime);
      camera2StopTimeMsHistogram.addSample(stopTimeMs);
    }
  }

  private void stopInternal() {
    Logging.d(TAG, "Stop internal");
    checkIsOnCameraThread();

    surfaceTextureHelper.stopListening();

    if (captureSession != null) {
      captureSession.close();
      captureSession = null;
    }
    if (surface != null) {
      surface.release();
      surface = null;
    }
    if (cameraDevice != null) {
      cameraDevice.close();
      cameraDevice = null;
    }

    Logging.d(TAG, "Stop done");
  }

  private void reportError(String error) {
    checkIsOnCameraThread();
    Logging.e(TAG, "Error: " + error);

    final boolean startFailure = (captureSession == null) && (state != SessionState.STOPPED);
    state = SessionState.STOPPED;
    stopInternal();
    if (startFailure) {
      callback.onFailure(FailureType.ERROR, error);
    } else {
      events.onCameraError(this, error);
    }
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

  private MeteringRectangle measureFocusArea() {
    final Rect sensorArraySize = camera.getActiveArraySizeRect();
    final int side = Math.min(sensorArraySize.width(), sensorArraySize.height()) / SIDE_RELATIVE_SIZE;
    final int x = sensorArraySize.centerX() - (side / 2);
    final int y = sensorArraySize.centerY() - (side / 2);
    return new MeteringRectangle(x, y, side, side, MeteringRectangle.METERING_WEIGHT_MAX - 1);
  }

  @Override
  public void setFlashState(boolean isFlashTorchOn) {
    isTorchStateOn = isFlashTorchOn;
  }

  @Override
  public void restartVideoRequest() throws CameraAccessException {
    captureSessionCallback.startVideoRequest();
  }

  @Override
  public void triggerAutofocus() throws CameraAccessException {
    if (!camera.applyAutoFocusFix()) {
      return;
    }
    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{focusArea});
    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
    captureSession.capture(captureRequestBuilder.build(), null, cameraThreadHandler);

    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{focusArea});
    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
    captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, cameraThreadHandler);
  }

  /**
   * Called on each captured frame. The filter flag is reset to initial state every time a capturing
   * session starts, it is then turned off when the first non-black frame is found.
   */
  private boolean filterBlackFrame(float[] transformMatrix) {
    if (blackFramesFilterOn) {
      final VideoFrame.TextureBuffer buffer = surfaceTextureHelper.createTextureBuffer(
          captureFormat.width, captureFormat.height,
          RendererCommon.convertMatrixToAndroidGraphicsMatrix(transformMatrix)
      );
      final VideoFrame.I420Buffer i420Buffer = buffer.toI420();
      final boolean isBlackFrame = isBlackFrame(i420Buffer);
      i420Buffer.release();
      buffer.release();
      if (isBlackFrame) {
        Logging.d(TAG, "Skipping black frame");
        surfaceTextureHelper.returnTextureFrame();
        return true;
      } else {
        blackFramesFilterOn = false;
        Logging.d(TAG, "black frame found - blackFramesFilterOn set to false");
      }
    }
    return false;
  }

  /**
   * Checks a number of pixels (defined by {@link Camera2Session:blackFramesFilterPixelsToCheck}, on
   * a given frame, if one of the pixel's luminance exceeds a certain value
   * ({@link Camera2Session:blackFramesFilterBlackThreshold}) then the frame si considered to be
   * non-black
   */
  private boolean isBlackFrame(VideoFrame.I420Buffer buffer) {
    final ByteBuffer yData = buffer.getDataY();
    final int yDataSize = buffer.getStrideY() * buffer.getHeight();
    final int pixelsStep = yDataSize / blackFramesFilterPixelsToCheck;
    for (int i=0; i < yDataSize;i += pixelsStep) {
      final byte y = yData.get(i);
      final int value = y & 0xff;
      if (value > blackFramesFilterBlackThreshold) {
        return false;
      }
    }
    return true;
  }

  public static Camera2Session create(
          CreateSessionCallback callback,
          Events events,
          Context applicationContext,
          CameraManager cameraManager,
          SurfaceTextureHelper surfaceTextureHelper,
          MediaRecorder mediaRecorder,
          RtcCameraInfo camera,
          int blackFramesFilterPixelsToCheck,
          int blackFramesFilterBlackThreshold) {

    final WindowManager windowManager = (WindowManager) applicationContext.getSystemService(Context.WINDOW_SERVICE);
    final int displayRotation = windowManager != null ? windowManager.getDefaultDisplay().getRotation() : 0;

    return new Camera2Session(
            callback,
            events,
            cameraManager,
            surfaceTextureHelper,
            mediaRecorder,
            camera,
            displayRotation,
            blackFramesFilterPixelsToCheck,
            blackFramesFilterBlackThreshold
    );
  }

  public static Camera2Session create(
          CreateSessionCallback callback,
          Events events,
          Context applicationContext,
          CameraManager cameraManager,
          SurfaceTextureHelper surfaceTextureHelper,
          MediaRecorder mediaRecorder,
          String cameraId,
          int width,
          int height,
          int frameRate) {

    final WindowManager windowManager = (WindowManager) applicationContext.getSystemService(Context.WINDOW_SERVICE);
    final int displayRotation = windowManager != null ? windowManager.getDefaultDisplay().getRotation() : 0;

    final RtcCameraInfo camera = Camera2InfoImpl.create(
            applicationContext,
            cameraId,
            1,
            width,
            height,
            frameRate,
            false,
            false
    );

    return new Camera2Session(
            callback,
            events,
            cameraManager,
            surfaceTextureHelper,
            mediaRecorder,
            camera,
            displayRotation,
            DEFAULT_BLACK_FRAME_PIXELS_TO_CHECK,
            DEFAULT_BLACK_COLOR_THRESHOLD);
  }
}
