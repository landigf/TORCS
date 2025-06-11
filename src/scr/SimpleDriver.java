package scr;

import java.io.Serializable;

/**
 * Driver d’emergenza rule-based.
 * Sterzo proporzionale, 3 soglie di velocità e cambio marcia con bias in curva.
 * Puoi usarlo come fallback quando il KNN va fuori distribuzione.
 */
public class SimpleDriver extends Controller implements Serializable {

    private static final long serialVersionUID = 1L;

    /* --- soglie RPM per cambio marcia --- */
    private static final double[] RPM_UP   = { 7000, 7200, 7300, 7400, 7600, 0 };
    private static final double[] RPM_DOWN = { 0,    2800, 3000, 3200, 3500, 0 };

    /* angoli riferimento (radianti) */
    private static final double ANG_SHARP  = 0.35;   // ≈ 20°
    private static final double ANG_MEDIUM = 0.20;   // ≈ 11°
    private static final double ANG_BIAS   = 0.10;   // ≈ 6°

    /** Controllo principale */
    @Override
    public Action control(SensorModel s) {
        Action a = new Action();

        /* -------- sterzo proporzionale -------- */
        a.steering = clip(s.getAngleToTrackAxis() * 0.5, -1, 1);

        /* -------- target speed a soglie -------- */
        double absA  = Math.abs(s.getAngleToTrackAxis());
        double target;
        if      (absA > ANG_SHARP)  target =  60;
        else if (absA > ANG_MEDIUM) target = 110;
        else                        target = 160;

        if (s.getSpeed() < target) {
            a.accelerate = 1.0;
            a.brake      = 0.0;
        } else {
            a.accelerate = 0.0;
            a.brake      = clip((s.getSpeed() - target) / 40.0, 0, 1);
        }

        /* -------- cambio marcia -------- */
        a.gear = chooseGear(s);

        return a;
    }

    /* logica marce con anticipo in curva/lento */
    private int chooseGear(SensorModel s) {
        int    g   = s.getGear();
        double rpm = s.getRPM();
        double v   = s.getSpeed();
        double aa  = Math.abs(s.getAngleToTrackAxis());

        if (g < 1) return 1;                       // gestisci N / R

        boolean inCorner = aa > ANG_BIAS || v < 55;
        if (inCorner && g > 2) return g - 1;       // scala in anticipo

        if (g < 6 && rpm >= RPM_UP[g - 1])   return g + 1;
        if (g > 1 && rpm <= RPM_DOWN[g - 1]) return g - 1;

        return g;
    }

    /* clip utility */
    private static double clip(double val, double lo, double hi) {
        return Math.max(lo, Math.min(hi, val));
    }

    /* requisiti dell’interfaccia Controller */
    @Override public void reset()    {}
    @Override public void shutdown() {}
}
