package scr;

import java.io.Serializable;
//import scr.ai.SimpleGear;

/**
 * SimpleDriver - versione 2025-06-11 con cambio marcia evoluto.
 * <p>
 * Integriamo l’algoritmo di shifting suggerito dall’utente:
 * - isteresi ampia su RPM
 * - frenata automatica/up-shift ritardato, down-shift anticipato in curva
 * - dead-time di 0,25 s per evitare rimbalzi di marcia
 *
 * Il resto della logica (sterzo proporzionale, fuzzy-speed su track sensors,
 * ABS, gestione stuck, clutch) resta invariato.
 */
public class SimpleDriver extends Controller implements Serializable {

    private static final long serialVersionUID = 1L;
    /* ===== cambio marcia evoluto ===== */
    //SimpleGear gearChanger = new SimpleGear();

    /* ===== costanti veicolo / pista ===== */
    // sterzo
    private static final float STEER_LOCK = 0.785398f;          // 45° total lock in rad
    private static final float STEER_SENS_OFFSET = 80f;         // km/h
    private static final float WHEEL_SENS_COEFF = 1f;

    // target-speed / curva
    private static final float MAX_SPEED_DIST = 70f;            // m: oltre questo rettilineo = full gas
    private static final float MAX_SPEED      = 180f;           // km/h
    private static final float SIN5 = 0.08716f;                 // sin(5°)
    private static final float COS5 = 0.99619f;                 // cos(5°)

    // stuck-logic
    private static final int   STUCK_TIME  = 25;                // ticks
    private static final float STUCK_ANGLE = 0.523598775f;      // 30° rad

    // ABS
    private static final float[] WHEEL_RADIUS = { 0.3179f, 0.3179f, 0.3276f, 0.3276f };
    private static final float ABS_SLIP       = 2.0f;
    private static final float ABS_RANGE      = 3.0f;
    private static final float ABS_MIN_SPEED  = 3.0f;           // m/s

    // clutch
    private static final float CLUTCH_MAX          = 0.5f;
    private static final float CLUTCH_DELTA        = 0.05f;
    private static final float CLUTCH_DELTA_TIME   = 0.02f;
    private static final float CLUTCH_DELTA_RACED  = 10f;
    private static final float CLUTCH_DEC          = 0.01f;
    private static final float CLUTCH_MAX_MODIFIER = 1.3f;
    private static final float CLUTCH_MAX_TIME     = 1.5f;

    /* ===== cambio marcia evoluto ===== */
    private static final double[] GEAR_RATIO = { 0, 3.60, 2.19, 1.59, 1.29, 1.05, 0.88 };
    private static final double[] RPM_UP     = { 7200, 7400, 7600, 7800, 8000, 99999 };
    private static final double[] RPM_DOWN   = { 0,    2600, 3000, 3200, 3500, 4000 };
    private static final double   ANG_CURVE  = 0.12;            // rad (≈7°)
    private static final long     SHIFT_DEAD_NS = 250_000_000L; // 0.25 s

    private int   lastGear  = 1;
    private long  lastShift = 0L;
    private double lastSpeed = 0.0;

    /* ===== variabili di stato ===== */
    private int   stuck  = 0;
    private float clutch = 0;

    /* ===== metodi principali ===== */
    @Override
    public void reset() {
        System.out.println("Restarting the race!");
    }

    @Override
    public void shutdown() {
        System.out.println("Bye bye!");
    }

    @Override
    public Action control(SensorModel sensors) {
        /* ---------- gestione stuck ---------- */
        if (Math.abs(sensors.getAngleToTrackAxis()) > STUCK_ANGLE) stuck++; else stuck = 0;

        if (stuck > STUCK_TIME) {
            return recoverFromStuck(sensors);
        }

        /* ---------- logica normale ---------- */
        float accelAndBrake = getAccel(sensors);
        //int   gear          = gearChanger.chooseGear(sensors);
        int   gear          = chooseGear(sensors);
        float steer         = getSteer(sensors);

        // normalizza sterzo
        steer = Math.max(-1f, Math.min(1f, steer));

        float accel, brake;
        if (accelAndBrake > 0) {
            accel = accelAndBrake;
            brake = 0;
        } else {
            accel = 0;
            brake = filterABS(sensors, -accelAndBrake);
        }
        clutch = clutching(sensors, clutch);

        Action action = new Action();
        action.gear       = gear;
        action.steering   = steer;
        action.accelerate = accel;
        action.brake      = brake;
        action.clutch     = clutch;
        return action;
    }

    /* ===== sterzo proporzionale ===== */
    private float getSteer(SensorModel sensors) {
        float targetAngle = (float) (sensors.getAngleToTrackAxis() - sensors.getTrackPosition() * 0.5);
        if (sensors.getSpeed() > STEER_SENS_OFFSET) {
            return (float) (targetAngle / (STEER_LOCK * (sensors.getSpeed() - STEER_SENS_OFFSET) * WHEEL_SENS_COEFF));
        }
        return targetAngle / STEER_LOCK;
    }

