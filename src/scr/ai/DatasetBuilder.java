package scr.ai;

import java.io.*;
import java.util.*;

public class DatasetBuilder {
    
    // Configurazioni predefinite delle feature
    public static final String[] CONFIG_BASIC = {"angle", "gear", "rpm", "speedX", "speedY", "trackPos"};
    public static final String[] CONFIG_WITH_SENSORS = {"angle", "gear", "rpm", "speedX", "speedY", "track0", "track5", "track9", "track13", "track18", "wheel0", "trackPos"};
    public static final String[] CONFIG_ALL_SENSORS = {"angle", "gear", "rpm", "speedX", "speedY", "track0", "track1", "track2", "track3", "track4", "track5", "track6", "track7", "track8", "track9", "track10", "track11", "track12", "track13", "track14", "track15", "track16", "track17", "track18", "trackPos"};
    
    // Le azioni sono sempre le stesse
    private static final String[] OUTPUT_COLUMNS = {"steer", "accel", "brake"};
    
    public static List<DataPoint> loadCSV(String path, String[] featureColumns) throws IOException {
        List<DataPoint> list = new ArrayList<>();
        
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String headerLine = br.readLine();
            String[] headers = headerLine.split(",");
            
            // Trova gli indici delle colonne features
            int[] featureIndices = new int[featureColumns.length];
            for (int i = 0; i < featureColumns.length; i++) {
                featureIndices[i] = findColumnIndex(headers, featureColumns[i]);
                if (featureIndices[i] == -1) {
                    throw new IllegalArgumentException("Colonna non trovata: " + featureColumns[i]);
                }
            }
            
            // Trova gli indici delle colonne output
            int[] outputIndices = new int[OUTPUT_COLUMNS.length];
            for (int i = 0; i < OUTPUT_COLUMNS.length; i++) {
                outputIndices[i] = findColumnIndex(headers, OUTPUT_COLUMNS[i]);
                if (outputIndices[i] == -1) {
                    throw new IllegalArgumentException("Colonna output non trovata: " + OUTPUT_COLUMNS[i]);
                }
            }
            
            String line;
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split(",");
                
                // Estrai features
                double[] features = new double[featureColumns.length];
                for (int i = 0; i < featureColumns.length; i++) {
                    features[i] = parseAndNormalize(tokens[featureIndices[i]], featureColumns[i]);
                }
                
                // Estrai azioni
                double[] actions = new double[OUTPUT_COLUMNS.length];
                for (int i = 0; i < OUTPUT_COLUMNS.length; i++) {
                    actions[i] = Double.parseDouble(tokens[outputIndices[i]]);
                }
                
                list.add(new DataPoint(features, actions));
            }
        }
        return list;
    }
    
    private static int findColumnIndex(String[] headers, String columnName) {
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].trim().equals(columnName)) {
                return i;
            }
        }
        return -1;
    }
    
    private static double parseAndNormalize(String value, String columnName) {
        double val = Double.parseDouble(value);
        
        // Normalizzazione semplificata basata sui dati
        switch (columnName) {
            // StandardScaler approssimativo: centra attorno a 0
            case "angle" -> val = val; // Già piccolo, lascia così
            case "curLapTime" -> val = (val - 22) / 15; // Centra attorno a 22, dividi per ~std
            case "speedX" -> val = (val - 140) / 50; // Centra attorno a 140
            case "speedY" -> val = val / 50; // Normalizza per outliers
            case "trackPos" -> val = val; // Già -1 a 1
            
            // MinMaxScaler semplice: 0-1
            case "gear" -> val = (val - 1) / 5; // 1-6 → 0-1
            case "rpm" -> val = val / 10000; // Max ~10k
            case "damage" -> val = val; // Già 0
            case "lastLapTime" -> val = val / 60; // Max ~60 sec
            
            // Track sensors: semplice divisione
            default -> {
                if (columnName.startsWith("track")) {
                    val = val / 200; // Max 200
                } else if (columnName.startsWith("wheel")) {
                    val = val / 200; // Circa il range
                }
            }
        }
        
        return val;
    }
    
    public static void main(String[] args) throws Exception {
        String csv = args.length > 0 ? args[0] : "drive_log.csv";
        String model = args.length > 1 ? args[1] : "knn.tree";
        String config = args.length > 2 ? args[2] : "sensors";
        
        // Scegli configurazione
        String[] featureColumns;
        switch (config.toLowerCase()) {
            case "basic" -> featureColumns = CONFIG_BASIC;
            case "sensors" -> featureColumns = CONFIG_WITH_SENSORS;
            case "all" -> featureColumns = CONFIG_ALL_SENSORS;
            default -> {
                System.err.println("Configurazione non valida. Usa: basic, sensors, all");
                return;
            }
        }
        
        System.out.println("Usando configurazione: " + config);
        System.out.println("Features: " + Arrays.toString(featureColumns));
        
        List<DataPoint> pts = loadCSV(csv, featureColumns);
        KDTree tree = new KDTree(pts, pts.get(0).features.length);
        
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(model))) {
            oos.writeObject(tree);
        }
        
        System.out.printf("Saved KD-Tree (%d points, %d features) → %s%n", 
                         pts.size(), featureColumns.length, model);
    }
}