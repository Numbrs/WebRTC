package org.webrtc;

import android.graphics.Rect;
import android.support.annotation.Nullable;
import android.util.Range;

import java.util.List;

/**
 * Wrapper that encapsulates all the necessary info for Camera1 and Camera2 implementations.
 *
 * @see Camera1InfoImpl
 * @see Camera2InfoImpl
 */
@SuppressWarnings("deprecation")
public interface RtcCameraInfo {

    /**
     * Id of the camera. Used mostly in Camera2 to identify the camera.
     *
     * @return the camera id.
     */
    @Nullable String getCameraId();

    /**
     * Index of the camera. Used mostly in Camera1 to identify the camera.
     *
     * @return the camera id.
     */
    int getCameraIndex();

    /**
     * The rotation angle in degrees relative to the orientation of the camera.
     * Rotation can only be 0, 90, 180 or 270.
     *
     * @see android.hardware.Camera.CameraInfo
     *
     * @return the rotation angle relative to the device orientation.
     */
    int getOrientation();

    /**
     * Facing of the lens. Its value can be 0 or 1.
     *
     * The direction that the camera faces. It should be for Camera1
     * Camera.CameraInfo.CAMERA_FACING_FRONT or Camera.CameraInfo.CAMERA_FACING_BACK. For Camera2,
     * it should be CameraMetadata.LENS_FACING_FRONT and CameraMetadata.LENS_FACING_BACK.
     *
     * Be aware that:
     * In Camera1, the front facing value is 1, the back facing value is 0
     * In Camera2, the front facing value is 0, the back facing value is 1
     *
     * @return the facing of the lens.
     */
    int getLensFacing();

    /**
     * Whether the camera is facing front (Camera.CameraInfo.CAMERA_FACING_FRONT or
     * CameraMetadata.LENS_FACING_FRONT) or not.
     *
     * @return whether the camera faces front or not.
     */
    boolean isFrontFacing();

    /**
     * Whether the camera is facing back (Camera.CameraInfo.CAMERA_FACING_BACK or
     * CameraMetadata.LENS_FACING_BACK) or not.
     *
     * @return whether the camera faces back or not.
     */
    boolean isBackFacing();

    /**
     * The preferred camera width.
     *
     * @return the preferred camera width.
     */
    int getWidth();

    /**
     * The preferred camera height.
     *
     * @return the preferred camera height.
     */
    int getHeight();

    /**
     * The preferred camera minimum frames per second (fps).
     *
     * @return the preferred camera fps.
     */
    int getMinFps();

    /**
     * The unit to which the frames per second (fps) should be divided.
     *
     * @return the fps unit factor.
     */
    int getFpsUnitFactor();

    List<Float> getFocalLengths();

    /**
     * Lists all the available aperture values for this camera.
     *
     * @return available apertures list.
     */
    List<Float> getApertures();

    /**
     * Lists all the available autofocus modes.
     *
     * @return available optical stabilization modes list.
     */
    List<Integer> getAvailableAutofocusModes();

    /**
     * Lists all the available optical stabilization modes.
     *
     * @return available optical stabilization modes list.
     */
    List<Integer> getAvailableOpticalStabilizationModes();

    /**
     * Lists all the available video stabilization modes.
     *
     * @return available video stabilization modes list.
     */
    List<Integer> getAvailableVideoStabilizationModes();


    /**
     * Region in which to apply the autofocus gesture.
     *
     * @return the active autofocus region.
     */
    Rect getActiveArraySizeRect();

    /**
     * Calculates the best picture size (width and height) to be used in a capture format.
     *
     * @return the best picture size.
     */
    @Nullable Size getBestSize();

    /**
     * Calculates the best fps size range available to be used in a capture format.
     *
     * @return the best fps range.
     */
    @Nullable Range<Integer> getBestFpsRange();

    /**
     * Creates a capture format with the best possible size and fps range.
     *
     * @return the best capture format.
     */
    @Nullable CameraEnumerationAndroid.CaptureFormat getCaptureFormat();

    /**
     * Whether the autofocus fix should be used instead of continuous video focus.
     *
     * @return whether the autofocus fix should be applied or not.
     */
    boolean applyAutoFocusFix();


    /**
     * PM-46409: Whether the image stretch fix should be applied or not.
     *
     * @return whether the stretch fix should be applied or not.
     */
    boolean applyImageStretchedFix();

    /**
     * Inits the values based on the given android.hardware.Camera.Parameters values for Camera1.
     *
     * @param parameters the camera1 parameters.
     */
    void init(android.hardware.Camera.Parameters parameters);

    /**
     * Sets new preferred video format width, height and minimumFps
     *
     * @param width the preferred video width.
     * @param height the preferred video height.
     * @param preferredFps the preferred minimum Fps.
     */
    void set(int width, int height, int preferredFps);
}
