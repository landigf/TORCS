package scr.ai;

import java.io.Serializable;

public class DataPoint implements Serializable {
    private static final long serialVersionUID = 1L;
    public final double[] features;   // 21 valori normalizzati
    public final double[] action;     // [steering, accel, brake]

    public DataPoint(double[] features, double[] action) {
        this.features = features;
        this.action   = action;
    }
}