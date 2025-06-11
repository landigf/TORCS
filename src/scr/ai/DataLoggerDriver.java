package scr.ai;

import java.awt.event.*;
import java.io.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.swing.*;
import scr.Action;
import scr.SensorModel;
import scr.SimpleDriver;

public class DataLoggerDriver extends SimpleDriver {
    private PrintWriter log;
    private long t0;

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

    // Stato tasti
    private boolean leftPressed  = false;
    private boolean rightPressed = false;
    private boolean accelPressed = false;
    private boolean brakePressed = false;

    public DataLoggerDriver() {
        // Apri/crea CSV
        try {
            File f = new File("drive_log.csv");
            log = new PrintWriter(new FileWriter(f, true), true);
            if (f.length() == 0) {
                String header = "time,angle,curLapTime,damage,gear,rpm,speedX,speedY,lastLapTime," +
                                IntStream.range(0,19).mapToObj(i->"track"+i)
                                         .collect(Collectors.joining(",")) +
                                ",wheel0,wheel1,wheel2,wheel3,trackPos,steer,accel,brake";
                log.println(header);
            }
        } catch (IOException e) {
            throw new RuntimeException("Cannot open drive_log.csv", e);
        }

        // Finestra per input da tastiera
        JFrame frame = new JFrame("TORCS Manual Control");
        frame.setSize(200,200);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setFocusable(true);
        frame.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                switch(e.getKeyCode()){
                    case KeyEvent.VK_W -> accelPressed  = true;
                    case KeyEvent.VK_S -> brakePressed  = true;
                    case KeyEvent.VK_A -> leftPressed   = true;
                    case KeyEvent.VK_D -> rightPressed  = true;
                }
            }
            @Override public void keyReleased(KeyEvent e) {
                switch(e.getKeyCode()){
                    case KeyEvent.VK_W -> accelPressed  = false;
                    case KeyEvent.VK_S -> brakePressed  = false;
                    case KeyEvent.VK_A -> leftPressed   = false;
                    case KeyEvent.VK_D -> rightPressed  = false;
                }
            }
        });
        frame.setVisible(true);
        frame.setAlwaysOnTop(true);
        frame.requestFocus();
    }

    /**
     * Restituisce la marcia ottimale sulla base di RPM e soglie
    */
    private int chooseGear(SensorModel s) {
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

    @Override
    public Action control(SensorModel s) {
        // 1) Input umano
        Action a = readHumanInput();
        if (a == null) a = new Action();

        // 2) Cambio marcia automatico
        a.gear = chooseGear(s);

        // 3) Raccolta dati sensori
        double angle       = s.getAngleToTrackAxis();
        double curLapTime  = s.getCurrentLapTime();
        double damage      = s.getDamage();
        double rpm         = s.getRPM();
        double speedX      = s.getSpeed();
        double speedY      = s.getLateralSpeed();
        double lastLapTime = s.getLastLapTime();
        double[] trackArr  = s.getTrackEdgeSensors();
        double[] wheelSpin = s.getWheelSpinVelocity();
        double trackPos    = s.getTrackPosition();
        long   time        = System.currentTimeMillis() - t0;

        // 4) Debug console
        System.out.println("Steer:"+a.steering+" Accel:"+a.accelerate+" Brake:"+a.brake);
        System.out.println("SpeedX:"+speedX+" RPM:"+rpm+" Gear:"+a.gear);

        // 5) Log CSV
        StringBuilder sb = new StringBuilder();
        sb.append(time).append(',').append(angle).append(',')
          .append(curLapTime).append(',').append(damage).append(',')
          .append(a.gear).append(',').append(rpm).append(',')
          .append(speedX).append(',').append(speedY).append(',')
          .append(lastLapTime);
        for (double d : trackArr) sb.append(',').append(d);
        for (double w : wheelSpin) sb.append(',').append(w);
        sb.append(',').append(trackPos)
          .append(',').append(a.steering)
          .append(',').append(a.accelerate)
          .append(',').append(a.brake);
        log.println(sb.toString());
        log.flush();

        return a;
    }

    @Override public void reset() {
        t0 = System.currentTimeMillis();
    }

    @Override public void shutdown() {
        log.close();
    }

    /**
     * Mappa WASD su sterzo/freno/gas
     */
    private Action readHumanInput() {
        Action a = new Action();
        if (accelPressed) a.accelerate = 1.0f;
        if (brakePressed) a.brake      = 1.0f;
        if (leftPressed && !rightPressed)       a.steering =  1.0f;
        else if (rightPressed && !leftPressed)  a.steering = -1.0f;
        else                                    a.steering =  0.0f;
        return a;
    }
}