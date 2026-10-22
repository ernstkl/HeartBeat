package eu.berdosi.app.heartbeat;

import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.TextureView;

import java.text.SimpleDateFormat;
import java.util.Date;
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
    // after locking the exposure, frames already in the camera pipeline may still change
    private final long exposureLockSettleMs = 500;
    // length of the measured signal
    private final long measuringMs = 11500;
    // give up if no finger is detected within this time
    private final long maxWaitMs = 60000;
    // live estimate during the measurement: from this much data on, then once per interval.
    // On the recordings, 3 s of data are within about 2 bpm of the final result.
    private final long liveEstimateStartMs = 3000;
    private final long liveEstimateIntervalMs = 1000;

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
    private enum State { WAITING_FOR_FINGER, SETTLING, LOCKING_EXPOSURE, MEASURING, FINISHED }
    private State state = null;
    private long cameraStartNs = 0;
    private long fingerSinceNs = 0;
    private long lockingSinceNs = 0;
    private long measuringSinceNs = 0;
    private long liveEstimateNs = 0;
    private float[] colorAtStart;
    private String exposureAtStart;
    private int frameCount = 0;

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

    static boolean isFingerOnCamera(float meanRed, float meanGreen) {
        return meanRed >= FINGER_MIN_RED && meanGreen <= FINGER_MAX_GREEN_TO_RED * meanRed;
    }

    void measurePulse(CameraService cameraService) {

        // wait for a finger on the camera, then record the amount of red of every camera frame
        // and estimate the pulse.

        this.cameraService = cameraService;
        store = new MeasureStore();

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
    private synchronized void processFrame(long timeNs, float meanRed, float meanGreen, float meanBlue) {
        if (state == State.FINISHED) return;
        if (state == null) {
            state = State.WAITING_FOR_FINGER;
            cameraStartNs = timeNs;
            sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, activity.getString(R.string.status_place_finger));
        }

        boolean fingerOnCamera = isFingerOnCamera(meanRed, meanGreen);

        if (frameCount++ % 10 == 0) {
            Log.d("finger", String.format(Locale.ROOT, "state %s, mean red %.0f, green %.0f, blue %.0f, finger %b, exposure %s",
                    state, meanRed, meanGreen, meanBlue, fingerOnCamera, cameraService.describeExposure()));
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
            sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, activity.getString(R.string.status_hold_still));
        }

        if (state == State.SETTLING) {
            if ((timeNs - fingerSinceNs) / 1_000_000 < fingerSettleMs
                    || (timeNs - cameraStartNs) / 1_000_000 < cameraWarmUpMs) {
                return;
            }
            // the exposure has adapted to the finger: keep it fixed from now on, so that the
            // camera does not readjust the brightness during the measurement
            cameraService.setExposureLocked(true);
            state = State.LOCKING_EXPOSURE;
            lockingSinceNs = timeNs;
            return;
        }

        if (state == State.LOCKING_EXPOSURE) {
            if ((timeNs - lockingSinceNs) / 1_000_000 < exposureLockSettleMs) return;
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
            mainHandler.post(() -> finishMeasurement(finishedStore, finishedColor, exposure));
        }
    }

    /** The pulse from the data so far, rounded - it still changes by a beat or two. */
    private void showLiveEstimate(CopyOnWriteArrayList<Measurement<Float>> values, long measuredMs) {
        long startNs = System.nanoTime();
        PulseEstimator.Result pulse = PulseEstimator.estimate(values);
        Log.d("finger", String.format(Locale.ROOT, "live estimate %s after %d ms, took %.1f ms",
                pulse == null ? "-" : String.format(Locale.ROOT, "%.1f bpm, quality %.2f", pulse.bpm, pulse.peakPower),
                measuredMs, (System.nanoTime() - startNs) / 1e6));
        String status = (pulse != null && pulse.isReliable())
                ? String.format(Locale.getDefault(), activity.getString(R.string.status_live_estimate),
                        pulse.bpm, measuredMs / 1000f, measuringMs / 1000f)
                : String.format(Locale.getDefault(), activity.getString(R.string.status_no_clear_pulse),
                        measuredMs / 1000f, measuringMs / 1000f);
        sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, status);
    }

    /** Runs on the main thread once the signal has been recorded. */
    private void finishMeasurement(MeasureStore store, float[] colorAtStart, String exposure) {
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
                pulse.durationSeconds,
                100 * pulse.peakPower);

        if (!pulse.isReliable()) {
            currentValue = currentValue
                    + activity.getString(R.string.row_separator)
                    + activity.getString(R.string.low_signal_quality_warning);
        }

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
