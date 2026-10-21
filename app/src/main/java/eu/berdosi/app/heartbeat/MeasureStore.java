package eu.berdosi.app.heartbeat;

import java.util.Date;
import java.util.concurrent.CopyOnWriteArrayList;

class MeasureStore {
    private final CopyOnWriteArrayList<Measurement<Float>> measurements = new CopyOnWriteArrayList<>();
    private float minimum = Float.MAX_VALUE;
    private float maximum = -Float.MAX_VALUE;

    void add(float measurement, Date timestamp) {
        Measurement<Float> measurementWithDate = new Measurement<>(timestamp, measurement);

        measurements.add(measurementWithDate);
        if (measurement < minimum) minimum = measurement;
        if (measurement > maximum) maximum = measurement;
    }

    CopyOnWriteArrayList<Measurement<Float>> getStdValues() {
        CopyOnWriteArrayList<Measurement<Float>> stdValues = new CopyOnWriteArrayList<>();

        // values scaled to 0..1, no smoothing: PulseEstimator works on the raw signal
        for (Measurement<Float> measurement : measurements) {
            Measurement<Float> stdValue =
                    new Measurement<>(
                            measurement.timestamp,
                            (measurement.measurement - minimum) / (maximum - minimum));
            stdValues.add(stdValue);
        }

        return stdValues;
    }

    @SuppressWarnings("SameParameterValue") // this parameter can be set at OutputAnalyzer
    CopyOnWriteArrayList<Measurement<Float>> getLastStdValues(int count) {
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
