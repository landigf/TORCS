package scr.ai;

public class ActionCache {
    private double[] lastIn = null;
    private double[] lastOut = null;
    private final double tol = 0.005; // Tolleranza per considerare simili le azioni

    public boolean isSimilar(double[] in) {
        if (lastIn == null) return false;
        double sum = 0;
        for (int i = 0; i < in.length; i++) {
            double d = in[i] - lastIn[i]; sum += d * d;
        }
        return Math.sqrt(sum) < tol;
    }

    public void update(double[] in, double[] out) {
        this.lastIn  = in != null ? in.clone() : null;
        this.lastOut = out != null ? out.clone() : null;
    }

    public double[] get() {
        return lastOut;
    }
}
