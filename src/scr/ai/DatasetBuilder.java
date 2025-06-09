package scr.ai;

import java.io.*;
import java.util.*;

public class DatasetBuilder {
    public static List<DataPoint> loadCSV(String path) throws IOException {
        List<DataPoint> list = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null) {
                String[] tok = line.split(",");
                double[] feat = new double[21];
                feat[0] = Double.parseDouble(tok[1]);
                feat[1] = Double.parseDouble(tok[2]) / 300.0;
                for (int i = 0; i < 19; i++) {
                    feat[2 + i] = Double.parseDouble(tok[3 + i]) / 200.0;
                }
                double[] act = {
                    Double.parseDouble(tok[22]),
                    Double.parseDouble(tok[23]),
                    Double.parseDouble(tok[24])
                };
                list.add(new DataPoint(feat, act));
            }
        }
        return list;
    }

    public static void main(String[] args) throws Exception {
        String csv   = args.length>0? args[0] : "drive_log.csv";
        String model = args.length>1? args[1] : "knn.tree";
        List<DataPoint> pts = loadCSV(csv);
        KDTree tree = new KDTree(pts, pts.get(0).features.length);
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream(model))) {
            oos.writeObject(tree);
        }
        System.out.printf("Saved KD-Tree (%d points) → %s%n", pts.size(), model);
    }
}