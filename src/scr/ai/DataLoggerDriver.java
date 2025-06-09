package scr.ai;

import scr.SimpleDriver;
import scr.SensorModel;
import scr.Action;

import java.io.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.awt.event.*;
import javax.swing.*;

public class DataLoggerDriver extends SimpleDriver {
    private PrintWriter log;
    private long t0;

    // Stato tasti
    private boolean leftPressed = false;
    private boolean rightPressed = false;
    private boolean accelPressed = false;
    private boolean brakePressed = false;

    // Soglie cambio marcia (da SimpleDriver)
    private final int[] gearUp   = {5000, 6000, 6000, 6500, 7000,    0};
    private final int[] gearDown = {   0, 2500, 3000, 3000, 3500, 3500};

    public DataLoggerDriver() {
        // Apri/crea CSV
        try {
            File f = new File("drive_log.csv");
            log = new PrintWriter(new FileWriter(f, true), true);
            if (f.length() == 0) {
                String header = "time,angle,curLapTime,damage,fuel,gear,rpm," +
                    "speedX,speedY,speedZ,lastLapTime," +
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

    @Override
    public Action control(SensorModel s) {
        // 1) Input umano
        Action a = readHumanInput();
        if (a == null) a = new Action();

        // 2) Cambio marcia automatico
        a.gear = getAutomaticGear(s);

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

        // 4) Log CSV (includo a.gear)
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

    /** Algoritmo automatico di cambio marcia basato su RPM */
    private int getAutomaticGear(SensorModel s) {
        int g = s.getGear();
        double rpm = s.getRPM();
        if (g < 1) g = 1;
        if (g < 6 && rpm >= gearUp[g - 1]) {
            return g + 1;
        } else if (g > 1 && rpm <= gearDown[g - 1]) {
            return g - 1;
        }
        return g;
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
