package eu.berdosi.app.heartbeat;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PulseEstimatorTest {

    /**
     * Measurement exported from the app on 2024-05-07 (timestamp in ms, standardized value; this
     * old app version still smoothed the values with a 4-point rolling average).
     * Reference values from scipy.signal.lombscargle with the mean subtracted:
     * 57.8 bpm, normalized peak power 0.6091. (The app's valley counting said 58.3.)
     */
    @Test
    public void recordedMeasurementMatchesPython() throws Exception {
        PulseEstimator.Result result = estimateFromCsv("log-2024-05-07.csv");

        assertNotNull(result);
        assertEquals(57.8, result.bpm, 1e-6);
        assertEquals(0.6091, result.peakPower, 1e-3);
        assertEquals(10.9, result.durationSeconds, 1e-6);
    }

    /**
     * Export Details of 2026-10-21, first build with PulseEstimator (still smoothed values and
     * timer-based timestamps). The app showed 56.8 bpm, signal quality 77%; scipy agrees.
     * (Valley counting said 56.5.)
     */
    @Test
    public void recordedMeasurement2026MatchesPython() throws Exception {
        PulseEstimator.Result result = estimateFromCsv("log-2026-10-21-2336.csv");

        assertNotNull(result);
        assertEquals(56.8, result.bpm, 1e-6);
        assertEquals(0.766, result.peakPower, 1e-3);
        assertEquals(11.057, result.durationSeconds, 1e-6);
    }

    /**
     * 2026-10-21 23:49: first recording with camera frame timestamps and without smoothing.
     * App and scipy: 56.2 bpm, peak power 0.517. (Valley counting said 61.7.)
     */
    @Test
    public void recordedMeasurementWithFrameTimestamps() throws Exception {
        PulseEstimator.Result result = estimateFromCsv("log-2026-10-21-2349.csv");

        assertNotNull(result);
        assertEquals(56.2, result.bpm, 1e-6);
        assertEquals(0.517, result.peakPower, 1e-3);
    }

    /**
     * 2026-10-21 23:55: first recording with finger detection (measuring starts after the finger
     * has settled; color at start: red 168, green 0, blue 0). App and scipy: 54.6 bpm, 0.380.
     * (Valley counting said 55.8.)
     */
    @Test
    public void recordedMeasurementWithFingerDetection() throws Exception {
        PulseEstimator.Result result = estimateFromCsv("log-2026-10-21-2355.csv");

        assertNotNull(result);
        assertEquals(54.6, result.bpm, 1e-6);
        assertEquals(0.380, result.peakPower, 1e-3);
    }

    /**
     * 2026-10-21 23:48: finger not placed properly at the start. The jump in the first ~1.5 s
     * dominates, the estimate is wrong (scipy agrees: 32.6 bpm), but the low peak power shows it.
     * Without the first 1.5 s it would be 57.0 bpm - hence the finger detection in OutputAnalyzer.
     */
    @Test
    public void badFingerPlacementGivesLowPeakPower() throws Exception {
        PulseEstimator.Result result = estimateFromCsv("log-2026-10-21-2348.csv");

        assertNotNull(result);
        assertEquals(32.6, result.bpm, 1e-6);
        assertTrue(result.peakPower < 0.1);
    }

    /** Reads "timestamp in ms,value" lines from a test resource and runs the estimator. */
    private PulseEstimator.Result estimateFromCsv(String resourceName) throws Exception {
        List<Long> timestamps = new ArrayList<>();
        List<Float> values = new ArrayList<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            assertNotNull("test resource missing: " + resourceName, in);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(",");
                timestamps.add(Long.parseLong(fields[0]));
                values.add(Float.parseFloat(fields[1]));
            }
        }
        long[] t = new long[values.size()];
        float[] y = new float[values.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = timestamps.get(i);
            y[i] = values.get(i);
        }
        return PulseEstimator.estimate(t, y);
    }

    /** A sine with jittered sampling times (like the app's), offset and noise is found. */
    @Test
    public void syntheticSignalWithJitter() {
        final double trueBpm = 72.4;
        Random random = new Random(42);
        int n = 250;
        long[] t = new long[n];
        float[] y = new float[n];
        long now = 1_715_053_877_000L;
        for (int i = 0; i < n; i++) {
            now += 40 + random.nextInt(20); // 40..59 ms between samples
            t[i] = now;
            double phase = 2 * Math.PI * trueBpm / 60.0 * (now - t[0]) / 1000.0;
            y[i] = (float) (0.5 + 0.3 * Math.sin(phase) + 0.05 * random.nextGaussian());
        }

        PulseEstimator.Result result = PulseEstimator.estimate(t, y);

        assertNotNull(result);
        assertEquals(trueBpm, result.bpm, PulseEstimator.STEP_BPM);
        assertTrue(result.peakPower > 0.8);
    }

    /** A static camera image gives a constant signal, which MeasureStore turns into NaN. */
    @Test
    public void constantOrNaNSignalIsRejected() {
        long[] t = new long[100];
        float[] constant = new float[100];
        float[] nan = new float[100];
        for (int i = 0; i < t.length; i++) {
            t[i] = i * 45L;
            constant[i] = 0.5f;
            nan[i] = Float.NaN;
        }
        assertNull(PulseEstimator.estimate(t, constant));
        assertNull(PulseEstimator.estimate(t, nan));
    }

    @Test
    public void tooFewSamplesAreRejected() {
        assertNull(PulseEstimator.estimate(new long[]{0, 45, 90}, new float[]{0.1f, 0.9f, 0.1f}));
    }
}
