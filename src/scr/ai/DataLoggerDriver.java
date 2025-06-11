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

    private SimpleGear gearChanger = new SimpleGear();

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


    @Override
    public Action control(SensorModel s) {
        // 1) Input umano
        Action a = readHumanInput();
        if (a == null) a = new Action();

        // 2) Cambio marcia automatico
        a.gear = gearChanger.chooseGear(s);

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