    /* ===== target-speed fuzzy + "turbo" on straights ===== */
    private float getAccel(SensorModel sensors) {
        if (sensors.getTrackPosition() > -1 && sensors.getTrackPosition() < 1) {
            float center = (float) sensors.getTrackEdgeSensors()[9];
            float right  = (float) sensors.getTrackEdgeSensors()[10];
            float left   = (float) sensors.getTrackEdgeSensors()[8];

            // turbo: se vado piano (<120) e ho >120 m liberi, gas pieno
            if (sensors.getSpeed() < 120 && center > 120) return 1.0f;

            float targetSpeed;
            if (center > MAX_SPEED_DIST || (center >= right && center >= left)) {
                targetSpeed = MAX_SPEED;
            } else {
                boolean rightTurn = right > left;
                float  h = center * SIN5;
                float  b = (rightTurn ? right : left) - center * COS5;
                float  sinAngle = (b * b) / (h * h + b * b);
                targetSpeed = MAX_SPEED * (center * sinAngle / MAX_SPEED_DIST);
            }
            return (float) (2 / (1 + Math.exp(sensors.getSpeed() - targetSpeed)) - 1);
        }
        return 0.3f; // off-track recovery accel
    }

    /* ===== cambio marcia suggerito ===== */
    private int chooseGear(SensorModel s) {
        int    g   = s.getGear();
        double rpm = s.getRPM();
        double v   = s.getSpeed();
        double aa  = Math.abs(s.getAngleToTrackAxis());

        if (g < 1) return 1;

        long now = System.nanoTime();
        double decel = lastSpeed - v;  // km/h persi nell’ultimo tick
        if (g == 6 && decel >= 1) {    // frenata sensibile
            lastShift = now;
            lastGear  = g - 1;
            lastSpeed = v;
            return lastGear;
        }

        if (now - lastShift < SHIFT_DEAD_NS) {
            lastSpeed = v;
            return lastGear;
        }

        // up-shift
        if (g < 6 && rpm >= RPM_UP[g - 1]) {
            double rpmAfterUp = rpm * (GEAR_RATIO[g] / GEAR_RATIO[g + 1]);
            if (rpmAfterUp >= 3000) {
                lastShift = now;
                lastGear  = g + 1;
                lastSpeed = v;
                return lastGear;
            }
        }

        // down-shift
        boolean needDown = rpm <= RPM_DOWN[g - 1] || (aa > ANG_CURVE && rpm < 5500);
        if (needDown && g > 1) {
            double rpmAfterDown = rpm * (GEAR_RATIO[g] / GEAR_RATIO[g - 1]);
            if (rpmAfterDown <= 9000) {
                lastShift = now;
                lastGear  = g - 1;
                lastSpeed = v;
                return lastGear;
            }
        }

        // nessun cambio
        lastSpeed = v;
        lastGear  = g;
        return g;
    }

    /* ===== gestione recupero da stuck ===== */
    private Action recoverFromStuck(SensorModel sensors) {
        float steer = - (float) sensors.getAngleToTrackAxis() / STEER_LOCK;
        int   gear  = -1; // retromarcia
        if (sensors.getAngleToTrackAxis() * sensors.getTrackPosition() > 0) {
            gear  = 1;
            steer = -steer;
        }
        clutch = clutching(sensors, clutch);
        Action a = new Action();
        a.gear       = gear;
        a.steering   = steer;
        a.accelerate = 1.0f;
        a.brake      = 0;
        a.clutch     = clutch;
        return a;
    }

    /* ===== ABS ===== */
    private float filterABS(SensorModel sensors, float brake) {
        float speed = (float) (sensors.getSpeed() / 3.6); // m/s
        if (speed < ABS_MIN_SPEED) return brake;

        float slip = 0f;
        for (int i = 0; i < 4; i++) {
            slip += sensors.getWheelSpinVelocity()[i] * WHEEL_RADIUS[i];
        }
        slip = speed - slip / 4.0f;
        if (slip > ABS_SLIP) {
            brake -= (slip - ABS_SLIP) / ABS_RANGE;
        }
        return Math.max(0, brake);
    }

    /* ===== clutch helper ===== */
    private float clutching(SensorModel sensors, float c) {
        float maxClutch = CLUTCH_MAX;
        if (sensors.getCurrentLapTime() < CLUTCH_DELTA_TIME && getStage() == Stage.RACE
                && sensors.getDistanceRaced() < CLUTCH_DELTA_RACED)
            c = maxClutch;

        if (c > 0) {
            double delta = CLUTCH_DELTA;
            if (sensors.getGear() < 2) {
                delta /= 2;
                maxClutch *= CLUTCH_MAX_MODIFIER;
                if (sensors.getCurrentLapTime() < CLUTCH_MAX_TIME) c = maxClutch;
            }
            c = Math.min(maxClutch, c);
            if (c != maxClutch) {
                c -= delta;
                c = Math.max(0f, c);
            } else {
                c -= CLUTCH_DEC;
            }
        }
        return c;
    }

    /* ===== inizializzazione angoli sensori ===== */
    public float[] initAngles() {
        float[] a = new float[19];
        for (int i = 0; i < 5; i++) { a[i] = -90 + i * 15; a[18 - i] = 90 - i * 15; }
        for (int i = 5; i < 9; i++) { a[i] = -20 + (i - 5) * 5; a[18 - i] = 20 - (i - 5) * 5; }
        a[9] = 0;
        return a;
    }
}
