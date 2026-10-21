package eu.berdosi.app.heartbeat;

import java.util.List;

/**
 * Estimates the pulse from unevenly sampled measurements, using the Lomb-Scargle periodogram.
 *
 * The periodogram is evaluated directly on the measurement timestamps (no interpolation), on a
 * fine grid of heart rates, and the pulse is the heart rate with the highest power.
 *
 * Plain Java on purpose (no Android classes), so that it can be unit tested on the desktop.
 * Reference implementation: algorithmus-entwicklung/load_and_analyze.py (scipy.signal.lombscargle
 * with precenter=True).
 */
class PulseEstimator {
    static final double MIN_BPM = 30;
    static final double MAX_BPM = 180;
    static final double STEP_BPM = 0.2;

    /**
     * Below this normalized peak power the estimate is not trusted. Good measurements so far had
     * 0.38 - 0.77, one with the finger misplaced at the start 0.05.
     */
    static final double MIN_RELIABLE_PEAK_POWER = 0.2;

    /** Fewer samples than this are not considered a usable measurement. */
    static final int MIN_SAMPLES = 20;

    static class Result {
        /** Estimated heart rate in beats per minute. */
        final double bpm;
        /**
         * Normalized periodogram value at the peak, between 0 and 1: the fraction of the signal's
         * variance explained by a sine at the estimated heart rate. Higher means a clearer pulse.
         */
        final double peakPower;
        /** Time span covered by the measurements, in seconds. */
        final double durationSeconds;

        Result(double bpm, double peakPower, double durationSeconds) {
            this.bpm = bpm;
            this.peakPower = peakPower;
            this.durationSeconds = durationSeconds;
        }

        /** Whether the pulse stands out clearly enough from the rest of the signal. */
        boolean isReliable() {
            return peakPower >= MIN_RELIABLE_PEAK_POWER;
        }
    }

    /** Convenience overload for the values kept by {@link MeasureStore}. */
    static Result estimate(List<Measurement<Float>> measurements) {
        long[] timestampsMs = new long[measurements.size()];
        float[] values = new float[measurements.size()];
        for (int i = 0; i < measurements.size(); i++) {
            timestampsMs[i] = measurements.get(i).timestamp.getTime();
            values[i] = measurements.get(i).measurement;
        }
        return estimate(timestampsMs, values);
    }

    /**
     * @return the estimate, or null if the signal is unusable (too few samples, or constant -
     * which is what a camera delivering a static image produces).
     */
    static Result estimate(long[] timestampsMs, float[] values) {
        final int n = values.length;
        if (n < MIN_SAMPLES) return null;

        // timestamps relative to the first one, in seconds; values with the mean subtracted
        double[] t = new double[n];
        double[] y = new double[n];
        double mean = 0;
        for (int i = 0; i < n; i++) mean += values[i];
        mean /= n;
        double sumOfSquares = 0;
        for (int i = 0; i < n; i++) {
            t[i] = (timestampsMs[i] - timestampsMs[0]) / 1000.0;
            y[i] = values[i] - mean;
            sumOfSquares += y[i] * y[i];
        }
        // also catches NaN, which MeasureStore produces for a constant signal (maximum == minimum)
        if (!(sumOfSquares > 0)) return null;

        final int frequencyCount = (int) Math.round((MAX_BPM - MIN_BPM) / STEP_BPM) + 1;
        double bestBpm = MIN_BPM;
        double bestPower = -1;
        for (int k = 0; k < frequencyCount; k++) {
            double bpm = MIN_BPM + k * STEP_BPM;
            double power = lombScargle(t, y, 2 * Math.PI * bpm / 60.0);
            if (power > bestPower) {
                bestPower = power;
                bestBpm = bpm;
            }
        }

        return new Result(bestBpm, 2 * bestPower / sumOfSquares, t[n - 1]);
    }

    /**
     * Lomb-Scargle periodogram at a single angular frequency, same formula as scipy's
     * _lombscargle (https://github.com/scipy/scipy/blob/main/scipy/signal/_lombscargle.pyx).
     *
     * @param t time in seconds
     * @param y values with the mean subtracted
     * @param w angular frequency in rad / s
     */
    static double lombScargle(double[] t, double[] y, double w) {
        double xc = 0, xs = 0, cc = 0, ss = 0, cs = 0;
        for (int i = 0; i < t.length; i++) {
            double c = Math.cos(w * t[i]);
            double s = Math.sin(w * t[i]);
            xc += y[i] * c;
            xs += y[i] * s;
            cc += c * c;
            ss += s * s;
            cs += c * s;
        }

        double tau = Math.atan2(2 * cs, cc - ss) / (2 * w);
        double cTau = Math.cos(w * tau);
        double sTau = Math.sin(w * tau);
        double cTau2 = cTau * cTau;
        double sTau2 = sTau * sTau;
        double csTau = 2 * cTau * sTau;

        double numeratorCos = cTau * xc + sTau * xs;
        double numeratorSin = cTau * xs - sTau * xc;
        return 0.5 * (numeratorCos * numeratorCos / (cTau2 * cc + csTau * cs + sTau2 * ss)
                + numeratorSin * numeratorSin / (cTau2 * ss - csTau * cs + sTau2 * cc));
    }
}
