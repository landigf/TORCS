package scr.ai;

import java.io.*;
import java.util.*;
import scr.Action;
import scr.SensorModel;
import scr.SimpleDriver;

public class KNNDriver extends SimpleDriver {
    private KDTree tree;
    private final ActionCache cache = new ActionCache();
    private final int k = 3;
    private int gear = 1;

    public KNNDriver() {
        try (ObjectInputStream ois = new ObjectInputStream(
                new FileInputStream("knn.tree"))) {
            tree = (KDTree) ois.readObject();
        } catch (Exception e) {
            throw new RuntimeException("Cannot load KD-Tree", e);
        }
    }

    @Override
    public Action control(SensorModel s) {
        double[] in = extractFeatures(s);
        if (cache.isSimilar(in)) {
            return buildAction(cache.get(), s);
        }
        List<DataPoint> nn = tree.nearest(in, k);
        double[] a = new double[3];
        for (DataPoint p : nn) for (int i = 0; i < 3; i++) a[i] += p.action[i];
        for (int i = 0; i < 3; i++) a[i] /= k;
        cache.update(in, a);
        return buildAction(a, s);
    }

    private double[] extractFeatures(SensorModel s) {
        double[] f = new double[21];
        f[0] = s.getAngleToTrackAxis();
        f[1] = s.getSpeed() / 300.0;
        double[] tr = s.getTrackEdgeSensors();
        for (int i = 0; i < 19; i++) f[2 + i] = tr[i] / 200.0;
        return f;
    }

    private boolean isStraight(SensorModel s) {
        double[] t = s.getTrackEdgeSensors();
        return t[9] > 150 && Math.abs(s.getAngleToTrackAxis()) < 0.1;
    }

    private boolean isCurve(SensorModel s) {
        double[] t = s.getTrackEdgeSensors();
        return t[9] < 70 || Math.abs(s.getAngleToTrackAxis()) > 0.3;
    }

    private int updateGear(SensorModel s, double accel, double brake) {
        double rpm = s.getRPM();
        double speed = s.getSpeed();
        if (isStraight(s)) {
            if (rpm > 8500 && gear < 6) gear++;
            if (rpm < 4000 && gear > 1) gear--;
        } else if (isCurve(s)) {
            if (speed > 100) return 0;
            if (rpm < 3000 && gear > 1) gear--;
        }
        return gear;
    }

    private Action buildAction(double[] a, SensorModel s) {
        Action out = new Action();
        boolean curve = isCurve(s);
        boolean straight = isStraight(s);
        out.steering = a[0];
        if (curve) { out.accelerate = 0; out.brake = 1; }
        else if (straight) { out.accelerate = 1; out.brake = 0; }
        else { out.accelerate = a[1]; out.brake = a[2]; }
        out.gear = updateGear(s, out.accelerate, out.brake);
        return out;
    }

    @Override public void reset() { cache.update(null,null); gear = 1; }
    @Override public void shutdown() {}
}