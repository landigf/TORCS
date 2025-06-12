package scr.ai;

import java.io.*;
import java.util.*;

import scr.Action;
import scr.SensorModel;
import scr.SimpleDriver;

public class KNNDriver extends SimpleDriver {

    /* ---------- modelli, cache, fallback ---------- */
    private final KDTree      tree;
    //private final ActionCache cache     = new ActionCache();
    private final SimpleDriver fallback = new SimpleDriver();

    /* ---------- watchdog ---------- */
    private static final long MAX_LATENCY_MS  = 17;
    private static final long LOG_INTERVAL_NS = 5_000_000_000L;   // 5 s

    private long totTime  = 0;      // somma dei ms totali
    private long frames   = 0;      // cicli
    private long maxDelay = 0;      // max ritardo nell’intervallo
    private long lastLog  = System.nanoTime();

    private SimpleGear gearChanger = new SimpleGear();


    /* steering smoothing */
    //private double prevSteer = 0;
    //private static final double ALPHA = 0.2;

    /* OOD guard - OutOfDistribution */
    private static final double OOD_THRESHOLD = 1.0;

    /* feature set */
    private static final String[] FEATURES = DatasetBuilder.CONFIG_WITH_SENSORS;

    private static final Map<String, Double> FEAT_W = Map.of(
        "distanceFromStart", 4.0,   // domina il matching (↑ ⇒ curva giusta)
        "angle",              2.0,
        "trackPos",           2.0,
        /* tutto il resto */  "default", 1.0);

    boolean debug = false;  // flag per debug

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

        double trackPos = s.getTrackPosition();

        boolean offTrack = (trackPos <= -1.00 || trackPos >= 1.00);
        double centralTrack = s.getTrackEdgeSensors()[9]; // centro pista
        boolean isStuck = centralTrack == -1.0;

        if (offTrack || s.getSpeed() == 0 || isStuck) {
            System.err.printf("\n\n=========\n\n\n\n===========\n[WARN] off-track: %.2f, stuck: %b - fallback%n====\n",
                              trackPos, isStuck);
            //prevSteer = 0;  // reset steering
            return fallback.control(s);
        }

        long t0 = System.nanoTime();             // START CHRONO
        double[] in  = extractFeatures(s);
        /* ---- K-NN oppure cache ---- */
        
        //double[] actArr = cache.lookup(in);
        double[] actArr = null;  // inizializza come null per KNN

        int k = dynamicK(s.getSpeed());
        List<DataPoint> nn = tree.nearest(in, k);
        double nearest = euclidean(in, nn.get(0).features);
        if (actArr == null) {        // cache miss → KNN
            // Stampa tutti i vicini trovati per debug
            if (nn.size() < k) {
                System.err.printf("\n\n[WARN] %d vicini trovati, ma ne servono %d%n", nn.size(), k);
                return fallback.control(s);
            } else {
                System.out.printf("\n\n[INFO] %d vicini trovati%n", nn.size());
            }
            
            // Stampa il contenuto dei DataPoint dei vicini
            for (int i = 0; i < nn.size(); i++) {
                DataPoint dp = nn.get(i);
                System.out.printf("Vicino %d - Features: %s, Action: [%.3f, %.3f, %.3f]%n", 
                    i, Arrays.toString(dp.features), dp.action[0], dp.action[1], dp.action[2]);
            }
            System.out.printf("\n\n[INFO] KNN con k=%d%n\n\n\n\n\n", k);

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
            
            if (nearest > OOD_THRESHOLD) {
                System.err.printf("\n\n====================\n[WARN] OOD %.3f > %.3f - fallback%n\n====\n",
                                  nearest, OOD_THRESHOLD);
                //prevSteer = 0;  // reset steering
                return fallback.control(s);
            }
            //cache.put(in, actArr);
        }

        Action knnAction = buildAction(actArr, s);
        Action safeAction = fallback.control(s);
        double lambda = Math.max(0, 1 - nearest / OOD_THRESHOLD);
        // fusione tra KNN e fallback
        Action out = new Action();
        out.steering   = lambda * knnAction.steering + (1 - lambda) * safeAction.steering;
        // Aumenta il peso del KNN per l'accelerazione
        double forceKnn = 0.2;  // forza del KNN sull'accelerazione
        if (centralTrack > 100 || s.getSpeed() > 40) forceKnn = 0.9;
        double knnWeight = Math.min(1.0, lambda + forceKnn);
        out.accelerate = knnWeight * knnAction.accelerate + (1 - knnWeight) * safeAction.accelerate;
        out.brake      = knnWeight * knnAction.brake + (1 - knnWeight) * safeAction.brake;
        out.gear       = gearChanger.chooseGear(s);  // cambio marcia sempre da fallback
        /* --- debug --- */
        System.out.printf("\nknnAction: %s, \nsafeAction: %s, lambda: %.2f%n",
                          knnAction, safeAction, lambda);

