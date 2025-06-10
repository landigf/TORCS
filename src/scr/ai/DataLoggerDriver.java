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
    
    // Soglie RPM ottimizzate per ogni marcia
    private final double[] OPTIMAL_SHIFT_UP_RPM = {6500, 7000, 7200, 7500, 8000, 0};
    private final double[] OPTIMAL_SHIFT_DOWN_RPM = {0, 3000, 3500, 4000, 4500, 5000};
    
    // Soglie velocità per ottimizzazione
    private final double[] MIN_SPEED_FOR_GEAR = {0, 10, 25, 45, 70, 100};

    public DataLoggerDriver() {
        // Apri/crea CSV
        try {
            File f = new File("drive_log.csv");
            log = new PrintWriter(new FileWriter(f, true), true);
            if (f.length() == 0) {
                String header = "time,angle,curLapTime,damage,gear,rpm," +
                    "speedX,lastLapTime," +
                    IntStream.range(0, 19)
                             .mapToObj(i -> "track" + i)
                             .collect(Collectors.joining(",")) +
                    ",trackPos,steer,accel,brake";
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
                    case KeyEvent.VK_A -> leftPressed = true;
                    case KeyEvent.VK_D -> rightPressed = true;
                }
            }
            @Override
            public void keyReleased(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_W -> accelPressed = false;
                    case KeyEvent.VK_S -> brakePressed = false;
                    case KeyEvent.VK_A -> leftPressed = false;
                    case KeyEvent.VK_D -> rightPressed = false;
                }
            }
        });
        frame.setVisible(true);
        frame.setAlwaysOnTop(true);
        frame.requestFocus();
    }

    /** Sistema di cambio marce ottimizzato */
    private int getOptimizedGear(SensorModel s) {
        double rpm = s.getRPM();
        double speed = Math.abs(s.getSpeed() * 3.6); // km/h
        double throttle = accelPressed ? 1.0 : 0.0;
        boolean isBraking = brakePressed;
        long currentTime = System.currentTimeMillis();
        
        // Evita cambi troppo frequenti
        if (currentTime - lastGearChange < GEAR_CHANGE_DELAY) {
            return currentGear;
        }
        
        int targetGear = currentGear;
        
        // Logica di cambio marcia ottimizzata
        if (currentGear < 6) {
            // Condizioni per scalare su
            boolean shouldShiftUp = false;
            
            if (rpm > OPTIMAL_SHIFT_UP_RPM[currentGear - 1]) {
                shouldShiftUp = true;
            }
            
            // Cambio anticipato se si sta accelerando forte
            if (throttle > 0.8 && rpm > (OPTIMAL_SHIFT_UP_RPM[currentGear - 1] * 0.9)) {
                shouldShiftUp = true;
            }
            
            // Verifica che la velocità sia adeguata per la marcia superiore
            if (shouldShiftUp && speed >= MIN_SPEED_FOR_GEAR[currentGear]) {
                targetGear = currentGear + 1;
            }
        }
        
        if (currentGear > 1) {
            // Condizioni per scalare giù
            boolean shouldShiftDown = false;
            
            if (rpm < OPTIMAL_SHIFT_DOWN_RPM[currentGear - 1]) {
                shouldShiftDown = true;
            }
            
            // Cambio anticipato in frenata
            if (isBraking && rpm < (OPTIMAL_SHIFT_DOWN_RPM[currentGear - 1] * 1.2)) {
                shouldShiftDown = true;
            }
            
            // Cambio forzato se velocità troppo bassa per la marcia
            if (speed < MIN_SPEED_FOR_GEAR[currentGear - 1] * 0.8) {
                shouldShiftDown = true;
            }
            
            // Cambio per engine braking in curva
            double trackCurvature = Math.abs(s.getAngleToTrackAxis());
            if (trackCurvature > 0.3 && speed > 60 && !accelPressed) {
                shouldShiftDown = true;
            }
            
            if (shouldShiftDown) {
                targetGear = currentGear - 1;
            }
        }
        
        // Protezione contro over-rev e under-rev
        if (targetGear > currentGear && rpm > 8500) {
            targetGear = currentGear; // Non cambiare se RPM troppo alti
        }
        if (targetGear < currentGear && rpm < 2000) {
            targetGear = currentGear; // Non cambiare se RPM troppo bassi
        }
        
        // Aggiorna stato se cambio effettuato
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

        // 3) Lettura sensori
        double angle       = s.getAngleToTrackAxis();
        double curLapTime  = s.getCurrentLapTime();
        double damage      = s.getDamage();
        double rpm         = s.getRPM();
        double speedX      = s.getSpeed();
        double lastLapTime = s.getLastLapTime();
        double[] track     = s.getTrackEdgeSensors();
        double trackPos    = s.getTrackPosition();
        long   time        = System.currentTimeMillis() - t0;

        // 4) Debug: stampa i valori di input E sensori per verifica
        System.out.println("Input - Steer: " + a.steering + ", Accel: " + a.accelerate + ", Brake: " + a.brake);
        System.out.println("Sensors - Speed: " + speedX + ", RPM: " + rpm + ", Gear: " + a.gear + ", TrackPos: " + trackPos);
        
        // 5) Verifica che i sensori track abbiano 19 elementi
        if (track.length != 19) {
            System.err.println("WARNING: Track sensors length is " + track.length + " instead of 19!");
        }

        // 6) Log CSV con ordine corretto
        StringBuilder sb = new StringBuilder();
        sb.append(time).append(',')
          .append(angle).append(',')
          .append(curLapTime).append(',')
          .append(damage).append(',')
          .append(a.gear).append(',')
          .append(rpm).append(',')
          .append(speedX).append(',')
          .append(lastLapTime);
        for (double d : track) sb.append(',').append(d);
        sb.append(',').append(trackPos)
          .append(',').append(a.steering)
          .append(',').append(a.accelerate)
          .append(',').append(a.brake);
        log.println(sb);
        log.flush(); // Forza la scrittura

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

        // **sterzo invertito**: A→+1, D→-1
        if (leftPressed && !rightPressed)       a.steering = +1.0f;
        else if (rightPressed && !leftPressed)  a.steering = -1.0f;
        else                                    a.steering =  0.0f;
        return a;
    }
}
