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

    // Stato tasti
    private boolean leftPressed = false;
    private boolean rightPressed = false;
    private boolean accelPressed = false;
    private boolean brakePressed = false;

    // Sistema cambio ottimizzato
    private int currentGear = 1;
    private long lastGearChange = 0;
    private final long GEAR_CHANGE_DELAY = 200; // ms tra cambi

    // Parametri motore dinamici
    private final double REDLINE_RPM = 8500;
    private final double IDLE_RPM    = 1000;

    public DataLoggerDriver() {
        // Apri/crea CSV
        try {
            File f = new File("drive_log.csv");
            log = new PrintWriter(new FileWriter(f, true), true);
            if (f.length() == 0) {
                String header = "time,angle,curLapTime,damage,gear,rpm," +
                    "speedX,speedY,lastLapTime," +
                    IntStream.range(0, 19)
                             .mapToObj(i -> "track" + i)
                             .collect(Collectors.joining(",")) +
                    ",wheelSpinVel0,wheelSpinVel1,wheelSpinVel2,wheelSpinVel3," +
                    "trackPos,steer,accel,brake";
                log.println(header);
            }
        } catch (IOException e) {
            throw new RuntimeException("Cannot open drive_log.csv", e);
        }

        // Finestra per input da tastiera
        JFrame frame = new JFrame("TORCS Manual Control");
        frame.setSize(200, 200);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setFocusable(true);
        frame.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_W -> accelPressed = true;
                    case KeyEvent.VK_S -> brakePressed = true;
                    case KeyEvent.VK_A -> leftPressed  = true;
                    case KeyEvent.VK_D -> rightPressed = true;
                }
            }
            @Override
            public void keyReleased(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_W -> accelPressed = false;
                    case KeyEvent.VK_S -> brakePressed = false;
                    case KeyEvent.VK_A -> leftPressed  = false;
                    case KeyEvent.VK_D -> rightPressed = false;
                }
            }
        });
        frame.setVisible(true);
        frame.setAlwaysOnTop(true);
        frame.requestFocus();
    }

    /** Calcola un indice medio di curvatura dal sensore track */
    private double computeCurvature(double[] track) {
        double sum = 0;
        for (int i = 1; i < track.length; i++) {
            sum += Math.abs(track[i] - track[i - 1]);
        }
        return sum / (track.length - 1);
    }

    /** Sistema di cambio marce dinamico */
    private int getOptimizedGear(SensorModel s) {
        double rpm   = s.getRPM();
        double speed = Math.abs(s.getSpeed() * 3.6); // km/h
        double[] track   = s.getTrackEdgeSensors();
        boolean onStraight = computeCurvature(track) < 0.05;
        double throttle   = accelPressed ? 1.0 : 0.0;
        boolean isBraking = brakePressed;
        long currentTime  = System.currentTimeMillis();

        // Evita cambi troppo frequenti
        if (currentTime - lastGearChange < GEAR_CHANGE_DELAY) {
            return currentGear;
        }

        int targetGear = currentGear;
        // Calcola soglie dinamiche
        double shiftUpRPM   = onStraight ? REDLINE_RPM * 0.95 : REDLINE_RPM * 0.90;
        double shiftDownRPM = onStraight ? IDLE_RPM * 2      : IDLE_RPM * 3;

        // --- Logica salita marcia ---
        if (currentGear < 6) {
            boolean doUp = false;
            // Superamento soglia
            if (rpm > shiftUpRPM) doUp = true;
            // Anticipo in forte accelerazione
            if (throttle > 0.8 && rpm > shiftUpRPM * 0.9) doUp = true;
            if (doUp) {
                targetGear = currentGear + 1;
            }
        }
        // --- Logica scalata marcia ---
        if (currentGear > 1) {
            boolean doDown = false;
            // Sotto soglia
            if (rpm < shiftDownRPM) doDown = true;
            // In frenata
            if (isBraking && rpm < shiftDownRPM * 1.2) doDown = true;
            // Curvatura elevata
            if (!onStraight && speed > 40 && !accelPressed) doDown = true;
            if (doDown) {
                targetGear = currentGear - 1;
            }
        }

        // Protezione over/under rev
        if (targetGear > currentGear && rpm > REDLINE_RPM) {
            targetGear = currentGear;
        }
        if (targetGear < currentGear && rpm < IDLE_RPM) {
            targetGear = currentGear;
        }

        // Applica cambio
        if (targetGear != currentGear) {
            lastGearChange = currentTime;
            currentGear = targetGear;
        }
        return targetGear;
    }

    @Override
    public Action control(SensorModel s) {
        // 1) Input umano
        Action a = readHumanInput();
        if (a == null) a = new Action();

        // 2) Cambio marcia ottimizzato
        a.gear = getOptimizedGear(s);

        // 3) Lettura sensori per log
        double angle       = s.getAngleToTrackAxis();
        double curLapTime  = s.getCurrentLapTime();
        double damage      = s.getDamage();
        double rpm         = s.getRPM();
        double speedX      = s.getSpeed();
        double speedY      = s.getLateralSpeed(); // Velocità laterale
        double lastLapTime = s.getLastLapTime();
        double[] trackArr  = s.getTrackEdgeSensors();
        double[] wheelSpin = s.getWheelSpinVelocity(); // Velocità rotazione ruote
        double trackPos    = s.getTrackPosition();
        long   time        = System.currentTimeMillis() - t0;

        // 4) Debug console
        System.out.println("Steer: " + a.steering + ", Accel: " + a.accelerate + ", Brake: " + a.brake);
        System.out.println("Speed: " + speedX + ", SpeedY: " + speedY + ", RPM: " + rpm + ", Gear: " + a.gear);

        // 5) Verifica sensori
        if (trackArr.length != 19) {
            System.err.println("WARNING: Track sensors length is " + trackArr.length + " instead of 19!");
        }
        if (wheelSpin.length != 4) {
            System.err.println("WARNING: Wheel spin sensors length is " + wheelSpin.length + " instead of 4!");
        }

        // 6) Log CSV
        StringBuilder sb = new StringBuilder();
        sb.append(time).append(',')
          .append(angle).append(',')
          .append(curLapTime).append(',')
          .append(damage).append(',')
          .append(a.gear).append(',')
          .append(rpm).append(',')
          .append(speedX).append(',')
          .append(speedY).append(',')
          .append(lastLapTime);
        
        // 19 sensori track
        for (double d : trackArr) sb.append(',').append(d);
        
        // 4 velocità ruote
        for (double w : wheelSpin) sb.append(',').append(w);
        
        sb.append(',').append(trackPos)
          .append(',').append(a.steering)
          .append(',').append(a.accelerate)
          .append(',').append(a.brake);
        
        log.println(sb);
        log.flush();

        return a;
    }

    @Override
    public void reset() {
        t0 = System.currentTimeMillis();
    }

    @Override
    public void shutdown() {
        log.close();
    }

    /** Mappa WASD su sterzo/freno/gas (con sterzo invertito) */
    private Action readHumanInput() {
        Action a = new Action();
        if (accelPressed) a.accelerate = 1.0f;
        if (brakePressed) a.brake      = 1.0f;
        if (leftPressed && !rightPressed)       a.steering = +1.0f;
        else if (rightPressed && !leftPressed)  a.steering = -1.0f;
        else                                    a.steering = 0.0f;
        return a;
    }
}
