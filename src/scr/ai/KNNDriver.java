package scr.ai;

import java.io.*;
import java.util.*;

import scr.Action;
import scr.SensorModel;
import scr.SimpleDriver;

public class KNNDriver extends SimpleDriver {

    /* ---------- modelli, cache, fallback ---------- */
    private final KDTree      tree;
    private final ActionCache cache     = new ActionCache();
    private final SimpleDriver fallback = new SimpleDriver();

    /* ---------- watchdog ---------- */
    private static final long MAX_LATENCY_MS  = 15;
    private static final long LOG_INTERVAL_NS = 10_000_000_000L;   // 10 s

    private long totTime  = 0;      // somma dei ms totali
    private long frames   = 0;      // cicli
    private long maxDelay = 0;      // max ritardo nell’intervallo
    private long lastLog  = System.nanoTime();

    /* ---------- cambio marcia ---------- */
    private static final double[] RPM_UP   = { 7000, 7200, 7300, 7400, 7500, 0 };
    private static final double[] RPM_DOWN = { 0,    2800, 3000, 3200, 3500, 0 };
    private static final long   MIN_SHIFT_NS = 300_000_000;  // 0.3 s
    private static final double ANG_CURVE    = 0.12;         // ~7°
    private static final double SPEED_MIN_DS = 60;           // no downshift sopra 60 km/h
    private int   lastGear        = 1;
    private long  lastShiftTimeNs = 0;


    /* steering smoothing */
    private double prevSteer = 0;
    private static final double ALPHA = 0.7;

    /* OOD guard */
    private static final double OOD_THRESHOLD = 0.4;

    /* feature set */
    private static final String[] FEATURES = DatasetBuilder.CONFIG_WITH_SENSORS;

    /* ---------- ctor ---------- */
    public KNNDriver() {
        try (ObjectInputStream ois =
                 new ObjectInputStream(new FileInputStream("knn.tree"))) {
            tree = (KDTree) ois.readObject();
            System.out.printf("KD-Tree caricato (%d feat)%n", FEATURES.length);
        } catch (Exception e) {
            throw new RuntimeException("Cannot load KD-Tree", e);
        }
    }

    /* ---------- loop ---------- */
    @Override
    public Action control(SensorModel s) {

        long t0 = System.nanoTime();             // START CHRONO

        /* ---- K-NN oppure cache ---- */
        double[] in  = extractFeatures(s);
        double[] actArr = cache.lookup(in);

        if (actArr == null) {                    // cache miss → KNN
            int k = dynamicK(s.getSpeed());
            List<DataPoint> nn = tree.nearest(in, k);

            /* pesi inversi alla distanza */
            double[] w = new double[k];
            double wSum = 0;
            for (int i = 0; i < k; i++) {
                double d = euclidean(in, nn.get(i).features);
                w[i] = 1.0 / (d + 1e-6);
                wSum += w[i];
            }
            actArr = new double[3];
            for (int i = 0; i < k; i++)
                for (int j = 0; j < 3; j++)
                    actArr[j] += nn.get(i).action[j] * w[i];
            for (int j = 0; j < 3; j++) actArr[j] /= wSum;

            /* OOD guard */
            double nearest = euclidean(in, nn.get(0).features);
            if (nearest > OOD_THRESHOLD) {
                System.err.printf("\n\n====================\n[WARN] OOD %.3f > %.3f – fallback%n\n====\n",
                                  nearest, OOD_THRESHOLD);
                return fallback.control(s);
            }
            cache.put(in, actArr);
        }

        Action out = buildAction(actArr, s);

        /* ---- TIMING & LOG ---- */
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;     // STOP

        // stats
        totTime  += elapsedMs;
        frames++;
        if (elapsedMs > maxDelay) maxDelay = elapsedMs;

        // fallback su frame lenti
        if (elapsedMs > MAX_LATENCY_MS) {
            System.err.printf("\n\n**************\n[WARN] slow frame %d ms > %d – fallback%n",
                              elapsedMs, MAX_LATENCY_MS);
            out = fallback.control(s);
        }

        // log ogni 10 s
        long now = System.nanoTime();
        if (now - lastLog >= LOG_INTERVAL_NS) {
            double avg = (double) totTime / frames;
            System.out.printf("\n***\n*****\n******\n[INFO] avg %.2f ms   max %d ms%n\n\n", avg, maxDelay);
            lastLog = now;
            maxDelay = 0;                        // reset per prossimo intervallo
        }

        return out;
    }

