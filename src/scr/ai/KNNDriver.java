package scr.ai;

import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.io.PrintWriter;
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
    private static final int SEGMENTS = 32;   // deve combaciare con il builder
    private static final int MIN_K    = 5;
    private static final int MAX_K    = 7;

    /* watchdog / profilo */
    //private static final long LOG_INTERVAL_NS = 5_000_000_000L; // 5 s

    /* ====== modelli & fallback ====== */
    private final KDTree[] trees = new KDTree[SEGMENTS];
    private final SimpleDriver fallback = new SimpleDriver();

    /* ====== feature handling ====== */
    private static final String[] FEATURES = DatasetBuilder.CONFIG_WITH_SENSORS;

    /** Pesi della distanza per feature – "default" vale per tutte le altre. */
    static double wDFS = 1.2; // peso per distanceFromStart
    static double wAngle = 1.2; // peso per angle
    static double wSpeedX = 1.0; // peso per speedX
    static double wTrack13 = 2.0; // peso per track13 (se usata)
    private static final Map<String, Double> FEAT_W = Map.of(
        "distanceFromStart",  wDFS,
        "angle",              wAngle,
        "speedX",             wSpeedX,
        "track13",            wTrack13,
        "default",            1.0);

    /* OOD guard TODDO: prova a cambiare */
    private static final double OOD_THRESHOLD = 3.0;

    /* stats */
    // private long totTime  = 0;
    // private long frames   = 0;
    // private long maxDelay = 0;
    // private long lastLog  = System.nanoTime();

    private final SimpleGear gearChanger = new SimpleGear();

    private PrintWriter log;
    //private long t0;          // timestamp di riferimento per la colonna “time”

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

        // try {
        //     File f = new File("drive_log.csv");
        //     log = new PrintWriter(new FileWriter(f, false), true);
        //     if (f.length() == 0) {
        //         String header = "time,angle,curLapTime,distanceFromStart,fuel,damage,gear,rpm," +
        //                         "speedX,speedY,speedZ,lastLapTime," +
        //                         IntStream.range(0,19).mapToObj(i -> "track"+i)
        //                                 .collect(Collectors.joining(",")) + "," +
        //                         "wheel0,wheel1,wheel2,wheel3,trackPos,steer,accel,brake";
        //         log.println(header);
        //     }
        // } catch (IOException e) {
        //     throw new RuntimeException("Cannot open drive_log.csv", e);
        // }

    }

    /* ====== loop principale ====== */
    @Override
    public Action control(SensorModel s) {
        /* ------ sicurezza & fallback rapido ------ */
        double rawDist  = s.getDistanceFromStartLine();
        double normDist = FeatureScaler.normalize("distanceFromStart", rawDist);
        int seg         = (int) Math.floor(normDist * SEGMENTS) % SEGMENTS;
        System.out.printf("\n^^^[INFO] distanceFromStart=%.3f - segment=%02d%n", rawDist, seg);
        double trackPos = s.getTrackPosition();
        boolean offTrack = (trackPos <= -1.2 || trackPos >= 1.2);
        //boolean isStuck  = s.getTrackEdgeSensors()[9] == -1.0;
        
        /* ====== logging ====== */
        Action fallbackAction = fallback.control(s);
        fallbackAction.steering = Math.max(-0.5, Math.min(0.5, fallbackAction.steering)); // clamp steering

        /* ====== DA CONTROLLARE =======
         * ci mettiamo tutti quelli dove per ora fa ancora casino
         * 
        */
        if (seg == 21 && rawDist <= 3670) {
            fallbackAction.brake = Math.min(0.2, fallbackAction.brake); // frena in questo segmento
        } else if (seg >= 23 && seg <= 26 && rawDist <= 4840) {
            fallbackAction.accelerate = 1.0;
            fallbackAction.brake      = 0.0; // accelera in questi segmenti
        } else if (seg == 26) {
            fallbackAction.accelerate = 0.0;
            fallbackAction.brake      = 0.5; // accelera in questi segmenti
            if (trackPos < -1.0) fallbackAction.steering = 0.2; // evita steering negativo
            else if (trackPos > 1.0) fallbackAction.steering = -0.2; // evita steering positivo
        } else if (seg == 22 || seg == 29) {
            fallbackAction.brake      = 0.0;
        }
        boolean isDangerous = /*seg == 15 ||*/ seg == 20 || seg == 21 || seg == 22 || seg == 23 || seg == 25 || seg == 24 || seg == 26 || seg == 27 || seg == 28 || seg == 29;

        /* NON TESTATO TODO */
        if (!isDangerous){
            if (trackPos < -1.1 && fallbackAction.steering < 0) fallbackAction.steering = 0.17; // limit steering on left edge
            else if (trackPos > 1.1 && fallbackAction.steering > 0) fallbackAction.steering = -0.17; // limit steering on right edge
            else if (trackPos < -0.9 && fallbackAction.steering < 0) fallbackAction.steering = 0.07; // limit steering on left edge
            else if (trackPos > 0.9 && fallbackAction.steering > 0) fallbackAction.steering = -0.07; // limit steering on right edge
        }
        // // ----------------------------------prova a togliere 20
        if (seg != 0 && seg != SEGMENTS - 1) {
            if (offTrack || s.getSpeed() <= 7 || isDangerous || (seg == 15 && rawDist > 2746)) {
                System.err.printf("[WARN] fallback action - trackPos: %.2f", trackPos);
                //writeLog(s, fallbackAction);
                System.out.printf("[fallbackAction] seg=%02d, trackPos=%.3f, steering=%.3f, accel=%.3f, brake=%.3f%n", 
                   seg, trackPos, fallbackAction.steering, fallbackAction.accelerate, fallbackAction.brake);
                return fallbackAction;
            }
        }

        // if (offTrack || s.getSpeed() <= 7 || isStuck) {
        //     System.err.printf("\n---------->[WARN] Off-track: %.2f, Speed: %.2f, Stuck: %b%n", trackPos, s.getSpeed(), isStuck);
        //     //return fallback.control(s);
        //     writeLog(s, fallbackAction);
        //     return fallbackAction;
        // }
        

        /* ------ estrai le feature normalizzate ------ */
        double[] in = extractFeatures(s);

        /* ------ scegli l'albero in base a distanceFromStart ------ */
        
        
        KDTree tree     = selectTree(seg);
        if (tree == null) {
            //return fallback.control(s);
            //writeLog(s, fallbackAction);
            return fallbackAction;
        }
        System.out.printf("[INFO] Segmento %02d, distanza normalizzata=%.3f%n", seg, normDist);

        /* ------ k dinamico ------ */
        int k = dynamicK(s);
        boolean isInS = seg == 20 || seg == 21 || seg == 22;
        boolean isInFine = seg == 27 || seg == 28 || seg == 26;
        if (isInS) k = 6;
        else if (isInFine) k = 5;
        // if (isInS) {
        //     k = MIN_K+1; // segmenti critici: usa sempre k minimo
        // } else if (seg == 11 || seg == 9 || seg == 10){
        //     k = MAX_K - 1;
        // }
        System.out.printf("[INFO] k=%d (dynamic based on angle %.2f)\n", k, s.getAngleToTrackAxis());

        if (seg == 12 || seg == 13) {
            wDFS = 1;
            wAngle = 3;
            wTrack13 = 3;
            k = 5; // era 7
        } else if (seg == 14) {
            wTrack13 = 6;
            wSpeedX = 1.5;
            wAngle = 1.5;
            k = 3; // era 5
        } else if (seg == 27) {
            wSpeedX = 1.5;
            wAngle = 1.5;
            wTrack13 = 6;
            k = 4; // era 5
        } else {
            wDFS = 1.2; // reset to default
            wAngle = 1.2;
        }
        List<DataPoint> nn = tree.nearest(in, k);

        //boolean isStraight = Math.abs(s.getAngleToTrackAxis()) < 0.1;
        /* ------ Out-of-Distribution guard ------ */
        double nearest = weightedDist(in, nn.get(0).features);
        if (nearest > OOD_THRESHOLD) {
            System.err.printf("\n==============================\n[WARN] OOD guard attivata: nearest=%.3f > threshold=%.3f%n", nearest, OOD_THRESHOLD);
            //return fallback.control(s);
            //writeLog(s, fallbackAction);
            return fallbackAction;
        } 

        /* ------ media delle azioni ------ */
        double[] actArr = averageAction(nn);

        /* ------ costruisci e restituisci l'azione ------ */
        Action knnAction  = buildAction(actArr, s);

        // Blending logic: mix KNN with fallback when OOD is high or in segment 23
        
        Action out = knnAction; // default to KNN action
        
        
        if (isInS) {
            // Bilanciamento 50-50 tra KNN e fallback nei segmenti critici
            double blendRatio = 0.4;
            out.steering = blendRatio * knnAction.steering + (1 - blendRatio) * fallbackAction.steering;
            out.accelerate = blendRatio * knnAction.accelerate + (1 - blendRatio) * fallbackAction.accelerate;
            out.brake = blendRatio * knnAction.brake + (1 - blendRatio) * fallbackAction.brake;
            if (seg == 21 || seg == 22) {
                out.brake = Math.max(0.1, out.brake); // frena in questi segmenti
            }
        } else if (seg == 14 && rawDist < 2700 && trackPos < 0.0) {
            out.steering = Math.max(0.0, out.steering); // evita steering negativo in questo segmento
        } else if (seg == 27 || seg == 28) {
            if (nearest > OOD_THRESHOLD*0.5) {
                // Se OOD è alto, usa fallback
                double blendRatio = 0.2; // 20% KNN, 80% fallback
                out.steering = blendRatio * knnAction.steering + (1 - blendRatio) * fallbackAction.steering;
                out.accelerate = blendRatio * knnAction.accelerate + (1 - blendRatio) * fallbackAction.accelerate;
                out.brake = blendRatio * knnAction.brake + (1 - blendRatio) * fallbackAction.brake;
            } else {
                // Altrimenti, usa KNN
                out = knnAction;
            }
        }
        // else if (seg == 14) {
        //     // In 14 sbaglia
        //     double blendRatio = 0.8;
        //     out.steering = blendRatio * knnAction.steering + (1 - blendRatio) * fallbackAction.steering;
        //     out.accelerate = blendRatio * knnAction.accelerate + (1 - blendRatio) * fallbackAction.accelerate; // favorisce accelerazione
        //     out.brake = blendRatio * knnAction.brake + (1 - blendRatio) * fallbackAction.brake;
        // }


        /* clamp steering finale */
        if (isInS) {
            out.steering = Math.max(-0.6, Math.min(0.6, out.steering));
        } else if (seg == 13 || seg == 14 || seg == 15 || isInFine)
            if (trackPos < -0.8) {
                // Vicino al bordo sinistro: non limitare steering positivi
                out.steering = Math.max(-0.1, Math.min(0.6, out.steering));
            } else if (trackPos > 0.8) {
                // Vicino al bordo destro: non limitare steering negativi
                out.steering = Math.max(-0.6, Math.min(0.1, out.steering));
            } else {
                // Normale limitazione quando non si è vicini ai bordi
                out.steering = Math.max(-0.3, Math.min(0.3, out.steering));
            }
        else if (seg == 28) {
            return fallbackAction; // segmento 29: usa fallback
        } else if ((seg == 8 || seg == 18) && trackPos < 0.0) {
            out.steering = Math.max(0.0, out.steering);
        }
        else {
            out.steering = Math.max(-0.17, Math.min(0.17, out.steering));  
            // FORSE STRINGERE ANCORA DI PIù
        }

        //if (seg >= 27) out.steering = Math.max(-0.3, out.steering); // limit steering in segment 28+
        //if (seg >= 28) out.steering = Math.max(-0.1, out.steering); // limit steering in segment 29+
        /* ====== logging ====== */
        //if (seg == 20) out.steering = Math.max(-0.01, Math.min(0.01, out.steering));
        //if (seg >= 29) out.steering = Math.max(-0.01, Math.min(0.01, out.steering));
        if (trackPos < -1.1) out.steering = 0.17; // limit steering on left edge
        else if (trackPos > 1.1) out.steering = -0.17; // limit steering on right edge
        else if (trackPos < -0.9 && out.steering < 0) out.steering = 0.08; // limit steering on left edge
        else if (trackPos > 0.9 && out.steering > 0) out.steering = -0.08; // limit steering on right edge

       
        if (seg == 29 || seg == 30 || seg == 31 || seg == 0){
            //out.steering = Math.max(-0.02, Math.min(0.02, out.steering));
            out.brake = 0.0; // no brake in these segments
        }
        //writeLog(s, knnAction);
        System.out.printf("[ACTION] seg=%02d, trackPos=%.3f, steering=%.3f, accel=%.3f, brake=%.3f%n", 
                   seg, trackPos, out.steering, out.accelerate, out.brake);
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
        //totTime = frames = maxDelay = 0;
        //lastLog = System.nanoTime();

        /* ====== logging ====== */
        //t0 = System.currentTimeMillis(); // reset timestamp
    }

    /* ====== logging ====== */
    @Override public void shutdown() {
        if (log != null) log.close();
    }

    /* ====== logging ====== */
    // private void writeLog(SensorModel s, Action a) {
    //     // --- raccolta dati sensori ---
    //     double angle       = s.getAngleToTrackAxis();
    //     double curLapTime  = s.getCurrentLapTime();
    //     double distance    = s.getDistanceFromStartLine();
    //     double fuel        = s.getFuelLevel();
    //     double damage      = s.getDamage();
    //     double rpm         = s.getRPM();
    //     double speedX      = s.getSpeed();
    //     double speedY      = s.getLateralSpeed();
    //     double speedZ      = s.getZSpeed();
    //     double lastLapTime = s.getLastLapTime();
    //     double[] trackArr  = s.getTrackEdgeSensors();
    //     double[] wheelSpin = s.getWheelSpinVelocity();
    //     double trackPos    = s.getTrackPosition();
    //     long    time       = System.currentTimeMillis() - t0;
    //     StringBuilder sb = new StringBuilder();
    //     sb.append(time).append(',').append(angle).append(',')
    //     .append(curLapTime).append(',').append(distance).append(',')
    //     .append(fuel).append(',').append(damage).append(',')
    //     .append(a.gear).append(',').append(rpm).append(',')
    //     .append(speedX).append(',').append(speedY).append(',')
    //     .append(speedZ).append(',').append(lastLapTime);
    //     for (double d : trackArr)  sb.append(',').append(d);
    //     for (double w : wheelSpin) sb.append(',').append(w);
    //     sb.append(',').append(trackPos)
    //     .append(',').append(a.steering)
    //     .append(',').append(a.accelerate)
    //     .append(',').append(a.brake);
    //     log.println(sb.toString());   // scrivi su log
    // }
}