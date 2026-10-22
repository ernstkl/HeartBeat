package eu.berdosi.app.heartbeat;

import android.hardware.camera2.CaptureResult;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.TextureView;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

class OutputAnalyzer {
    private final MainActivity activity;

    private final ChartDrawer chartDrawer;

    private MeasureStore store;

    // skip the first frames after starting the camera, which are broken by exposure metering
    private final long cameraWarmUpMs = 3500;
    // the finger has to be on the camera this long before measuring, until the image has settled
    private final long fingerSettleMs = 1500;
    // the auto exposure has to have settled before it is locked; phones that never report it
    // settled are not waited for longer than this
    private final long maxExposureConvergeWaitMs = 3000;
    // locking takes a few frames; again a limit for phones that do not report it
    private final long maxExposureLockWaitMs = 1500;
    // once the camera reports the exposure locked, frames already in the pipeline may still change
    private final long exposureLockSettleMs = 500;
    // length of the measured signal
    private final long measuringMs = 11500;
    // give up if no finger is detected within this time
    private final long maxWaitMs = 60000;
    // live estimate during the measurement: from this much data on, then once per interval.
    // On the recordings, 3 s of data are within about 2 bpm of the final result.
    private final long liveEstimateStartMs = 3000;
    private final long liveEstimateIntervalMs = 1000;
    // with several cameras: each one is looked at this long, after a pause for the exposure to adapt
    private final long probeSettleMs = 1000;
    private final long probeMs = 1000;

    /*
     * A finger on the camera, lit by the flash, gives a bright red image with little green.
     * Mean channel values are 0..255 (computed from the YUV means, so they can leave that range).
     * Checked on a Nokia X30, see the "finger" log and TODO.md.
     */
    private static final float FINGER_MIN_RED = 100;
    private static final float FINGER_MAX_GREEN_TO_RED = 0.5f;

    private CameraService cameraService;

    private long firstFrameTimestampNs = 0;
    private long firstFrameWallClockMs = 0;

    // state of the measurement, changed on the camera frame thread (and by timeout / stop)
    private enum State { CHOOSING_CAMERA, WAITING_FOR_FINGER, SETTLING, LOCKING_EXPOSURE, MEASURING, FINISHED }
    private State state = null;
    private long cameraStartNs = 0;
    private long fingerSinceNs = 0;
    private long settledSinceNs = 0;
    private long lockingSinceNs = 0;
    private long lockedSinceNs = 0;
    private long measuringSinceNs = 0;
    private long liveEstimateNs = 0;
    private float[] colorAtStart;
    private String exposureAtStart;
    private int frameCount = 0;

    /*
     * Phones with several back cameras: while choosing, the cameras are opened in turn and the
     * one with the clearest finger image is kept. Frames still arriving from the previous camera
     * after a switch are ignored.
     */
    private List<String> cameraIds;
    private String activeCameraId;
    private int probedCamera;
    private long probeStartNs;
    private int probeFrames;
    private int probeFingerFrames;
    private double probeGreenToRedSum;
    // finger score per camera, NaN = not probed since the finger was first seen
    private float[] fingerScores;
    private int probedSinceFinger;
    private String fingerScoreSummary = "-";

    private final Handler mainHandler;

    OutputAnalyzer(MainActivity activity, TextureView graphTextureView, Handler mainHandler) {
        this.activity = activity;
        this.chartDrawer = new ChartDrawer(graphTextureView);
        this.mainHandler = mainHandler;
    }

    /**
     * Wall clock time of a camera frame. The camera timestamp is exact relative to other frames,
     * but its time base varies between devices, so it is anchored to the wall clock at the first frame.
     */
    private Date frameTimestamp(long frameTimestampNs) {
        if (firstFrameTimestampNs == 0) {
            firstFrameTimestampNs = frameTimestampNs;
            firstFrameWallClockMs = System.currentTimeMillis();
        }
        return new Date(firstFrameWallClockMs + (frameTimestampNs - firstFrameTimestampNs) / 1_000_000);
    }

    /** Whether auto exposure has finished adjusting; also true if the camera does not report it. */
    private static boolean isExposureConverged(int aeState) {
        return aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
                || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                || aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                || aeState == -1;
    }

    static boolean isFingerOnCamera(float meanRed, float meanGreen) {
        return meanRed >= FINGER_MIN_RED && meanGreen <= FINGER_MAX_GREEN_TO_RED * meanRed;
    }

