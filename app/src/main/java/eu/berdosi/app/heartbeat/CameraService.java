package eu.berdosi.app.heartbeat;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

class CameraService {
    /** Receives the mean color of every camera frame, on the camera frame thread. */
    interface FrameListener {
        /**
         * @param timestampNs sensor timestamp of the frame; exact relative to other frames, but
         *                    the time base differs between devices
         * @param meanRed     mean color components, 0..255 nominally; computed from the YUV means
         *                    without clipping, so they can leave that range slightly
         */
        void onFrame(long timestampNs, float meanRed, float meanGreen, float meanBlue);
    }

    // frames are only averaged, so a small image is enough
    private static final int FRAME_MIN_PIXELS = 320 * 240;

    private String cameraId;
    private final Activity activity;
    private final Handler handler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession previewSession;
    // written on the main thread, compared in camera callbacks
    private volatile ImageReader imageReader;
    private HandlerThread frameThread;
    private Handler frameHandler;
    private boolean exposureLockAvailable;

    private volatile FrameListener frameListener;

    // latest camera state as reported in the capture results, for logging and export
    private volatile int aeState = -1;
    private volatile long exposureTimeNs = 0;
    private volatile int sensitivityIso = 0;

    private CaptureRequest.Builder previewCaptureRequestBuilder;

    CameraService(Activity _activity, Handler _handler) {
        activity = _activity;
        handler = _handler;
    }

    void setFrameListener(FrameListener listener) {
        frameListener = listener;
    }

    void start(Surface previewSurface) {

        CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = Objects.requireNonNull(cameraManager).getCameraIdList()[0];
        } catch (CameraAccessException | NullPointerException | ArrayIndexOutOfBoundsException e) {
            Log.e("camera", "No access to camera", e);
            handler.sendMessage(Message.obtain(
                handler,
                MainActivity.MESSAGE_CAMERA_NOT_AVAILABLE,
                "No access to camera...."));
        }

        try {

            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.println(Log.ERROR, "camera", "No permission to take photos");
                handler.sendMessage(Message.obtain(
                    handler,
                    MainActivity.MESSAGE_CAMERA_NOT_AVAILABLE,
                    "No permission to take photos"));
                return;
            }

            // message has been sent to MainActivity, this method can return.
            if (cameraId == null) {
                return;
            };