    /* ---------- utility ---------- */

    private int dynamicK(double speed) {
        if (speed < 30)  return 3;
        if (speed > 120) return 7;
        return 5;
    }

    private Action buildAction(double[] a, SensorModel s) {
        Action out = new Action();

        double steer = Math.max(-1, Math.min(1, a[0]));
        steer = ALPHA * steer + (1 - ALPHA) * prevSteer;
        prevSteer = steer;

        out.steering   = steer;
        out.accelerate = Math.max(0, Math.min(1, a[1]));
        out.brake      = Math.max(0, Math.min(1, a[2]));
        out.gear       = chooseGear(s);
        return out;
    }

    private double[] extractFeatures(SensorModel s) {
        double[] f = new double[FEATURES.length];
        for (int i = 0; i < FEATURES.length; i++) {
            String col = FEATURES[i];
            double raw;
            switch (col) {
                case "angle"       -> raw = s.getAngleToTrackAxis();
                case "curLapTime"  -> raw = s.getCurrentLapTime();
                case "speedX"      -> raw = s.getSpeed();
                case "speedY"      -> raw = s.getLateralSpeed();
                case "trackPos"    -> raw = s.getTrackPosition();
                case "gear"        -> raw = s.getGear();
                case "rpm"         -> raw = s.getRPM();
                case "damage"      -> raw = s.getDamage();
                case "lastLapTime" -> raw = s.getLastLapTime();
                default -> {
                    if (col.startsWith("track")) {
                        int idx = Integer.parseInt(col.substring(5));
                        raw = s.getTrackEdgeSensors()[idx];
                    } else if (col.startsWith("wheel")) {
                        int idx = Integer.parseInt(col.substring(5));
                        raw = s.getWheelSpinVelocity()[idx];
                    } else raw = 0;
                }
            }
            f[i] = FeatureScaler.normalize(col, raw);
        }
        return f;
    }

    private int chooseGear(SensorModel s) {
        int    g    = s.getGear();
        double rpm  = s.getRPM();
        double v    = s.getSpeed();
        double absA = Math.abs(s.getAngleToTrackAxis());

        /* gestisci N / R */
        if (g < 1) return 1;

        /* blocco anti-rimbalzo: aspetta MIN_SHIFT_NS prima di nuovo cambio */
        long now = System.nanoTime();
        if (now - lastShiftTimeNs < MIN_SHIFT_NS) return lastGear;

        /* —— logica upshift —— */
        if (g < 6 && rpm >= RPM_UP[g - 1]) {
            lastShiftTimeNs = now;
            lastGear = g + 1;
            return lastGear;
        }

        /* —— logica downshift —— */
        boolean inCurve = absA > ANG_CURVE;
        boolean wantShorter = (inCurve && g > 2) || (rpm <= RPM_DOWN[g - 1] && v < SPEED_MIN_DS);

        if (wantShorter && g > 1) {
            lastShiftTimeNs = now;
            lastGear = g - 1;
            return lastGear;
        }

        /* nessun cambio */
        lastGear = g;
        return g;
    }

    private static double euclidean(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    /* ---------- lifecycle ---------- */
    @Override
    public void reset() {
        cache.clear();
        prevSteer = 0;
        totTime = frames = maxDelay = 0;
        lastLog = System.nanoTime();
    }

    @Override public void shutdown() {}
}
