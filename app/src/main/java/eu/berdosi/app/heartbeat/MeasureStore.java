package eu.berdosi.app.heartbeat;

import java.util.Date;
import java.util.concurrent.CopyOnWriteArrayList;

class MeasureStore {
    private final CopyOnWriteArrayList<Measurement<Integer>> measurements = new CopyOnWriteArrayList<>();
    private int minimum = 2147483647;
    private int maximum = -2147483648;

    void add(int measurement, Date timestamp) {
        Measurement<Integer> measurementWithDate = new Measurement<>(timestamp, measurement);

        measurements.add(measurementWithDate);
        if (measurement < minimum) minimum = measurement;
        if (measurement > maximum) maximum = measurement;
    }

    CopyOnWriteArrayList<Measurement<Float>> getStdValues() {
        CopyOnWriteArrayList<Measurement<Float>> stdValues = new CopyOnWriteArrayList<>();

        // values scaled to 0..1, no smoothing: PulseEstimator works on the raw signal
        for (Measurement<Integer> measurement : measurements) {
            Measurement<Float> stdValue =
                    new Measurement<>(
                            measurement.timestamp,
                            // subtract as int: a float cannot hold the large red sums exactly
                            (float) (measurement.measurement - minimum) / (maximum - minimum));
            stdValues.add(stdValue);
        }

        return stdValues;
    }

    @SuppressWarnings("SameParameterValue") // this parameter can be set at OutputAnalyzer
    CopyOnWriteArrayList<Measurement<Integer>> getLastStdValues(int count) {
        if (count < measurements.size()) {
            return  new CopyOnWriteArrayList<>(measurements.subList(measurements.size() - 1 - count, measurements.size() - 1));
        } else {
            return measurements;
        }
    }

    Date getLastTimestamp() {
        return measurements.get(measurements.size() - 1).timestamp;
    }
}
