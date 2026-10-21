package eu.berdosi.app.heartbeat;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.TextureView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class OutputAnalyzer {
    private final MainActivity activity;

    private final ChartDrawer chartDrawer;

    private MeasureStore store;

    private final int measurementInterval = 45;
    // skip the first frames after starting the camera, which are broken by exposure metering
    private final long cameraWarmUpMs = 3500;
    // the finger has to be on the camera this long before measuring, until the image has settled
    private final long fingerSettleMs = 1500;
    // length of the measured signal
    private final long measuringMs = 11500;
    // give up if no finger is detected within this time
    private final long maxWaitMs = 60000;

    /*
     * A finger on the camera, lit by the flash, gives a bright red image with little green.
     * Mean channel values are 0..255. These thresholds are first guesses, see the "finger" log.
     */
    private static final float FINGER_MIN_RED = 100;
    private static final float FINGER_MAX_GREEN_TO_RED = 0.5f;

    private int detectedValleys = 0;

    private final CopyOnWriteArrayList<Long> valleys = new CopyOnWriteArrayList<>();

    private CountDownTimer timer;

    private final ExecutorService sampler = Executors.newSingleThreadExecutor();

    private long lastFrameTimestampNs = 0;
    private long firstFrameTimestampNs = 0;
    private long firstFrameWallClockMs = 0;

    // state of the measurement, only used on the sampler thread
    private enum State { WAITING_FOR_FINGER, SETTLING, MEASURING, FINISHED }
    private State state = null;
    private long cameraStartNs = 0;
    private long fingerSinceNs = 0;
    private long measuringSinceNs = 0;
    private float[] colorAtStart;
    private int frameCount = 0;

    private final Handler mainHandler;

    OutputAnalyzer(MainActivity activity, TextureView graphTextureView, Handler mainHandler) {
        this.activity = activity;
        this.chartDrawer = new ChartDrawer(graphTextureView);
        this.mainHandler = mainHandler;
    }

    private boolean detectValley() {
        final int valleyDetectionWindowSize = 13;
        CopyOnWriteArrayList<Measurement<Integer>> subList = store.getLastStdValues(valleyDetectionWindowSize);
        if (subList.size() < valleyDetectionWindowSize) {
            return false;
        } else {
            Integer referenceValue = subList.get((int) Math.ceil(valleyDetectionWindowSize / 2f)).measurement;

            for (Measurement<Integer> measurement : subList) {
                if (measurement.measurement < referenceValue) return false;
            }

            // filter out consecutive measurements due to too high measurement rate
            return (!subList.get((int) Math.ceil(valleyDetectionWindowSize / 2f)).measurement.equals(
                    subList.get((int) Math.ceil(valleyDetectionWindowSize / 2f) - 1).measurement));
        }
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

    void measurePulse(TextureView textureView, CameraService cameraService) {

        // about 20 times a second, get the amount of red on the picture.
        // wait for a finger on the camera, then record the signal and estimate the pulse.

        store = new MeasureStore();

        detectedValleys = 0;

        timer = new CountDownTimer(maxWaitMs + measuringMs, measurementInterval) {

            @Override
            public void onTick(long millisUntilFinished) {
                // Grab the frame and its capture time together, here on the UI thread, where the
                // TextureView updates its image - so the timestamp belongs to exactly this frame.
                SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
                if (surfaceTexture == null) return;
                long frameTimestampNs = surfaceTexture.getTimestamp();
                // the camera has not delivered a new frame since the last tick: don't count it twice
                if (frameTimestampNs != 0 && frameTimestampNs == lastFrameTimestampNs) return;
                lastFrameTimestampNs = frameTimestampNs;
                Bitmap currentBitmap = textureView.getBitmap();
                if (currentBitmap == null) return;
                // timestamp not available: fall back to the time of grabbing the frame
                long timeNs = frameTimestampNs != 0 ? frameTimestampNs : System.nanoTime();

                // a single worker thread keeps the measurements in frame order
                if (sampler.isShutdown()) return;
                sampler.execute(() -> processFrame(currentBitmap, timeNs, cameraService));
            }

            @Override
            public void onFinish() {
                // only reached if the measurement could not complete in time
                sampler.execute(() -> {
                    if (state == State.FINISHED) return;
                    state = State.FINISHED;
                    mainHandler.post(() -> {
                        sampler.shutdown();
                        sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, activity.getString(R.string.status_no_finger));
                        sendMessage(MainActivity.MESSAGE_UPDATE_FINAL, activity.getString(R.string.status_no_finger));
                        cameraService.stop();
                    });
                });
            }
        };

        activity.setViewState(MainActivity.VIEW_STATE.MEASUREMENT);
        timer.start();
    }

    /** Runs on the sampler thread for every new camera frame. */
    private void processFrame(Bitmap bitmap, long timeNs, CameraService cameraService) {
        if (state == State.FINISHED) return;
        if (state == null) {
            state = State.WAITING_FOR_FINGER;
            cameraStartNs = timeNs;
            sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, activity.getString(R.string.status_place_finger));
        }

        int pixelCount = bitmap.getWidth() * bitmap.getHeight();
        int red = 0, green = 0, blue = 0;
        int[] pixels = new int[pixelCount];

        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        // extract the color components
        // https://developer.android.com/reference/android/graphics/Color.html#decoding
        for (int pixelIndex = 0; pixelIndex < pixelCount; pixelIndex++) {
            red += (pixels[pixelIndex] >> 16) & 0xff;
            green += (pixels[pixelIndex] >> 8) & 0xff;
            blue += pixels[pixelIndex] & 0xff;
        }
        // max int is 2^31 (2147483647) , so width and height can be at most 2^11,
        // as 2^8 * 2^11 * 2^11 = 2^30, just below the limit

        float meanRed = (float) red / pixelCount;
        float meanGreen = (float) green / pixelCount;
        float meanBlue = (float) blue / pixelCount;
        boolean fingerOnCamera = isFingerOnCamera(meanRed, meanGreen);

        if (frameCount++ % 10 == 0) {
            Log.d("finger", String.format(Locale.ROOT, "state %s, mean red %.0f, green %.0f, blue %.0f, finger %b",
                    state, meanRed, meanGreen, meanBlue, fingerOnCamera));
        }

        if (!fingerOnCamera) {
            if (state != State.WAITING_FOR_FINGER) {
                // finger removed or moved: start over
                state = State.WAITING_FOR_FINGER;
                store = new MeasureStore();
                valleys.clear();
                detectedValleys = 0;
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
            state = State.MEASURING;
            measuringSinceNs = timeNs;
            colorAtStart = new float[]{meanRed, meanGreen, meanBlue};
            sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, activity.getString(R.string.status_measuring));
        }

        store.add(red, frameTimestamp(timeNs));
        float measuringSeconds = (timeNs - measuringSinceNs) / 1e9f;

        if (detectValley()) {
            detectedValleys = detectedValleys + 1;
            valleys.add(store.getLastTimestamp().getTime());
            // in 13 seconds (13000 milliseconds), I expect 15 valleys. that would be a pulse of 15 / 130000 * 60 * 1000 = 69

            String currentValue = String.format(
                    Locale.getDefault(),
                    activity.getResources().getQuantityString(R.plurals.measurement_output_template, detectedValleys),
                    (valleys.size() == 1)
                            ? (60f * (detectedValleys) / (Math.max(1, measuringSeconds)))
                            : (60f * (detectedValleys - 1) / (Math.max(1, (valleys.get(valleys.size() - 1) - valleys.get(0)) / 1000f))),
                    detectedValleys,
                    measuringSeconds);

            sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, currentValue);
        }

        // draw the chart on a separate thread.
        CopyOnWriteArrayList<Measurement<Float>> chartValues = store.getStdValues();
        if (chartValues.size() > 1) {
            Thread chartDrawerThread = new Thread(() -> chartDrawer.draw(chartValues));
            chartDrawerThread.start();
        }

        if ((timeNs - measuringSinceNs) / 1_000_000 >= measuringMs) {
            state = State.FINISHED;
            MeasureStore finishedStore = store;
            float[] finishedColor = colorAtStart;
            mainHandler.post(() -> finishMeasurement(finishedStore, finishedColor, cameraService));
        }
    }

    /** Runs on the main thread once the signal has been recorded. */
    private void finishMeasurement(MeasureStore store, float[] colorAtStart, CameraService cameraService) {
        timer.cancel();
        sampler.shutdown();

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

        sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, currentValue);

        StringBuilder returnValueSb = new StringBuilder();
        returnValueSb.append(currentValue);
        returnValueSb.append(activity.getString(R.string.row_separator));

        // the original valley counting result, kept for comparison
        // clip the interval to the first till the last one - on this interval, there were detectedValleys - 1 periods
        returnValueSb.append(activity.getString(R.string.valley_counting_label));
        if (valleys.size() > 0) {
            returnValueSb.append(String.format(
                    Locale.getDefault(),
                    activity.getResources().getQuantityString(R.plurals.measurement_output_template, detectedValleys - 1),
                    60f * (detectedValleys - 1) / (Math.max(1, (valleys.get(valleys.size() - 1) - valleys.get(0)) / 1000f)),
                    detectedValleys - 1,
                    1f * (valleys.get(valleys.size() - 1) - valleys.get(0)) / 1000f));
        } else {
            returnValueSb.append("-");
        }
        returnValueSb.append(activity.getString(R.string.row_separator));

        returnValueSb.append(String.format(
                Locale.getDefault(),
                activity.getString(R.string.color_at_start_template),
                colorAtStart[0], colorAtStart[1], colorAtStart[2]));
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

        returnValueSb.append(activity.getString(R.string.output_detected_peaks_header));
        returnValueSb.append(activity.getString(R.string.row_separator));

        // add detected valleys location
        for (long tick : valleys) {
            returnValueSb.append(tick);
            returnValueSb.append(activity.getString(R.string.row_separator));
        }

        sendMessage(MainActivity.MESSAGE_UPDATE_FINAL, returnValueSb.toString());

        cameraService.stop();
    }

    void stop() {
        if (timer != null) {
            timer.cancel();
        }
        sampler.shutdownNow();
    }

    void sendMessage(int what, Object message) {
        Message msg = new Message();
        msg.what = what;
        msg.obj = message;
        mainHandler.sendMessage(msg);
    }
}
