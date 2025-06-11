package scr.ai;

import scr.SensorModel;

public class SimpleGear {
    /* ---------- cambio marcia ---------- */
    private static final double[] GEAR_RATIO = { 0, 3.60, 2.19, 1.59, 1.29, 1.05, 0.88 };

    /* soglie up / down a isteresi larga */
    private static final double[] RPM_UP   = { 7200, 7400, 7600, 7800, 8000, 99999 };
    private static final double[] RPM_DOWN = { 0,    2600, 3000, 3200, 3500, 4000 };

    private static final double ANG_CURVE = 0.12;               // 7°
    private static final long   SHIFT_DEAD_NS = 250_000_000;     // 0,25 s
    private int  lastGear = 1;
    private long lastShift = 0;
    private double lastSpeed = 0;

    /**
     * Crea un cambio marcia semplice
     */
    public SimpleGear() {}
    
    /**
     * Restituisce la marcia ottimale sulla base di RPM e soglie
    */
    public int chooseGear(SensorModel s) {
        int    g   = s.getGear();
        double rpm = s.getRPM();
        double v   = s.getSpeed();
        double aa  = Math.abs(s.getAngleToTrackAxis());

        if (g < 1) return 1;

        long now = System.nanoTime();
        double decel = lastSpeed - v;                 // km/h persi nell’ultimo tick
        if (g == 6 && decel >= 1) {                   // soglia frenata
            lastShift = now;
            lastGear  = g - 1;
            lastSpeed = v;
            return lastGear;
        }

        
        if (now - lastShift < SHIFT_DEAD_NS) {        // isteresi tempo
            lastSpeed = v;
            return lastGear;
        }


        /* ---------- upshift standard ---------- */
        if (g < 6 && rpm >= RPM_UP[g - 1]) {
            double rpmAfterUp = rpm * (GEAR_RATIO[g] / GEAR_RATIO[g + 1]);
            if (rpmAfterUp >= 3000) {
                lastShift = now;
                lastGear  = g + 1;
                lastSpeed = v;
                return lastGear;
            }
        }

        /* ---------- downshift standard ---------- */
        boolean needDown =
            rpm <= RPM_DOWN[g - 1] || (aa > ANG_CURVE && rpm < 5500);

        if (needDown && g > 1) {
            double rpmAfterDown = rpm * (GEAR_RATIO[g] / GEAR_RATIO[g - 1]);
            if (rpmAfterDown <= 9000) {
                lastShift = now;
                lastGear  = g - 1;
                lastSpeed = v;
                return lastGear;
            }
        }

        /* nessun cambio */
        lastSpeed = v;
        lastGear  = g;
        return g;
    }
}
