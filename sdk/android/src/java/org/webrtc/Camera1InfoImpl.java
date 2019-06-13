package org.webrtc;

import android.annotation.TargetApi;
import android.graphics.Rect;
import android.os.Build;
import android.support.annotation.Nullable;
import android.util.Range;

import java.util.List;

import static android.content.ContentValues.TAG;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.webrtc.CameraEnumerationAndroid.CaptureFormat;
import static org.webrtc.CameraEnumerationAndroid.getClosestSupportedFramerateRange;
import static org.webrtc.CameraEnumerationAndroid.getClosestSupportedSize;

/**
 * Wrapper containing all necessary info for camera1 devices.
 */
@SuppressWarnings("deprecation")
public class Camera1InfoImpl implements RtcCameraInfo {

    @Nullable private final String cameraId;
    private final int cameraIndex;
    private final boolean fixAutoFocus;

    private final int lensFacing;
    private final int orientation;

    private List<Float> focalLengths = emptyList();
    private List<Float> apertures = emptyList();

    private List<Integer> autofocusModes = emptyList();
    private List<Size> availableSizes = emptyList();
    private List<int[]> availableFpsRanges = emptyList();
    private List<CaptureFormat.FramerateRange> supportedFrameRates = emptyList();

    private int width;
    private int height;
    private int preferredFps;

    @Nullable private Size bestSize;
    @Nullable private CaptureFormat captureFormat;
    @Nullable private CaptureFormat.FramerateRange bestFpsRange;
    private boolean applyImageStretchedFix;

    Camera1InfoImpl(
            int cameraIndex,
            int width,
            int height,
            int preferredFps,
            boolean fixAutoFocus,
            boolean applyImageStretchedFix) {
        cameraId = Camera1Enumerator.getDeviceName(cameraIndex);
        this.cameraIndex = cameraIndex;
        this.width = width;
        this.height = height;
        this.preferredFps = preferredFps;
        this.fixAutoFocus = fixAutoFocus;
        this.applyImageStretchedFix = applyImageStretchedFix;

        android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
        android.hardware.Camera.getCameraInfo(cameraIndex, info);
        lensFacing = info.facing;
        orientation = info.orientation;
    }

    @Override
    public void init(android.hardware.Camera.Parameters parameters) {
        focalLengths = singletonList(parameters.getFocalLength());
        final List<android.hardware.Camera.Size> supportedPreviewSizes = parameters.getSupportedPreviewSizes();
        availableSizes = Camera1Enumerator.convertSizes(supportedPreviewSizes);
        availableFpsRanges = parameters.getSupportedPreviewFpsRange();
        supportedFrameRates = Camera1Enumerator.convertFramerates(availableFpsRanges);
    }

    @Nullable
    @Override
    public String getCameraId() {
        return cameraId;
    }

    @Override
    public int getCameraIndex() {
        return cameraIndex;
    }

    @Override
    public int getLensFacing() {
        return lensFacing;
    }

    @Override
    public boolean isFrontFacing() {
        return lensFacing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT;
    }

    @Override
    public boolean isBackFacing() {
        return lensFacing == android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK;
    }

    @Override
    public int getOrientation() {
        return orientation;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinFps() {
        return preferredFps;
    }

    @Override
    public int getFpsUnitFactor() {
        return 1;
    }

    @Override
    public List<Float> getFocalLengths() {
        return focalLengths;
    }

    @Override
    public List<Float> getApertures() {
        return apertures;
    }

    @Override
    public List<Integer> getAvailableAutofocusModes() {
        return autofocusModes;
    }

    @Override
    public List<Integer> getAvailableOpticalStabilizationModes() {
        return emptyList();
    }

    @Override
    public List<Integer> getAvailableVideoStabilizationModes() {
        return emptyList();
    }

    @Override
    public Rect getActiveArraySizeRect() {
        return new Rect();
    }

    @Override
    public boolean applyAutoFocusFix() {
        return fixAutoFocus;
    }

    @Override
    public boolean applyImageStretchedFix() {
        return applyImageStretchedFix;
    }

    @Override
    public void set(int width, int height, int preferredFps) {
        if (this.width != width || this.height != height || this.preferredFps != preferredFps) {
            this.width = width;
            this.height = height;
            this.preferredFps = preferredFps;
            calculateCaptureFormat();
        }
    }

    @Nullable
    @Override
    public CaptureFormat getCaptureFormat() {
        if (captureFormat != null) {
            return captureFormat;
        }

        calculateCaptureFormat();
        return captureFormat;
    }

    @Nullable
    @Override
    public Size getBestSize() {
        if (bestSize != null) {
            return bestSize;
        }

        calculateCaptureFormat();
        return bestSize;
    }

    @Nullable
    @Override
    public Range<Integer> getBestFpsRange() {
        if (bestFpsRange != null) {
            return toFpsRange();
        }

        calculateCaptureFormat();
        return toFpsRange();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private Range<Integer> toFpsRange() {
        return Range.create(getMinFps(), getMinFps());
    }

    private void calculateCaptureFormat() {
        Logging.d(TAG, "Available preview sizes: " + availableSizes);
        Logging.d(TAG, "Available fps ranges: " + availableFpsRanges);
        if (availableSizes.isEmpty() || availableFpsRanges.isEmpty()) {
            return;
        }

        bestSize = getClosestSupportedSize(availableSizes, width, height);
        bestFpsRange = getClosestSupportedFramerateRange(supportedFrameRates, preferredFps);
        captureFormat = new CaptureFormat(bestSize.width, bestSize.height, bestFpsRange);

        Logging.d(TAG, "Using capture format: " + captureFormat);
    }

}
