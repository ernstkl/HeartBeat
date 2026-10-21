package eu.berdosi.app.heartbeat;

import java.util.List;

/**
 * Estimates the pulse from unevenly sampled measurements, using the Lomb-Scargle periodogram.
 *
 * A quadratic trend is removed first: the slow drift caused by the finger (pressure, blood flow,
 * warming) would otherwise take part of the signal's variance. The periodogram is then evaluated
 * directly on the measurement timestamps (no interpolation), on a fine grid of heart rates, and
 * the pulse is the heart rate with the highest power.
 *
 * Plain Java on purpose (no Android classes), so that it can be unit tested on the desktop.
 * Reference implementation: algorithmus-entwicklung/load_and_analyze.py (numpy.polyfit of degree 2,
 * then scipy.signal.lombscargle).
 */
class PulseEstimator {
    static final double MIN_BPM = 30;
    static final double MAX_BPM = 180;
    static final double STEP_BPM = 0.2;

    /**
     * Below this normalized peak power the estimate is not trusted. Calibrated on the recordings
     * of 2026-10-21/22 (after detrending): good measurements 0.57 - 0.81; a signal without pulse
     * (values shuffled in time) 0.03 - 0.10, as was the take with the finger misplaced (0.03).
     * 0.3 is three times the highest noise value. A real pulse wave is not a pure sine, so even
     * a perfect measurement stays below about 0.9.
     */
    static final double MIN_RELIABLE_PEAK_POWER = 0.3;

    /** Fewer samples than this are not considered a usable measurement. */
    static final int MIN_SAMPLES = 20;

    static class Result {
        /** Estimated heart rate in beats per minute. */
        final double bpm;
        /**
         * Normalized periodogram value at the peak, between 0 and 1: the fraction of the signal's
         * variance (after removing the trend) explained by a sine at the estimated heart rate.
         * Higher means a clearer pulse.
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

        // timestamps relative to the first one, in seconds
        double[] t = new double[n];
        for (int i = 0; i < n; i++) t[i] = (timestampsMs[i] - timestampsMs[0]) / 1000.0;

        double[] y = removeQuadraticTrend(t, values);
        if (y == null) return null;
        double sumOfSquares = 0;
        for (double value : y) sumOfSquares += value * value;
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
     * Residuals of a least squares fit of a + b*u + c*u^2, with u the time scaled to -1..1 (keeps
     * the normal equations well conditioned). This also removes the mean.
     *
     * @return the residuals, or null if all timestamps are equal
     */
    static double[] removeQuadraticTrend(double[] t, float[] values) {
        final int n = values.length;
        final double duration = t[n - 1] - t[0];
        if (!(duration > 0)) return null;

        double[] u = new double[n];
        // sums of u^0..u^4, and of y, u*y, u^2*y
        double s0 = n, s1 = 0, s2 = 0, s3 = 0, s4 = 0, sy = 0, suy = 0, su2y = 0;
        for (int i = 0; i < n; i++) {
            u[i] = 2 * (t[i] - t[0]) / duration - 1;
            double u2 = u[i] * u[i];
            s1 += u[i];
            s2 += u2;
            s3 += u2 * u[i];
            s4 += u2 * u2;
            sy += values[i];
            suy += u[i] * values[i];
            su2y += u2 * values[i];
        }

        // normal equations [s0 s1 s2; s1 s2 s3; s2 s3 s4] * (a, b, c) = (sy, suy, su2y), Cramer's rule
        double det = determinant(s0, s1, s2, s1, s2, s3, s2, s3, s4);
        if (det == 0) return null;
        double a = determinant(sy, s1, s2, suy, s2, s3, su2y, s3, s4) / det;
        double b = determinant(s0, sy, s2, s1, suy, s3, s2, su2y, s4) / det;
        double c = determinant(s0, s1, sy, s1, s2, suy, s2, s3, su2y) / det;

        double[] residuals = new double[n];
        for (int i = 0; i < n; i++) {
            residuals[i] = values[i] - (a + b * u[i] + c * u[i] * u[i]);
        }
        return residuals;
    }

    /** Determinant of the 3x3 matrix given row by row. */
    private static double determinant(double m11, double m12, double m13,
                                      double m21, double m22, double m23,
                                      double m31, double m32, double m33) {
        return m11 * (m22 * m33 - m23 * m32)
                - m12 * (m21 * m33 - m23 * m31)
                + m13 * (m21 * m32 - m22 * m31);
    }

    /**
     * Lomb-Scargle periodogram at a single angular frequency, same formula as scipy's
     * _lombscargle (https://github.com/scipy/scipy/blob/main/scipy/signal/_lombscargle.pyx).
     *
     * @param t time in seconds
     * @param y values with the mean (here: the trend) removed
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
