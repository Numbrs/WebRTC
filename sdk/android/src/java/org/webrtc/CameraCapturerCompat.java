package org.webrtc;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.media.MediaRecorder;

/**
 * Compatibility class for camera capturer.
 *
 * It allows the usage of either Camera2 or Camera1 apis to create a session.
 *
 * The inner CameraProvider interface allows third party libraries to choose how to provide
 * the camera object.
 */
public class CameraCapturerCompat extends CameraCapturer {

    private final CameraManager cameraManager;
    private final CameraProvider cameraProvider;
    private final boolean useCamera2Api;
    private final int blackFramesFilterPixelsToCheck;
    private final int blackFramesFilterBlackThreshold;

    public CameraCapturerCompat(
            Context appContext,
            CameraManager cameraManager,
            CameraCapturerCompat.CameraProvider cameraProvider,
            CameraEventsHandler eventsHandler,
            String cameraId,
            boolean useCamera2Api,
            int blackFramesFilterPixelsToCheck,
            int blackFramesFilterBlackThreshold) {
        super(
                cameraId,
                eventsHandler,
                useCamera2Api ? new Camera2Enumerator(appContext) : new Camera1Enumerator()
        );
        this.cameraProvider = cameraProvider;
        this.cameraManager = cameraManager;
        this.useCamera2Api = useCamera2Api;
        this.blackFramesFilterPixelsToCheck = blackFramesFilterPixelsToCheck;
        this.blackFramesFilterBlackThreshold = blackFramesFilterBlackThreshold;
    }

    @Override
    protected void createCameraSession(CameraSession.CreateSessionCallback createSessionCallback,
                                       CameraSession.Events events, Context applicationContext,
                                       SurfaceTextureHelper surfaceTextureHelper,
                                       String cameraName, int width, int height, int framerate) {

        RtcCameraInfo camera = cameraProvider.getCamera(cameraName);
        if (useCamera2Api) {
            if (camera != null) {
                session = Camera2Session.create(
                        createSessionCallback, events, applicationContext, cameraManager,
                        surfaceTextureHelper, camera, blackFramesFilterPixelsToCheck,
                        blackFramesFilterBlackThreshold);
            } else {
                session = Camera2Session.create(
                        createSessionCallback, events, applicationContext, cameraManager,
                        surfaceTextureHelper, cameraName, width, height, framerate);
            }
        } else if (camera != null) {
            session = Camera1Session.create(applicationContext, createSessionCallback, events, true,
                    surfaceTextureHelper, camera);

        } else {
            session = Camera1Session.create(createSessionCallback, events, true, applicationContext,
                    surfaceTextureHelper, Camera1Enumerator.getCameraIndex(cameraName),
                    width, height, framerate);
        }
    }

    /**
     * Interface that provides the camera info.
     */
    public interface CameraProvider {

        /**
         * Provides the right camera info based on its id.
         *
         * @param cameraId the camera id.
         *
         * @return the correspondent camera.
         */
        RtcCameraInfo getCamera(String cameraId);
    }

}
