package scr.ai;

import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.List;
import java.util.Map;

import scr.Action;
import scr.SensorModel;
import scr.SimpleDriver;

/**
 * KNN-based pilot che usa **KD-Tree segmentati**: per ciascun intervallo di
 * distanceFromStart (normalizzato) viene caricato un albero diverso, così le
 * query k-NN non vedono campioni di zone lontane della pista.
 * <p>
 * – Segmenti prodotti da {@code SegmentKDBuilder.java}
 * – Numero di segmenti (SEGMENTS) deve combaciare fra builder e driver.
 */
public class KNNDriver extends SimpleDriver {

    /* ====== configurazione globale ====== */
    private static final int SEGMENTS = 35;   // deve combaciare con il builder
    private static final int MIN_K    = 3;
    private static final int MAX_K    = 5;

    /* watchdog / profilo */
    private static final long LOG_INTERVAL_NS = 5_000_000_000L; // 5 s

    /* ====== modelli & fallback ====== */
    private final KDTree[] trees = new KDTree[SEGMENTS];
    private final SimpleDriver fallback = new SimpleDriver();

    /* ====== feature handling ====== */
    private static final String[] FEATURES = DatasetBuilder.CONFIG_WITH_SENSORS;

    /** Pesi della distanza per feature – "default" vale per tutte le altre. */
    private static final Map<String, Double> FEAT_W = Map.of(
        "distanceFromStart", 5.0,
        "angle",              2.0,
        "trackPos",           2.5,
        "default",            1.0);

    /* OOD guard */
    private static final double OOD_THRESHOLD = 0.5;

    /* stats */
    private long totTime  = 0;
    private long frames   = 0;
    private long maxDelay = 0;
    private long lastLog  = System.nanoTime();

    private final SimpleGear gearChanger = new SimpleGear();