    void measurePulse(CameraService cameraService) {

        // wait for a finger on the camera, then record the amount of red of every camera frame
        // and estimate the pulse.

        this.cameraService = cameraService;
        store = new MeasureStore();

        cameraIds = cameraService.getCandidateCameraIds();
        activeCameraId = cameraService.getCameraId();
        fingerScores = new float[cameraIds.size()];
        Arrays.fill(fingerScores, Float.NaN);
        if (cameraIds.size() > 1) {
            state = State.CHOOSING_CAMERA;
            probedCamera = Math.max(0, cameraIds.indexOf(activeCameraId));
            sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, activity.getString(R.string.status_place_finger));
        }

        cameraService.setFrameListener(this::processFrame);
        mainHandler.postDelayed(timeout, maxWaitMs + measuringMs);

        activity.setViewState(MainActivity.VIEW_STATE.MEASUREMENT);
    }

    private final Runnable timeout = this::onTimeout;

    /** Only runs if the measurement could not complete in time. */
    private void onTimeout() {
        synchronized (this) {
            if (state == State.FINISHED) return;
            state = State.FINISHED;
        }
        sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, activity.getString(R.string.status_no_finger));
        sendMessage(MainActivity.MESSAGE_UPDATE_FINAL, activity.getString(R.string.status_no_finger));
        cameraService.stop();
    }

    /** Runs on the camera frame thread for every camera frame. */
    private synchronized void processFrame(String cameraId, long timeNs, float meanRed, float meanGreen, float meanBlue) {
        if (state == State.FINISHED) return;
        if (!cameraId.equals(activeCameraId)) return;
        if (cameraStartNs == 0) cameraStartNs = timeNs;
        if (state == null) {
            state = State.WAITING_FOR_FINGER;
            sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, activity.getString(R.string.status_place_finger));
        }

        boolean fingerOnCamera = isFingerOnCamera(meanRed, meanGreen);

        if (frameCount++ % 10 == 0) {
            Log.d("finger", String.format(Locale.ROOT, "camera %s, state %s, mean red %.0f, green %.0f, blue %.0f, finger %b, exposure %s",
                    cameraId, state, meanRed, meanGreen, meanBlue, fingerOnCamera, cameraService.describeExposure()));
        }

        if (state == State.CHOOSING_CAMERA) {
            probeCamera(timeNs, meanRed, meanGreen, fingerOnCamera);
            return;
        }

        if (!fingerOnCamera) {
            if (state != State.WAITING_FOR_FINGER) {
                // finger removed or moved: start over, letting the camera adjust its exposure again
                cameraService.setExposureLocked(false);
                state = State.WAITING_FOR_FINGER;
                store = new MeasureStore();
                sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, activity.getString(R.string.status_place_finger));
            }
            return;
        }

        if (state == State.WAITING_FOR_FINGER) {
            state = State.SETTLING;
            fingerSinceNs = timeNs;
            settledSinceNs = 0;
            sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, activity.getString(R.string.status_hold_still));
        }

        if (state == State.SETTLING) {
            if ((timeNs - fingerSinceNs) / 1_000_000 < fingerSettleMs
                    || (timeNs - cameraStartNs) / 1_000_000 < cameraWarmUpMs) {
                return;
            }
            // Wait until the exposure has adapted to the finger. Locking it while it is still
            // changing gave a brightness ramp at the start of the signal (recording of 05:38).
            if (settledSinceNs == 0) settledSinceNs = timeNs;
            boolean converged = isExposureConverged(cameraService.getAeState());
            if (!converged && (timeNs - settledSinceNs) / 1_000_000 < maxExposureConvergeWaitMs) return;
            Log.d("finger", String.format(Locale.ROOT, "exposure %s after %d ms: %s",
                    converged ? "converged" : "did not converge", (timeNs - settledSinceNs) / 1_000_000,
                    cameraService.describeExposure()));
            // keep it fixed from now on, so that the camera does not readjust the brightness
            // during the measurement
            cameraService.setExposureLocked(true);
            state = State.LOCKING_EXPOSURE;
            lockingSinceNs = timeNs;
            lockedSinceNs = 0;
            return;
        }

        if (state == State.LOCKING_EXPOSURE) {
            if (lockedSinceNs == 0) {
                boolean locked = !cameraService.isExposureLockAvailable()
                        || cameraService.getAeState() == CaptureResult.CONTROL_AE_STATE_LOCKED
                        || cameraService.getAeState() == -1;
                if (!locked && (timeNs - lockingSinceNs) / 1_000_000 < maxExposureLockWaitMs) return;
                Log.d("finger", String.format(Locale.ROOT, "exposure %s after %d ms: %s",
                        locked ? "locked" : "not reported locked", (timeNs - lockingSinceNs) / 1_000_000,
                        cameraService.describeExposure()));
                lockedSinceNs = timeNs;
            }
            if ((timeNs - lockedSinceNs) / 1_000_000 < exposureLockSettleMs) return;
            state = State.MEASURING;
            measuringSinceNs = timeNs;
            liveEstimateNs = 0;
            colorAtStart = new float[]{meanRed, meanGreen, meanBlue};
            exposureAtStart = cameraService.describeExposure();
            sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, activity.getString(R.string.status_measuring));
        }

        store.add(meanRed, frameTimestamp(timeNs));

        // the chart can be drawn from this thread, too
        CopyOnWriteArrayList<Measurement<Float>> chartValues = store.getStdValues();
        if (chartValues.size() > 1) {
            chartDrawer.draw(chartValues);
        }

        long measuredMs = (timeNs - measuringSinceNs) / 1_000_000;
        if (measuredMs >= liveEstimateStartMs && measuredMs < measuringMs
                && (timeNs - liveEstimateNs) / 1_000_000 >= liveEstimateIntervalMs) {
            liveEstimateNs = timeNs;
            showLiveEstimate(chartValues, measuredMs);
        }

        if (measuredMs >= measuringMs) {
            state = State.FINISHED;
            MeasureStore finishedStore = store;
            float[] finishedColor = colorAtStart;
            String exposure = String.format(
                    Locale.getDefault(),
                    activity.getString(R.string.exposure_template),
                    exposureAtStart,
                    cameraService.describeExposure());
            String camera = String.format(
                    Locale.getDefault(),
                    activity.getString(R.string.camera_template),
                    cameraId,
                    fingerScoreSummary);
            mainHandler.post(() -> finishMeasurement(finishedStore, finishedColor, exposure, camera));
        }
    }

    /**
     * The pulse from the data so far, rounded - it still changes by a beat or two. Whether it
     * settles is the user's indication of a good measurement.
     */
    private void showLiveEstimate(CopyOnWriteArrayList<Measurement<Float>> values, long measuredMs) {
        long startNs = System.nanoTime();
        PulseEstimator.Result pulse = PulseEstimator.estimate(values);
        Log.d("finger", String.format(Locale.ROOT, "live estimate %s after %d ms, took %.1f ms",
                pulse == null ? "-" : String.format(Locale.ROOT, "%.1f bpm, peak power %.2f", pulse.bpm, pulse.peakPower),
                measuredMs, (System.nanoTime() - startNs) / 1e6));
        if (pulse == null) return;
        sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, String.format(
                Locale.getDefault(),
                activity.getString(R.string.status_live_estimate),
                pulse.bpm, measuredMs / 1000f, measuringMs / 1000f));
    }

    /**
     * Looks at the current camera for a while, then moves on to the next one. Once all cameras
     * have been looked at since the finger was first seen, the one with the best finger score is
     * kept and the measurement starts as with a single camera.
     */
    private void probeCamera(long timeNs, float meanRed, float meanGreen, boolean fingerOnCamera) {
        if (probeStartNs == 0) probeStartNs = timeNs;
        long probingMs = (timeNs - probeStartNs) / 1_000_000;
        if (probingMs < probeSettleMs) return;
        probeFrames++;
        if (fingerOnCamera) {
            probeFingerFrames++;
            probeGreenToRedSum += meanGreen / meanRed;
        }
        if (probingMs < probeSettleMs + probeMs) return;

        // A finger on most frames, and then: the less green, the less ambient light gets past the
        // finger, the clearer the finger image. 1 = no green at all.
        float score = (probeFingerFrames * 2 > probeFrames)
                ? (float) (1 - probeGreenToRedSum / probeFingerFrames)
                : 0;
        Log.d("finger", String.format(Locale.ROOT, "camera %s: finger on %d of %d frames, score %.3f, exposure %s",
                activeCameraId, probeFingerFrames, probeFrames, score, cameraService.describeExposure()));

        if (score > 0 && probedSinceFinger == 0) {
            // finger seen for the first time: from now on, every camera gets a score
            Arrays.fill(fingerScores, Float.NaN);
            sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, activity.getString(R.string.status_choosing_camera));
        }
        if (score > 0 || probedSinceFinger > 0) {
            fingerScores[probedCamera] = score;
            probedSinceFinger++;
        }

        int nextCamera = (probedCamera + 1) % cameraIds.size();
        if (probedSinceFinger == cameraIds.size()) {
            int best = 0;
            for (int i = 1; i < fingerScores.length; i++) {
                if (fingerScores[i] > fingerScores[best]) best = i;
            }
            StringBuilder summary = new StringBuilder();
            for (int i = 0; i < fingerScores.length; i++) {
                if (i > 0) summary.append(", ");
                summary.append(String.format(Locale.ROOT, "%s: %.3f", cameraIds.get(i), fingerScores[i]));
            }
            fingerScoreSummary = summary.toString();
            Log.d("finger", "finger scores " + fingerScoreSummary);

            if (fingerScores[best] > 0) {
                state = State.WAITING_FOR_FINGER;
                sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, activity.getString(R.string.status_hold_still));
                if (best != probedCamera) switchCamera(best);
                return;
            }
            // finger gone again: start over
            probedSinceFinger = 0;
            sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, activity.getString(R.string.status_place_finger));
        }
        switchCamera(nextCamera);
    }

    private void switchCamera(int index) {
        probedCamera = index;
        activeCameraId = cameraIds.get(index);
        cameraStartNs = 0;
        probeStartNs = 0;
        probeFrames = 0;
        probeFingerFrames = 0;
        probeGreenToRedSum = 0;
        String cameraId = activeCameraId;
        mainHandler.post(() -> {
            synchronized (this) {
                if (state == State.FINISHED) return;
            }
            cameraService.switchCamera(cameraId);
        });
    }

    /** Runs on the main thread once the signal has been recorded. */
    private void finishMeasurement(MeasureStore store, float[] colorAtStart, String exposure, String camera) {
        mainHandler.removeCallbacks(timeout);

        CopyOnWriteArrayList<Measurement<Float>> stdValues = store.getStdValues();

        PulseEstimator.Result pulse = PulseEstimator.estimate(stdValues);

        // If the camera only provided a static image, the signal is constant and no pulse
        // can be estimated. A camera not available error is shown, which is the most likely cause.
        if (pulse == null) {
            mainHandler.sendMessage(Message.obtain(
                    mainHandler,
                    MainActivity.MESSAGE_CAMERA_NOT_AVAILABLE,
                    "No usable signal - there may be an issue when accessing the camera."));
            return;
        }

        String currentValue = String.format(
                Locale.getDefault(),
                activity.getString(R.string.pulse_output_template),
                pulse.bpm,
                pulse.durationSeconds);
        Log.d("finger", String.format(Locale.ROOT, "result %.1f bpm, peak power %.2f", pulse.bpm, pulse.peakPower));

        sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, currentValue);

        StringBuilder returnValueSb = new StringBuilder();
        returnValueSb.append(currentValue);
        returnValueSb.append(activity.getString(R.string.row_separator));

        returnValueSb.append(String.format(
                Locale.getDefault(),
                activity.getString(R.string.color_at_start_template),
                colorAtStart[0], colorAtStart[1], colorAtStart[2]));
        returnValueSb.append(activity.getString(R.string.row_separator));
        returnValueSb.append(exposure);
        returnValueSb.append(activity.getString(R.string.row_separator));
        returnValueSb.append(camera);
        returnValueSb.append(activity.getString(R.string.row_separator));

        returnValueSb.append(activity.getString(R.string.raw_values));
        returnValueSb.append(activity.getString(R.string.row_separator));

        for (int stdValueIdx = 0; stdValueIdx < stdValues.size(); stdValueIdx++) {
            // stdValues.forEach((value) -> { // would require API level 24 instead of 21.
            Measurement<Float> value = stdValues.get(stdValueIdx);
            String timeStampString =
                    new SimpleDateFormat(
                            activity.getString(R.string.dateFormatGranular),
                            Locale.getDefault()
                    ).format(value.timestamp);
            returnValueSb.append(timeStampString);
            returnValueSb.append(activity.getString(R.string.separator));
            returnValueSb.append(value.measurement);
            returnValueSb.append(activity.getString(R.string.row_separator));
        }

        sendMessage(MainActivity.MESSAGE_UPDATE_FINAL, returnValueSb.toString());

        cameraService.stop();
    }

    void stop() {
        mainHandler.removeCallbacks(timeout);
        synchronized (this) {
            state = State.FINISHED;
        }
        if (cameraService != null) cameraService.setFrameListener(null);
    }

    void sendMessage(int what, Object message) {
        Message msg = new Message();
        msg.what = what;
        msg.obj = message;
        mainHandler.sendMessage(msg);
    }
}
