package eu.berdosi.app.heartbeat;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.TextureView;
import android.view.View;

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
    private final int measurementLength = 15000; // ensure the number of data points is the power of two
    private final int clipLength = 3500;

    private int detectedValleys = 0;
    private int ticksPassed = 0;

    private final CopyOnWriteArrayList<Long> valleys = new CopyOnWriteArrayList<>();

    private CountDownTimer timer;

    private final ExecutorService sampler = Executors.newSingleThreadExecutor();

    private long lastFrameTimestampNs = 0;
    private long firstFrameTimestampNs = 0;
    private long firstFrameWallClockMs = 0;

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
        if (frameTimestampNs == 0) {
            // timestamp not available, fall back to the time of grabbing the frame
            return new Date();
        }
        if (firstFrameTimestampNs == 0) {
            firstFrameTimestampNs = frameTimestampNs;
            firstFrameWallClockMs = System.currentTimeMillis();
        }
        return new Date(firstFrameWallClockMs + (frameTimestampNs - firstFrameTimestampNs) / 1_000_000);
    }

    void measurePulse(TextureView textureView, CameraService cameraService) {

        // 20 times a second, get the amount of red on the picture.
        // detect local minimums, calculate pulse.

        store = new MeasureStore();

        detectedValleys = 0;

        timer = new CountDownTimer(measurementLength, measurementInterval) {

            @Override
            public void onTick(long millisUntilFinished) {
                // skip the first measurements, which are broken by exposure metering
                if (clipLength > (++ticksPassed * measurementInterval)) return;

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
                Date timestamp = frameTimestamp(frameTimestampNs);

                // a single worker thread keeps the measurements in frame order
                if (sampler.isShutdown()) return;
                sampler.execute(() -> {
                    int pixelCount = currentBitmap.getWidth() * currentBitmap.getHeight();
                    int measurement = 0;
                    int[] pixels = new int[pixelCount];

                    currentBitmap.getPixels(pixels, 0, currentBitmap.getWidth(), 0, 0, currentBitmap.getWidth(), currentBitmap.getHeight());

                    // extract the red component
                    // https://developer.android.com/reference/android/graphics/Color.html#decoding
                    for (int pixelIndex = 0; pixelIndex < pixelCount; pixelIndex++) {
                        measurement += (pixels[pixelIndex] >> 16) & 0xff;
                    }
                    // max int is 2^31 (2147483647) , so width and height can be at most 2^11,
                    // as 2^8 * 2^11 * 2^11 = 2^30, just below the limit

                    store.add(measurement, timestamp);

                    if (detectValley()) {
                        detectedValleys = detectedValleys + 1;
                        valleys.add(store.getLastTimestamp().getTime());
                        // in 13 seconds (13000 milliseconds), I expect 15 valleys. that would be a pulse of 15 / 130000 * 60 * 1000 = 69

                        String currentValue = String.format(
                                Locale.getDefault(),
                                activity.getResources().getQuantityString(R.plurals.measurement_output_template, detectedValleys),
                                (valleys.size() == 1)
                                        ? (60f * (detectedValleys) / (Math.max(1, (measurementLength - millisUntilFinished - clipLength) / 1000f)))
                                        : (60f * (detectedValleys - 1) / (Math.max(1, (valleys.get(valleys.size() - 1) - valleys.get(0)) / 1000f))),
                                detectedValleys,
                                1f * (measurementLength - millisUntilFinished - clipLength) / 1000f);

                        sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, currentValue);
                    }

                    // draw the chart on a separate thread.
                    Thread chartDrawerThread = new Thread(() -> chartDrawer.draw(store.getStdValues()));
                    chartDrawerThread.start();
                });
            }

            @Override
            public void onFinish() {
                // frames already grabbed are still summed up; nothing new is accepted
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

                // look for "drops" of 0.15 - 0.75 in the value
                // a drop may take 2-3 ticks.
                // int dropCount = 0;
                // for (int stdValueIdx = 4; stdValueIdx < stdValues.size(); stdValueIdx++) {
                //     if (((stdValues.get(stdValueIdx - 2).measurement - stdValues.get(stdValueIdx).measurement) > dropHeight) &&
                //             !((stdValues.get(stdValueIdx - 3).measurement - stdValues.get(stdValueIdx - 1).measurement) > dropHeight) &&
                //            !((stdValues.get(stdValueIdx - 4).measurement - stdValues.get(stdValueIdx - 2).measurement) > dropHeight)
                //    ) {
                //        dropCount++;
                //    }
                // }

                // returnValueSb.append(activity.getString(R.string.detected_pulse));
                // returnValueSb.append(activity.getString(R.string.separator));
                // returnValueSb.append((float) dropCount / ((float) (measurementLength - clipLength) / 1000f / 60f));
                // returnValueSb.append(activity.getString(R.string.row_separator));

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
        };

        activity.setViewState(MainActivity.VIEW_STATE.MEASUREMENT);
        timer.start();
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