    /* ====== ctor ====== */
    public KNNDriver() {
        /* carica tutti i KD-Tree */
        for (int i = 0; i < SEGMENTS; i++) {
            String file = String.format("knn_seg_%02d.tree", i);
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                trees[i] = (KDTree) ois.readObject();
                System.out.printf("KD-Tree %02d caricato (%d punti)\n", i, trees[i].size());
            } catch (Exception e) {
                trees[i] = null; // segmento mancante: sarà gestito da selectTree()
                System.err.printf("[WARN] KD-Tree %02d mancante: %s\n", i, e.getMessage());
            }
        }
    }

    /* ====== loop principale ====== */
    @Override
    public Action control(SensorModel s) {
        /* ------ sicurezza & fallback rapido ------ */
        double rawDist  = s.getDistanceFromStartLine();
        double normDist = FeatureScaler.normalize("distanceFromStart", rawDist);
        int seg         = (int) Math.floor(normDist * SEGMENTS) % SEGMENTS;
        double trackPos = s.getTrackPosition();
        boolean offTrack = (trackPos <= -1.00 || trackPos >= 1.00);
        boolean isStuck  = s.getTrackEdgeSensors()[9] == -1.0;
        if (offTrack || s.getSpeed() < 7 || isStuck) {
            System.err.printf("\n---------->[WARN] Off-track: %.2f, Speed: %.2f, Stuck: %b%n", trackPos, s.getSpeed(), isStuck);
            return fallback.control(s);
        }
        boolean isStraight = Math.abs(s.getAngleToTrackAxis()) < 0.1;
        long t0 = System.nanoTime();

        /* ------ estrai le feature normalizzate ------ */
        double[] in = extractFeatures(s);

        /* ------ scegli l'albero in base a distanceFromStart ------ */
        
        
        KDTree tree     = selectTree(seg);
        if (tree == null) return fallback.control(s);
        System.out.printf("[INFO] Segmento %02d, distanza normalizzata=%.3f%n", seg, normDist);

        /* ------ k dinamico ------ */
        int k = dynamicK(s);
        System.out.printf("[INFO] k=%d (dynamic based on angle %.2f)\n", k, s.getAngleToTrackAxis());
        if (seg == 29) {
            System.out.printf("[INFO] Segmento 29: k=%d (curva media)\n", MIN_K);
            k = 2;
        } else if (seg == 13 || seg == 14 || seg == 15 || seg == 16 || seg == 17) {
            System.out.printf("[INFO] Segmento : k=%d (curva stretta)\n", MIN_K + 1);
            k = 4;
        } else if (seg == 22 || seg == 23) {
            System.out.printf("[INFO] Segmento 22-23: k=%d (S)\n", MAX_K);
            k = 3;
        }
        List<DataPoint> nn = tree.nearest(in, k);

        /* ------ Out-of-Distribution guard ------ */
        double nearest = weightedDist(in, nn.get(0).features);
        if (nearest > OOD_THRESHOLD && !isStraight && seg <= 31) {
            System.err.printf("\n==============================\n[WARN] OOD guard attivata: nearest=%.3f > threshold=%.3f%n", nearest, OOD_THRESHOLD);
            return fallback.control(s);
        } if (seg == 22 || seg == 23 && nearest > 0.26) {
            System.err.printf("\n=================->->==========\n[WARN] Segmento 22-23: OOD guard attivata, ma proseguo.\n");
            return fallback.control(s);
        }

        /* ------ media delle azioni ------ */
        double[] actArr = averageAction(nn);

        /* ------ costruisci e restituisci l'azione ------ */
        Action knnAction  = buildAction(actArr, s);

        Action out = knnAction;          // per ora usiamo solo il KNN puro

        /* ------ profiling temporale ------ */
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        totTime += elapsedMs;
        frames++;
        if (elapsedMs > maxDelay) maxDelay = elapsedMs;

        long now = System.nanoTime();
        if (now - lastLog >= LOG_INTERVAL_NS) {
            double avg = (double) totTime / frames;
            System.out.printf("[INFO] avg %.2f ms   max %d ms\n", avg, maxDelay);
            lastLog = now;
            maxDelay = 0;
        }

        /* clamp steering finale */
        out.steering = Math.max(-1.0, Math.min(1.0, out.steering));
        if (seg == 26 || seg == 27 || seg == 28) {
            out.steering = 0;
            out.brake = 0;
        }
        return out;
    }

    /* ====== helper ====== */
    /** Restituisce l'albero del segmento richiesto; se assente, prova quelli adiacenti. */
    private KDTree selectTree(int seg) {
        if (trees[seg] != null) return trees[seg];
        for (int d = 1; d < SEGMENTS; d++) {
            int left  = (seg - d + SEGMENTS) % SEGMENTS;
            if (trees[left] != null) return trees[left];
            int right = (seg + d) % SEGMENTS;
            if (trees[right] != null) return trees[right];
        }
        return null; // nessun albero caricato (caso limite)
    }

    /** k variabile in funzione dell'angolo alla tangente della pista. */
    private int dynamicK(SensorModel s) {
        double absA = Math.abs(s.getAngleToTrackAxis());
        if (absA > 0.3) return MIN_K;     // curva stretta
        if (absA > 0.1) return MIN_K + 1; // curva media
        return MAX_K;                     // rettilineo / curva larga
    }

    /** Media le (steering, accel, brake) dei vicini. */
    private static double[] averageAction(List<DataPoint> pts) {
        int dim = pts.get(0).action.length;
        double[] out = new double[dim];
        for (DataPoint p : pts) {
            for (int i = 0; i < dim; i++) out[i] += p.action[i];
        }
        for (int i = 0; i < dim; i++) out[i] /= pts.size();
        return out;
    }

    private Action buildAction(double[] a, SensorModel s) {
        Action out = new Action();
        out.steering   = Math.max(-1, Math.min(1, a[0]));
        out.accelerate = Math.max(0, Math.min(1, a[1]));
        out.brake      = Math.max(0, Math.min(1, a[2]));
        out.gear       = gearChanger.chooseGear(s);
        return out;
    }

    /** Converte i SensorModel nel vettore di feature normalizzate. */
    private double[] extractFeatures(SensorModel s) {
        double[] f = new double[FEATURES.length];
        for (int i = 0; i < FEATURES.length; i++) {
            String col = FEATURES[i];
            double raw;
            switch (col) {
                case "angle"               -> raw = s.getAngleToTrackAxis();
                case "curLapTime"          -> raw = s.getCurrentLapTime();
                case "distanceFromStart"   -> raw = s.getDistanceFromStartLine();
                case "speedX"              -> raw = s.getSpeed();
                case "speedY"              -> raw = s.getLateralSpeed();
                case "trackPos"            -> raw = s.getTrackPosition();
                case "gear"                -> raw = s.getGear();
                case "rpm"                 -> raw = s.getRPM();
                case "damage"              -> raw = s.getDamage();
                case "lastLapTime"         -> raw = s.getLastLapTime();
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

    /* distanza pesata (wrap-around su distanceFromStart) */
    private static double weightedDist(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            String col = FEATURES[i];
            double w   = FEAT_W.getOrDefault(col, FEAT_W.get("default"));
            double diff = a[i] - b[i];
            if ("distanceFromStart".equals(col))
                diff = Math.min(Math.abs(diff), 1.0 - Math.abs(diff));
            sum += w * diff * diff;
        }
        return Math.sqrt(sum);
    }

    /* ====== lifecycle ====== */
    @Override
    public void reset() {
        totTime = frames = maxDelay = 0;
        lastLog = System.nanoTime();
    }
    @Override public void shutdown() {}
}