        /* ---- TIMING & LOG ---- */
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;     // STOP

        // stats
        totTime  += elapsedMs;
        frames++;
        if (elapsedMs > maxDelay) maxDelay = elapsedMs;

        // fallback su frame lenti
        if (elapsedMs > MAX_LATENCY_MS) {
            System.err.printf("\n\n\n\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n**************\n[WARN] slow frame %d ms > %d - fallback%n",
                              elapsedMs, MAX_LATENCY_MS);
            out = safeAction;  // fallback
        }

        // log ogni 5 s
        long now = System.nanoTime();
        if (now - lastLog >= LOG_INTERVAL_NS) {
            double avg = (double) totTime / frames;
            System.out.printf("\n***[INFO] avg %.2f ms   max %d ms%n\n\n\n\n\n\n\n", avg, maxDelay);
            lastLog = now;
            maxDelay = 0;                        // reset per prossimo intervallo
        }
        // // Correzione steering aggressiva per evitare di uscire dalla pista
        // double absTrackPos = Math.abs(trackPos);
        
        // if (absTrackPos > 0.8) {
        //     // Situazione critica: forza steering verso il centro
        //     double correctionStrength = (absTrackPos - 0.8) / 0.2; // 0-1 quando trackPos è 0.8-1.0
        //     correctionStrength = Math.min(1.0, correctionStrength);
            
        //     if (trackPos > 0) {
        //     // Troppo a sinistra, forza steering a destra
        //     out.steering = -correctionStrength;
        //     } else {
        //     // Troppo a destra, forza steering a sinistra
        //     out.steering = correctionStrength;
        //     }
        // } else if (absTrackPos > 0.5) {
        //     // Situazione di avvertimento: modifica gradualmente lo steering
        //     double warningStrength = (absTrackPos - 0.5) / 0.3; // 0-1 quando trackPos è 0.5-0.8
            
        //     if (trackPos > 0 && out.steering > 0) {
        //     // A sinistra del centro e steering verso sinistra: riduci o inverti
        //     out.steering = out.steering * (1 - warningStrength) - warningStrength * 0.5;
        //     } else if (trackPos < 0 && out.steering < 0) {
        //     // A destra del centro e steering verso destra: riduci o inverti  
        //     out.steering = out.steering * (1 - warningStrength) + warningStrength * 0.5;
        //     }
        // }
        
        // Clamp finale per sicurezza
        out.steering = Math.max(-1.0, Math.min(1.0, out.steering));
        System.out.printf("--------Steering: %.3f, Accel: %.3f, Brake: %.3f, Gear: %d%n",
                          out.steering, out.accelerate, out.brake, out.gear);

        if (debug == false) {
            out.steering = 0;
            debug = true;  // attiva debug solo una volta
        } else {
            debug = false;  // disattiva debug per il prossimo ciclo
        }

        return out;
    }

    /* ---------- utility ---------- */

    private int dynamicK(double speed) {
        return 5;
    }

    private Action buildAction(double[] a, SensorModel s) {
        Action out = new Action();

        double steer = Math.max(-1, Math.min(1, a[0]));
        //steer = ALPHA * steer + (1 - ALPHA) * prevSteer;
        //prevSteer = steer;

    
        System.out.printf("\nIN BUILD: --- Steering: %.3f, Accel: %.3f, Brake: %.3f%n", steer, a[1], a[2]);
        out.steering   = steer;
        out.accelerate = Math.max(0, Math.min(1, a[1]));
        out.brake      = Math.max(0, Math.min(1, a[2]));
        out.gear       = gearChanger.chooseGear(s);
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
                case "distanceFromStart" -> raw = s.getDistanceFromStartLine();
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
        //cache.clear();
        //prevSteer = 0;
        totTime = frames = maxDelay = 0;
        lastLog = System.nanoTime();
    }

    @Override public void shutdown() {}
}
