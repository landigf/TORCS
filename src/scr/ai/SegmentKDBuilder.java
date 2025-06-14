package scr.ai;

import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds multiple KDTree files by partitioning the dataset according to
 * distanceFromStart (already normalised 0–1).
 *
 * Usage: java scr.ai.SegmentKDBuilder <csv> [segments]
 *
 * Output: knn_seg_00.tree, knn_seg_01.tree, ...
 */
public class SegmentKDBuilder {

    private static final int DEFAULT_SEGMENTS = 20;
    private static final String[] FEATURE_COLS = DatasetBuilder.CONFIG_WITH_SENSORS;

    public static void main(String[] args) throws Exception {

        if (args.length == 0) {
            System.err.println("Usage: java scr.ai.SegmentKDBuilder <csv> [segments]");
            return;
        }

        String csv = args[0];
        int SEGMENTS = args.length >= 2 ? Integer.parseInt(args[1]) : DEFAULT_SEGMENTS;

        // Load the entire dataset, already scaled
        List<DataPoint> points = DatasetBuilder.loadCSV(csv, FEATURE_COLS);

        // Locate the index of the distanceFromStart feature
        int distIdx = -1;
        System.out.println("Searching for distanceFromStart among features:");
        for (int i = 0; i < FEATURE_COLS.length; i++) {
            System.out.printf("  [%d] %s%n", i, FEATURE_COLS[i]);
            if ("distanceFromStart".equals(FEATURE_COLS[i])) {
            distIdx = i;
            break;
            }
        }
        if (distIdx == -1) {
            throw new IllegalStateException("distanceFromStart column missing in configuration");
        }
        System.out.printf("Found distanceFromStart at index %d%n", distIdx);

        // Create buckets
        @SuppressWarnings("unchecked")
        List<DataPoint>[] buckets = (List<DataPoint>[]) new List[SEGMENTS];
        for (int i = 0; i < SEGMENTS; i++) {
            buckets[i] = new ArrayList<>();
        }

        // Assign each point to a bucket
        for (DataPoint p : points) {
            double d = p.features[distIdx];   // already 0–1
            int seg = (int) Math.floor(d * SEGMENTS) % SEGMENTS;
            buckets[seg].add(p);
        }

        // Build and save a KDTree per bucket
        for (int seg = 0; seg < SEGMENTS; seg++) {
            if (buckets[seg].isEmpty()) continue;
            KDTree tree = new KDTree(buckets[seg], FEATURE_COLS.length);
            String filename = String.format("knn_seg_%02d.tree", seg);
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(filename))) {
                oos.writeObject(tree);
            }
            System.out.printf("Segment %02d -> %d points -> %s%n", seg, buckets[seg].size(), filename);
        }

        System.out.println("Done.");
    }
}
