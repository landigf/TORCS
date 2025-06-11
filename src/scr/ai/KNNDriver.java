
package scr.ai;

import java.io.*;
import java.util.*;
import scr.Action;
import scr.SensorModel;
import scr.SimpleDriver;

public class KNNDriver extends SimpleDriver {
    private KDTree tree;
    private final ActionCache cache = new ActionCache();
    private final SimpleDriver fallback = new SimpleDriver(); // Rule‑based backup
    private static final long MAX_LATENCY_MS = 15;        // soglia di sicurezza
    private long totTime = 0;                             // somma dei ms
    private long frames  = 0;                             // numero di chiamate
    private long slow    = 0;                             // quante oltre soglia

    /* Costanti di cambio marcia */
    private final double[] RPM_UP   = { 7000, 7200, 7300, 7400, 7500, 0 };
    private final double[] RPM_DOWN = { 0,    2800, 3000, 3200, 3500, 0 };

    // Steering smoothing
    private double prevSteer = 0.0;
    private static final double ALPHA = 0.7;

    // Threshold for out‑of‑distribution (Euclidean) distance
    private static final double OOD_THRESHOLD = 0.4;

    // Feature configuration
    private static final String[] featureConfig = DatasetBuilder.CONFIG_WITH_SENSORS;

    public KNNDriver() {
        try (ObjectInputStream ois = new ObjectInputStream(
                 new FileInputStream("knn.tree"))) {
            tree = (KDTree) ois.readObject();
        } catch (Exception e) {
            throw new RuntimeException("Cannot load KD‑Tree", e);
        }
    }

    @Override
    public Action control(SensorModel s) {
        double[] in = extractFeatures(s);

        // Cache lookup
        double[] cached = cache.lookup(in);
        if (cached != null) {
            System.out.println("\n=== CACHE HIT ===");
            System.out.printf("Features: %s%n", Arrays.toString(in));
            System.out.println("=================\n");
            return buildAction(cached, s);
        }

        int dynK = dynamicK(s.getSpeed());
        List<DataPoint> nn = tree.nearest(in, dynK);

        // Weighted KNN
        double[] weights = new double[nn.size()];
        double weightSum = 0.0;
        for (int i = 0; i < nn.size(); i++) {
            double d = euclidean(in, nn.get(i).features);
            weights[i] = 1.0 / (d + 1e-6);
            weightSum += weights[i];
        }

        double[] a = new double[3];
        for (int i = 0; i < nn.size(); i++) {
            for (int j = 0; j < 3; j++) {
                a[j] += nn.get(i).action[j] * weights[i];
            }
        }
        for (int j = 0; j < 3; j++) {
            a[j] /= weightSum;
        }

        // Out‑of‑distribution guard
        double nearestDist = euclidean(in, nn.get(0).features);
        if (nearestDist > OOD_THRESHOLD) {
            System.out.printf("\n\n\nOOD: %.3f > %.3f, using fallback%n\n\n\n", nearestDist, OOD_THRESHOLD);
            return fallback.control(s);
        }

        // Update cache
        cache.put(in, a);
        return buildAction(a, s);
    }

    /** Dynamic choice of k based on speed. */
    private int dynamicK(double speedKmh) {
        if (speedKmh < 30) return 3;
        if (speedKmh > 120) return 7;
        return 5;
    }

    private Action buildAction(double[] a, SensorModel s) {
        Action out = new Action();

        // Clip
        double steer = Math.max(-1.0, Math.min(1.0, a[0]));
        steer = ALPHA * steer + (1 - ALPHA) * prevSteer;
        prevSteer = steer;

        double accel = Math.max(0.0, Math.min(1.0, a[1]));
        double brake = Math.max(0.0, Math.min(1.0, a[2]));

        out.steering = steer;
        out.accelerate = accel;
        out.brake = brake;
        out.gear = getGear(s);
        return out;
    }

    private static double euclidean(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    private double[] extractFeatures(SensorModel s) {
        double[] features = new double[featureConfig.length];
        for (int i = 0; i < featureConfig.length; i++) {
            String f = featureConfig[i];
            double raw;
            switch (f) {
                case "angle" -> raw = s.getAngleToTrackAxis();
                case "curLapTime" -> raw = s.getCurrentLapTime();
                case "speedX" -> raw = s.getSpeed();
                case "speedY" -> raw = s.getLateralSpeed();
                case "trackPos" -> raw = s.getTrackPosition();
                case "gear" -> raw = s.getGear();
                case "rpm" -> raw = s.getRPM();
                case "damage" -> raw = s.getDamage();
                case "lastLapTime" -> raw = s.getLastLapTime();
                default -> {
                    if (f.startsWith("track")) {
                        int idx = Integer.parseInt(f.substring(5));
                        raw = s.getTrackEdgeSensors()[idx];
                    } else if (f.startsWith("wheel")) {
                        int idx = Integer.parseInt(f.substring(5));
                        raw = s.getWheelSpinVelocity()[idx];
                    } else {
                        raw = 0.0;
                    }
                }
            }
            features[i] = FeatureScaler.normalize(f, raw);
        }
        return features;
    }

    /** Cambio marcia rule-based tarato per KNN */
    private int getGear(SensorModel s) {
        int    gear      = s.getGear();
        double rpm       = s.getRPM();
        double speed     = s.getSpeed();            // km/h
        double absAngle  = Math.abs(s.getAngleToTrackAxis());   // rad

        /* gestisci N / R */
        if (gear < 1) return 1;

        /* —— bias verso la scalata in curva o a bassa velocità —— */
        boolean inCorner = absAngle > 0.10 || speed < 55;   // 0.10 rad ≈ 6°
        if (inCorner && gear > 2) {
            return gear - 1;        // anticipo di un rapporto
        }

        /* —— logica classica su giri motore —— */
        if (gear < 6 && rpm >= RPM_UP[gear - 1])       return gear + 1;
        if (gear > 1 && rpm <= RPM_DOWN[gear - 1])     return gear - 1;

        return gear;
    }

    @Override public void reset() { cache.put(new double[0], new double[0]); }
    @Override public void shutdown() {}
}
