
package scr.ai;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Simple LRU cache for KNN inference results.
 * Keeps the last N (input, action) pairs and re‑uses the action
 * if a new query is within tol (L2) of a cached input.
 */
public class ActionCache {
    private static final int MAX_SIZE = 50;
    private static final double TOL = 0.03;  // Empirically: ~3 % of typical feature norm

    private static class Entry {
        final double[] in;
        final double[] out;
        Entry(double[] in, double[] out) {
            this.in = in;
            this.out = out;
        }
    }

    private final Deque<Entry> lru = new ArrayDeque<>();

    /** Returns cached action if a similar input exists, else null. */
    public double[] lookup(double[] in) {
        for (Entry e : lru) {
            if (dist(in, e.in) < TOL) {
                // Move to front (LRU)
                lru.remove(e);
                lru.addFirst(e);
                return e.out.clone();
            }
        }
        return null;
    }

    /** Insert a new input/action pair. */
    public void put(double[] in, double[] out) {
        lru.addFirst(new Entry(in.clone(), out.clone()));
        if (lru.size() > MAX_SIZE) lru.removeLast();
    }

    private static double dist(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    /** Clears the cache. */
    public void clear() {
        lru.clear();
    }
}
