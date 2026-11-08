package frc.probe;

import org.wpilib.framework.RobotBase;
import org.wpilib.framework.TimedRobot;
import org.wpilib.hardware.bus.CANPort;
import org.wpilib.hardware.discrete.AnalogInput;
import org.wpilib.hardware.discrete.DigitalInput;
import org.wpilib.hardware.discrete.PWM;
import org.wpilib.hardware.imu.OnboardIMU;
import org.wpilib.hardware.led.AddressableLED;
import org.wpilib.hardware.led.AddressableLEDBuffer;
import org.wpilib.hardware.pneumatic.PneumaticsModuleType;
import org.wpilib.hardware.pneumatic.Solenoid;
import org.wpilib.hardware.power.PowerDistribution;
import org.wpilib.hardware.rotation.Encoder;
import org.wpilib.system.Notifier;
import org.wpilib.system.RobotController;

public final class Probe extends TimedRobot {
  int periodic;
  int disabled;
  int teleop;
  int notifier;
  PWM pwm = new PWM(0);
  DigitalInput dio = new DigitalInput(1);
  AnalogInput ai = new AnalogInput(0);
  Encoder enc = new Encoder(2, 3);
  OnboardIMU imu = new OnboardIMU(OnboardIMU.MountOrientation.FLAT);
  AddressableLED led = new AddressableLED(0);
  AddressableLEDBuffer buf = new AddressableLEDBuffer(4);
  Solenoid sol = new Solenoid(CANPort.CAN_S0, PneumaticsModuleType.CTRE_PCM, 1);
  PowerDistribution pd = new PowerDistribution(CANPort.CAN_S0);
  Notifier n = new Notifier(() -> notifier++);

  public Probe() {
    n.startPeriodic(0.05);
    led.setLength(4);
  }

  @Override
  public void robotPeriodic() {
    periodic++;
    pwm.setPulseTimeMicroseconds(1500 + periodic % 100);
    led.setData(buf);
    if (periodic % 50 == 0) {
      System.out.println("PROBE periodic=" + periodic + " disabled=" + disabled + " teleop=" + teleop
          + " notifier=" + notifier + " dio=" + dio.get() + " ai=" + ai.getVoltage() + " enc=" + enc.get()
          + " yaw=" + imu.getYawRadians() + " pdV=" + pd.getVoltage() + " vin=" + RobotController.getBatteryVoltage()
          + " now=" + RobotController.getTime() + " enabled=" + isEnabled());
    }
  }

  @Override
  public void disabledPeriodic() {
    disabled++;
  }

  @Override
  public void teleopPeriodic() {
    teleop++;
    sol.set(teleop % 2 == 0);
  }

  public static void main(String[] args) {
    RobotBase.startRobot(Probe::new);
  }
}