            CameraCharacteristics characteristics = Objects.requireNonNull(cameraManager).getCameraCharacteristics(cameraId);
            exposureLockAvailable = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE));
            Size frameSize = chooseFrameSize(characteristics);
            Log.d("camera", "frame size " + frameSize + ", exposure lock available: " + exposureLockAvailable);

            // frames are analyzed on their own thread, not on the UI thread
            frameThread = new HandlerThread("CameraFrames");
            frameThread.start();
            frameHandler = new Handler(frameThread.getLooper());

            final ImageReader reader = ImageReader.newInstance(frameSize.getWidth(), frameSize.getHeight(), ImageFormat.YUV_420_888, 4);
            reader.setOnImageAvailableListener(this::onImageAvailable, frameHandler);
            imageReader = reader;

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    if (reader != imageReader) {
                        // stopped while the camera was being opened
                        camera.close();
                        return;
                    }
                    cameraDevice = camera;

                    CameraCaptureSession.StateCallback stateCallback = new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            previewSession = session;
                            try {

                                // the camera and the reader of this session, not the fields: those are
                                // already cleared if stop() was called while the session was set up
                                previewCaptureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                previewCaptureRequestBuilder.addTarget(previewSurface); // this is previewSurface
                                previewCaptureRequestBuilder.addTarget(reader.getSurface());
                                previewCaptureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);

                                setRepeatingRequest();

                            } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                                // also thrown when the camera was closed in the meantime
                                if (e.getMessage() != null) {
                                    Log.println(Log.ERROR, "camera", e.getMessage());
                                }
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.println(Log.ERROR, "camera", "Session configuration failed");
                        }
                    };

                    try {
                        // deprecated in API 30, but changing it would bump minSdkVersion to 28.
                        //noinspection deprecation
                        camera.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()), stateCallback, null); //1
                    } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                        if (e.getMessage() != null) {
                            Log.println(Log.ERROR, "camera", e.getMessage());
                        }
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                }
            }, null);
        } catch (CameraAccessException | SecurityException e) {
            if (e.getMessage() != null) {
                Log.println(Log.ERROR, "camera", e.getMessage());
                handler.sendMessage(Message.obtain(
                    handler,
                    MainActivity.MESSAGE_CAMERA_NOT_AVAILABLE,
                    e.getMessage()));
            }
        }
    }

    /**
     * Lock auto exposure and auto white balance, so that the camera does not readjust the
     * brightness while the pulse is measured - or release the lock again.
     */
    void setExposureLocked(boolean locked) {
        if (!exposureLockAvailable || previewSession == null || previewCaptureRequestBuilder == null) return;
        previewCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, locked);
        previewCaptureRequestBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, locked);
        try {
            setRepeatingRequest();
        } catch (CameraAccessException | IllegalStateException e) {
            Log.println(Log.ERROR, "camera", "cannot change exposure lock: " + e.getMessage());
        }
    }

    /**
     * Auto exposure state from the latest capture result, one of CaptureResult.CONTROL_AE_STATE_*,
     * or -1 if the camera does not report it.
     */
    int getAeState() {
        return aeState;
    }

    boolean isExposureLockAvailable() {
        return exposureLockAvailable;
    }

    /** Camera exposure state as last reported, e.g. "LOCKED, 33.3 ms, ISO 400". */
    String describeExposure() {
        return String.format(Locale.ROOT, "%s, %.1f ms, ISO %d",
                describeAeState(aeState), exposureTimeNs / 1e6, sensitivityIso);
    }

    private static String describeAeState(int state) {
        switch (state) {
            case CaptureResult.CONTROL_AE_STATE_INACTIVE: return "INACTIVE";
            case CaptureResult.CONTROL_AE_STATE_SEARCHING: return "SEARCHING";
            case CaptureResult.CONTROL_AE_STATE_CONVERGED: return "CONVERGED";
            case CaptureResult.CONTROL_AE_STATE_LOCKED: return "LOCKED";
            case CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED: return "FLASH_REQUIRED";
            case CaptureResult.CONTROL_AE_STATE_PRECAPTURE: return "PRECAPTURE";
            default: return "unknown";
        }
    }

    private void setRepeatingRequest() throws CameraAccessException {
        previewSession.setRepeatingRequest(previewCaptureRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                Integer state = result.get(CaptureResult.CONTROL_AE_STATE);
                Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                if (state != null) aeState = state;
                if (exposure != null) exposureTimeNs = exposure;
                if (iso != null) sensitivityIso = iso;
            }
        }, frameHandler);
    }

    /** The smallest YUV size with at least FRAME_MIN_PIXELS, or the largest one if all are smaller. */
    private static Size chooseFrameSize(CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = Objects.requireNonNull(map).getOutputSizes(ImageFormat.YUV_420_888);
        Arrays.sort(sizes, (a, b) -> Long.compare(
                (long) a.getWidth() * a.getHeight(), (long) b.getWidth() * b.getHeight()));
        for (Size size : sizes) {
            if ((long) size.getWidth() * size.getHeight() >= FRAME_MIN_PIXELS) return size;
        }
        return sizes[sizes.length - 1];
    }

    /** Runs on the frame thread for every camera frame. */
    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireNextImage();
        if (image == null) return;
        try {
            FrameListener listener = frameListener;
            if (listener == null) return;

            Image.Plane[] planes = image.getPlanes();
            int width = image.getWidth();
            int height = image.getHeight();
            double meanY = planeMean(planes[0], width, height);
            double meanU = planeMean(planes[1], width / 2, height / 2);
            double meanV = planeMean(planes[2], width / 2, height / 2);

            // YUV to RGB (BT.601 full range, as used by the camera). The conversion is linear, so
            // the mean RGB follows from the mean YUV - no need to convert every pixel.
            float meanRed = (float) (meanY + 1.402 * (meanV - 128));
            float meanGreen = (float) (meanY - 0.344136 * (meanU - 128) - 0.714136 * (meanV - 128));
            float meanBlue = (float) (meanY + 1.772 * (meanU - 128));

            listener.onFrame(image.getTimestamp(), meanRed, meanGreen, meanBlue);
        } catch (IllegalStateException e) {
            // the camera was stopped while this frame was being read
            Log.d("camera", "frame dropped: " + e.getMessage());
        } finally {
            image.close();
        }
    }

    private static double planeMean(Image.Plane plane, int width, int height) {
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        long sum = 0;
        for (int row = 0; row < height; row++) {
            int rowStart = row * rowStride;
            for (int column = 0; column < width; column++) {
                sum += buffer.get(rowStart + column * pixelStride) & 0xff;
            }
        }
        return (double) sum / ((long) width * height);
    }

    void stop() {
        frameListener = null;
        try {
            if (cameraDevice != null) cameraDevice.close();
        } catch (Exception e) {
            Log.println(Log.ERROR, "camera", "cannot close camera device" + e.getMessage());
        }
        cameraDevice = null;
        previewSession = null;
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (frameThread != null) {
            frameThread.quitSafely();
            frameThread = null;
        }
    }
}
