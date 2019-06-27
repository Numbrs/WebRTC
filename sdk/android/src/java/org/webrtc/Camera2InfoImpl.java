package org.webrtc;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.os.Build;
import android.util.Range;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static android.content.ContentValues.TAG;

/**
 * Wrapper containing all necessary info for camera1 devices.
 */
@SuppressWarnings("deprecation")
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2InfoImpl implements RtcCameraInfo {

    private static final List<Integer> EMPTY_INT_LIST = Collections.emptyList();
    private static final List<Float> EMPTY_FLOAT_LIST = Collections.emptyList();
    private static final List<Range<Integer>> EMPTY_RANGE_LIST = Collections.emptyList();

    private final String cameraId;
    private final int cameraIndex;
    private final int lensFacing;
    private final int orientation;

    private final List<Float> apertures;
    private final List<Float> focalLengths;

    private final List<Integer> availableAutofocusModes;
    private final List<Integer> availableOpticalStabilizationModes;
    private final List<Integer> availableVideoStabilizationModes;

    private final List<Size> availableSizes;
    private final List<CameraEnumerationAndroid.CaptureFormat> availableFormats;
    private final List<CameraEnumerationAndroid.CaptureFormat.FramerateRange> frameRateRanges;

    private final Rect activeArraySizeRect;

    private int width;
    private int height;
    private int preferredFps;
    private final int fpsUnitFactor;
    private final boolean applyAutoFocusFix;
    private final boolean applyImageStretchedFix;

    private CameraEnumerationAndroid.CaptureFormat captureFormat = null;
    private CameraEnumerationAndroid.CaptureFormat.FramerateRange bestFpsRange = null;
    private Size bestSize = null;


    public static RtcCameraInfo create(
            Context applicationContext,
            String cameraId,
            int cameraIndex,
            int width,
            int height,
            int frameRate,
            boolean applyAutofocusFix,
            boolean applyImageStretchedFix) {

        CameraManager cameraManager = (CameraManager) applicationContext.getSystemService(Context.CAMERA_SERVICE);
        CameraCharacteristics characteristics = null;
        try {
            characteristics = cameraManager != null ? cameraManager.getCameraCharacteristics(cameraId) : null;
        } catch (CameraAccessException e) {
            Logging.e("Error accessing camera characteristics", e.getLocalizedMessage());
        }

        return new Camera2InfoImpl (
                cameraId,
                cameraIndex,
                characteristics,
                Camera2Enumerator.getSupportedSizes(characteristics),
                Camera2Enumerator.getSupportedFormats(cameraManager, cameraId),
                width,
                height,
                frameRate,
                applyAutofocusFix,
                applyImageStretchedFix
        );
    }

    public Camera2InfoImpl(
            CameraManager cameraManager,
            String cameraId,
            int cameraIndex,
            CameraCharacteristics characteristics,
            int width,
            int height,
            int preferredFps,
            boolean applyAutofocusFix,
            boolean applyImageStretchedFix) {
        this(
                cameraId,
                cameraIndex,
                characteristics,
                Camera2Enumerator.getSupportedSizes(characteristics),
                Camera2Enumerator.getSupportedFormats(cameraManager, cameraId),
                width,
                height,
                preferredFps,
                applyAutofocusFix,
                applyImageStretchedFix
        );
    }

    public Camera2InfoImpl(
            String cameraId,
            int cameraIndex,
            CameraCharacteristics characteristics,
            List<Size> availableSizes,
            List<CameraEnumerationAndroid.CaptureFormat> availableFormats,
            int width,
            int height,
            int preferredFps,
            boolean applyAutofocusFix,
            boolean applyImageStretchedFix) {
        this.cameraId = cameraId;
        this.cameraIndex = cameraIndex;
        this.width = width;
        this.height = height;
        this.preferredFps = preferredFps;
        this.applyAutoFocusFix = applyAutofocusFix;
        this.applyImageStretchedFix = applyImageStretchedFix;

        this.availableSizes = availableSizes;
        this.availableFormats = availableFormats;

        lensFacing =
                getInt(characteristics.get(CameraCharacteristics.LENS_FACING), CameraCharacteristics.LENS_FACING_FRONT);
        orientation =
                getInt(characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION), 0);

        apertures =
                getFloatList(characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES));
        focalLengths =
                getFloatList(characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS));

        availableAutofocusModes =
                getIntList(characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES));
        availableOpticalStabilizationModes =
                getIntList(characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION));

        availableVideoStabilizationModes =
                getIntList(characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES));

        activeArraySizeRect = getRect(characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE));

        Range<Integer>[] fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (fpsRanges != null && fpsRanges.length > 0) {
            fpsUnitFactor = Camera2Enumerator.getFpsUnitFactor(fpsRanges);
            frameRateRanges = Camera2Enumerator.convertFramerates(fpsRanges, fpsUnitFactor);
        } else {
            fpsUnitFactor = 1;
            frameRateRanges = Collections.emptyList();
        }

        calculateCaptureFormat();
    }

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
        return lensFacing == CameraMetadata.LENS_FACING_FRONT;
    }

    @Override
    public boolean isBackFacing() {
        return lensFacing == CameraMetadata.LENS_FACING_BACK;
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
        return fpsUnitFactor;
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
    public boolean applyAutoFocusFix() {
        return applyAutoFocusFix;
    }

    @Override
    public boolean applyImageStretchedFix() {
        return applyImageStretchedFix;
    }

    @Override
    public void init(android.hardware.Camera.Parameters parameters) {
        // no op
    }

    @Override
    public Rect getActiveArraySizeRect() {
        return activeArraySizeRect;
    }

    @Override
    public List<Integer> getAvailableAutofocusModes() {
        return availableAutofocusModes;
    }

    @Override
    public List<Integer> getAvailableOpticalStabilizationModes() {
        return availableOpticalStabilizationModes;
    }

    @Override
    public List<Integer> getAvailableVideoStabilizationModes() {
        return availableVideoStabilizationModes;
    }

    private static int getInt(Integer expectedValue, int defaultValue) {
        return expectedValue != null ? expectedValue : defaultValue;
    }

    private static Rect getRect(Rect expectedValue) {
        return expectedValue != null ? expectedValue : new Rect();
    }

    private static List<Integer> getIntList(int[] expectedValue) {
        if (expectedValue == null || expectedValue.length == 0) {
            return EMPTY_INT_LIST;
        }

        final List<Integer> items = new ArrayList<Integer>(expectedValue.length);
        for (int value : expectedValue) {
            items.add(value);
        }
        return items;
    }

    private static List<Float> getFloatList(float[] expectedValue) {
        if (expectedValue == null || expectedValue.length == 0) {
            return EMPTY_FLOAT_LIST;
        }
        final List<Float> items = new ArrayList<Float>(expectedValue.length);
        for (float value : expectedValue) {
            items.add(value);
        }
        return items;
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

    @Override
    public CameraEnumerationAndroid.CaptureFormat getCaptureFormat() {
        if (captureFormat != null) {
            return captureFormat;
        }

        calculateCaptureFormat();
        return captureFormat;
    }

    @Override
    public Size getBestSize() {
        if (bestSize != null) {
            return bestSize;
        }

        calculateCaptureFormat();
        return bestSize;
    }

    @Override
    public Range<Integer> getBestFpsRange() {
        if (bestFpsRange != null) {
            return toFpsRange();
        }

        calculateCaptureFormat();
        return toFpsRange();
    }

    private Range<Integer> toFpsRange() {
        Logging.d(TAG, "BestFpsRange: " + bestFpsRange);
        Logging.d(TAG, "PreferredFps: " + preferredFps);
        Logging.d(TAG, "FpsUnitFactor: " + fpsUnitFactor);
        return new Range<>(
                bestFpsRange.min / fpsUnitFactor,
                bestFpsRange.max / fpsUnitFactor
        );
    }

    private void calculateCaptureFormat() {
        Logging.d(TAG, "Available preview sizes: " + availableSizes);
        Logging.d(TAG, "Available fps ranges: " + frameRateRanges);
        if (availableSizes.isEmpty() || frameRateRanges.isEmpty()) {
            return;
        }

        bestSize = CameraEnumerationAndroid.getClosestSupportedSize(availableSizes, width, height);
        bestFpsRange = CameraEnumerationAndroid.getClosestSupportedFramerateRange(frameRateRanges, preferredFps);
        captureFormat = new CameraEnumerationAndroid.CaptureFormat(bestSize.width, bestSize.height, bestFpsRange);

        Logging.d(TAG, "Using capture format: " + captureFormat);
    }

}
