package scr.ai;

import java.io.*;
import java.util.*;

/**
 * DatasetBuilder – versione compatta che mantiene TUTTE le colonne
 * originali ma permette di aggiungere feature derivate che **non**
 * sono presenti nel CSV: angleSin, angleCos, curv.
 *
 * • Se nella configurazione compare angleSin / angleCos verranno calcolate
 *   da "angle".
 * • Se compare "curv" verrà calcolata da track8/9/10 se esistono,
 *   altrimenti da track5/9/13.
 * • Le altre colonne (gear, wheel0, …) funzionano come prima.
 */
public class DatasetBuilder {

    /* ===== configurazioni di colonne ===== */
    public static final String[] CONFIG_BASIC = {
        "angle", "gear", "rpm", "speedX", "speedY",
        "track0", "track5", "track9", "track13", "track18",
        "wheel0", "trackPos"
    };

    public static final String[] CONFIG_WITH_SENSORS = {
        // derivate
        "angle", 
        // motore / velocità
        "rpm", "speedX", "speedY",
        // visibilità pista (5 sensori)
        "track0", "track5", "track9","track13", "track18",
        // posizione
        "trackPos"
    };

    public static final String[] CONFIG_ALL_SENSORS = {
        "angle", "gear", "rpm", "speedX", "speedY",
        "track0","track1","track2","track3","track4","track5",
        "track6","track7","track8","track9","track10","track11",
        "track12","track13","track14","track15","track16","track17","track18",
        "wheel0", "wheel1", "wheel2", "wheel3", "trackPos"
    };

    /* colonne target */
    private static final String[] OUTPUT_COLUMNS = { "steer", "accel", "brake" };

    /* ===== CSV → DataPoint ===== */
    public static List<DataPoint> loadCSV(String path, String[] featureCols) throws IOException {

        List<DataPoint> pts = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {

            /* header */
            String[] header = br.readLine().split(",");
            Map<String,Integer> idx = indexMap(header);

            // assicurati di avere gli indici necessari alle derivate
            if (Arrays.asList(featureCols).contains("angleSin") || Arrays.asList(featureCols).contains("angleCos"))
                require(idx, "angle");
            if (Arrays.asList(featureCols).contains("curv"))
                require(idx, "track9"); // track9 sempre necessario

            String line;
            while ((line = br.readLine()) != null) {
                String[] tok = line.split(",");

                /* ——— feature vector dinamico ——— */
                double[] feat = new double[featureCols.length];
                for (int i = 0; i < featureCols.length; i++) {
                    feat[i] = FeatureScaler.normalize(featureCols[i], rawValue(featureCols[i], tok, idx));
                }

                /* ——— target ——— */
                double[] act = new double[OUTPUT_COLUMNS.length];
                for (int i = 0; i < OUTPUT_COLUMNS.length; i++)
                    act[i] = Double.parseDouble(tok[idx.get(OUTPUT_COLUMNS[i])]);

                pts.add(new DataPoint(feat, act));
            }
        }
        return pts;
    }

    /* ===== helper ===== */
    private static Map<String,Integer> indexMap(String[] header) {
        Map<String,Integer> m = new HashMap<>();
        for (int i = 0; i < header.length; i++) m.put(header[i].trim(), i);
        return m;
    }

    private static void require(Map<String,Integer> idx, String col) {
        if (!idx.containsKey(col))
            throw new IllegalArgumentException("Il CSV non contiene la colonna richiesta: " + col);
    }

    /**
     * Restituisce il valore grezzo (non normalizzato) della feature.
     * Se è derivata, la calcola usando le colonne di base.
     */
    private static double rawValue(String f, String[] tok, Map<String,Integer> idx) {
        switch (f) {
            case "angleSin" -> {
                double angle = Double.parseDouble(tok[idx.get("angle")]);
                return Math.sin(angle);
            }
            case "angleCos" -> {
                double angle = Double.parseDouble(tok[idx.get("angle")]);
                return Math.cos(angle);
            }
            case "curv" -> {
                double tr9 = Double.parseDouble(tok[idx.get("track9")]);
                // preferisci track8/10 se esistono, altrimenti fallback su 5/13
                Double t8 = idx.containsKey("track8")  ? Double.parseDouble(tok[idx.get("track8")])  : null;
                Double t10= idx.containsKey("track10") ? Double.parseDouble(tok[idx.get("track10")]) : null;
                if (t8 != null && t10 != null) return (t8 - 2*tr9 + t10)/2.0;
                double t5  = idx.containsKey("track5") ? Double.parseDouble(tok[idx.get("track5")]) : 0;
                double t13 = idx.containsKey("track13")? Double.parseDouble(tok[idx.get("track13")]): 0;
                return (t5 - 2*tr9 + t13)/2.0;
            }
            default -> {
                Integer i = idx.get(f);
                if (i == null)
                    throw new IllegalArgumentException("Colonna non trovata nel CSV: " + f);
                return Double.parseDouble(tok[i]);
            }
        }
    }

    /* ===== main ===== */
    public static void main(String[] args) throws Exception {
        String csv   = args.length > 0 ? args[0] : "drive_log.csv";
        String model = args.length > 1 ? args[1] : "knn.tree";
        String cfg   = args.length > 2 ? args[2] : "sensors";

        String[] featCols = switch (cfg.toLowerCase()) {
            case "basic"   -> CONFIG_BASIC;
            case "sensors" -> CONFIG_WITH_SENSORS;
            case "all"     -> CONFIG_ALL_SENSORS;
            default -> throw new IllegalArgumentException("Config non valida: basic | sensors | all");
        };

        System.out.printf("Build KD-Tree config='%s'%n", cfg);
        List<DataPoint> pts = loadCSV(csv, featCols);
        KDTree tree = new KDTree(pts, featCols.length);
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(model))) {
            oos.writeObject(tree);
        }
        System.out.printf("Salvato KD-Tree (%d punti, %d feat) → %s%n", pts.size(), featCols.length, model);
    }
}
