package org.webrtc;

import android.hardware.camera2.CameraAccessException;

/**
 * Common interface for sessions and camera capturers.
 */
public interface CameraApi {

    /**
     * Allows to start the video request again.
     *
     * @throws CameraAccessException error when accessing the camera.
     */
    void restartVideoRequest() throws CameraAccessException;

    /**
     * Allows to set the torch to on and of state.
     *
     * @param isFlashTorchOn the current flash state.
     */
    void setFlashState(boolean isFlashTorchOn);

    /**
     * Triggers the autofocus on devices that have the applyAutofocusFix set to true.
     *
     * @throws CameraAccessException error when accessing the camera.
     */
    void triggerAutofocus() throws CameraAccessException;

}
