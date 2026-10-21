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
     * Measurement exported from the app on 2024-05-07 (timestamp in ms, standardized value).
     * Reference values from scipy.signal.lombscargle with the mean subtracted:
     * 57.8 bpm, normalized peak power 0.6091. (The app's valley counting said 58.3.)
     */
    @Test
    public void recordedMeasurementMatchesPython() throws Exception {
        List<Long> timestamps = new ArrayList<>();
        List<Float> values = new ArrayList<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("log-2024-05-07.csv")) {
            assertNotNull("test resource missing", in);
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

        PulseEstimator.Result result = PulseEstimator.estimate(t, y);

        assertNotNull(result);
        assertEquals(57.8, result.bpm, 1e-6);
        assertEquals(0.6091, result.peakPower, 1e-3);
        assertEquals(10.9, result.durationSeconds, 1e-6);
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
