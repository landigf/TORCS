package scr.ai;

import java.io.*;
import java.util.*;

/**
 * Costruisce un KD-tree a partire da un CSV log delle corse.
 * Usa FeatureScaler per normalizzare tutte le feature.
 */
public class DatasetBuilder {

    /* ===== configurazioni di colonne ===== */
    public static final String[] CONFIG_BASIC = {
        "angle", "gear", "rpm", "speedX", "speedY", "trackPos"
    };
    public static final String[] CONFIG_WITH_SENSORS = {
        "angle", "gear", "rpm", "speedX", "speedY",
        "track0", "track5", "track9", "track13", "track18",
        "wheel0", "trackPos"
    };
    public static final String[] CONFIG_ALL_SENSORS = {
        "angle", "gear", "rpm", "speedX", "speedY",
        "track0","track1","track2","track3","track4","track5",
        "track6","track7","track8","track9","track10","track11",
        "track12","track13","track14","track15","track16","track17","track18",
        "wheel0", "wheel1", "wheel2", "wheel3", "trackPos"
    };

    /* colonne azione ≡ target */
    private static final String[] OUTPUT_COLUMNS = { "steer", "accel", "brake" };

    /* ===== lettura CSV ===== */
    public static List<DataPoint> loadCSV(String path,
                                          String[] featureCols) throws IOException {

        List<DataPoint> pts = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {

            /* —— header */
            String[] headers = br.readLine().split(",");

            int[] featIdx = findIndices(headers, featureCols);
            int[] outIdx  = findIndices(headers, OUTPUT_COLUMNS);

            /* —— righe dati */
            String line;
            while ((line = br.readLine()) != null) {

                String[] tok = line.split(",");

                double[] feat = new double[featureCols.length];
                for (int i = 0; i < featureCols.length; i++) {
                    feat[i] = FeatureScaler.normalize(featureCols[i],
                                                      Double.parseDouble(tok[featIdx[i]]));
                }

                double[] act = new double[OUTPUT_COLUMNS.length];
                for (int i = 0; i < OUTPUT_COLUMNS.length; i++) {
                    act[i] = Double.parseDouble(tok[outIdx[i]]);
                }

                pts.add(new DataPoint(feat, act));
            }
        }
        return pts;
    }

    private static int[] findIndices(String[] headers, String[] cols) {
        int[] idx = new int[cols.length];
        for (int i = 0; i < cols.length; i++) {
            idx[i] = -1;
            for (int j = 0; j < headers.length; j++) {
                if (headers[j].trim().equals(cols[i])) {
                    idx[i] = j;
                    break;
                }
            }
            if (idx[i] == -1)
                throw new IllegalArgumentException("Colonna non trovata: " + cols[i]);
        }
        return idx;
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
            default -> throw new IllegalArgumentException(
                    "Config non valida: usa basic | sensors | all");
        };

        System.out.printf("Build KD-Tree con config '%s'%n", cfg);
        System.out.println("Features: " + Arrays.toString(featCols));

        List<DataPoint> pts = loadCSV(csv, featCols);
        KDTree tree = new KDTree(pts, featCols.length);

        try (ObjectOutputStream oos =
                     new ObjectOutputStream(new FileOutputStream(model))) {
            oos.writeObject(tree);
        }
        System.out.printf("Salvato KD-Tree (%d punti, %d feat) → %s%n",
                          pts.size(), featCols.length, model);
    }
}
