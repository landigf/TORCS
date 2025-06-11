package scr.ai;

import java.io.*;
import java.util.*;
import scr.Action;
import scr.SensorModel;
import scr.SimpleDriver;

public class KNNDriver extends SimpleDriver {
    private KDTree tree;
    private final ActionCache cache = new ActionCache();
    private final int k = 6; // Numero di vicini da considerare
    
    // Configurazione delle feature (deve corrispondere al modello)
    private String[] featureConfig = DatasetBuilder.CONFIG_WITH_SENSORS;

    /* Costanti di cambio marcia */
    private final int[] gearUp   = {5000, 6000, 6000, 6500, 7000, 0};
    private final int[] gearDown = {   0, 2500, 3000, 3000, 3500, 3500};

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
        double[] features = new double[featureConfig.length];
        
        for (int i = 0; i < featureConfig.length; i++) {
            String feature = featureConfig[i];
            switch (feature) {
                // Stessa normalizzazione del DatasetBuilder
                case "angle" -> features[i] = s.getAngleToTrackAxis();
                case "curLapTime" -> features[i] = (s.getCurrentLapTime() - 22) / 15;
                case "speedX" -> features[i] = (s.getSpeed() - 140) / 50;
                case "speedY" -> features[i] = s.getLateralSpeed() / 50;
                case "trackPos" -> features[i] = s.getTrackPosition();
                case "gear" -> features[i] = (s.getGear() - 1) / 5.0;
                case "rpm" -> features[i] = s.getRPM() / 10000.0;
                case "damage" -> features[i] = s.getDamage();
                case "lastLapTime" -> features[i] = s.getLastLapTime() / 60.0;
                default -> {
                    if (feature.startsWith("track")) {
                        int idx = Integer.parseInt(feature.substring(5));
                        features[i] = s.getTrackEdgeSensors()[idx] / 200.0;
                    } else if (feature.startsWith("wheel")) {
                        int idx = Integer.parseInt(feature.substring(5));
                        features[i] = s.getWheelSpinVelocity()[idx] / 200.0;
                    }
                }
            }
        }
        return features;
    }
    
    /**
     * Restituisce la marcia ottimale sulla base di RPM e soglie
     */
    private int getGear(SensorModel sensors) {
        int gear = sensors.getGear();
        double rpm = sensors.getRPM();

        // Se la marcia è N o R, impostiamo 1
        if (gear < 1) {
            return 1;
        }
        // Up-shift
        if (gear < 6 && rpm >= gearUp[gear - 1]) {
            return gear + 1;
        }
        // Down-shift
        if (gear > 1 && rpm <= gearDown[gear - 1]) {
            return gear - 1;
        }
        // Mantieni
        return gear;
    }

    private Action buildAction(double[] a, SensorModel s) {
        Action out = new Action();
    
        // Usa sempre le azioni predette dal KNN
        out.steering = a[0];
        out.accelerate = a[1];
        out.brake = a[2];
        
        // Usa le regole ottimizzate per il cambio marcia
        out.gear = getGear(s);
        
        return out;
    }

    @Override public void reset() { 
        cache.update(null, null); 
    }
    
    @Override public void shutdown() {}
}