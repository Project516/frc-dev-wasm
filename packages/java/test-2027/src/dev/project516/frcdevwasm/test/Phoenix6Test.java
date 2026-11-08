package dev.project516.frcdevwasm.test;

/** Phoenix 6 on the shim: devices construct, signals report OK and mirror sim state inputs. */
public final class Phoenix6Test {
  private Phoenix6Test() {}

  private static final String PROBE =
      """
      package probe;

      import com.ctre.phoenix6.CANBus;
      import com.ctre.phoenix6.StatusCode;
      import com.ctre.phoenix6.Utils;
      import com.ctre.phoenix6.configs.TalonFXConfiguration;
      import com.ctre.phoenix6.controls.DutyCycleOut;
      import com.ctre.phoenix6.hardware.CANcoder;
      import com.ctre.phoenix6.hardware.Pigeon2;
      import com.ctre.phoenix6.hardware.TalonFX;

      public final class Probe {
        public static String run() {
          StringBuilder out = new StringBuilder();
          out.append("sim=").append(Utils.isSimulation()).append('\\n');

          Pigeon2 pigeon = new Pigeon2(3, new CANBus("rio"));
          pigeon.getSimState().setRawYaw(30);
          pigeon.getSimState().addYaw(15);
          pigeon.getSimState().setAngularVelocityZ(90);
          out.append("yaw=").append(pigeon.getYaw().refresh().getValueAsDouble()).append('\\n');
          out.append("yawRate=").append(pigeon.getAngularVelocityZWorld().refresh().getValueAsDouble()).append('\\n');
          out.append("yawStatus=").append(pigeon.getYaw().refresh().getStatus()).append('\\n');

          CANcoder cancoder = new CANcoder(4, new CANBus("rio"));
          cancoder.getSimState().setRawPosition(0.25);
          cancoder.getSimState().setVelocity(2);
          out.append("cancoder=").append(cancoder.getPosition().refresh().getValueAsDouble()).append('\\n');
          out.append("cancoderVelocity=").append(cancoder.getVelocity().refresh().getValueAsDouble()).append('\\n');

          TalonFX talon = new TalonFX(5, new CANBus("rio"));
          talon.getSimState().setRawRotorPosition(2.0);
          talon.getSimState().addRotorPosition(0.5);
          out.append("rotor=").append(talon.getRotorPosition().refresh().getValueAsDouble()).append('\\n');
          out.append("other=").append(talon.getSupplyCurrent().refresh().getValueAsDouble()).append('\\n');
          out.append("apply=").append(talon.getConfigurator().apply(new TalonFXConfiguration())).append('\\n');
          out.append("control=").append(talon.setControl(new DutyCycleOut(0.5))).append('\\n');

          TalonFX other = new TalonFX(6, new CANBus("rio"));
          out.append("distinct=").append(other.getRotorPosition().refresh().getValueAsDouble()).append('\\n');
          try {
            new TalonFX(70, new CANBus("rio"));
            out.append("badId=accepted\\n");
          } catch (IllegalArgumentException e) {
            out.append("badId=rejected\\n");
          }
          out.append("ok=").append(StatusCode.OK).append('\\n');
          return out.toString();
        }
      }
      """;

  /** The JNI release and the java.lang.foreign release of the 2027 alphas. */
  private static final String[] VERSIONS = {"26.50", "26.70"};

  public static void run(Tests t) {
    for (String version : VERSIONS) {
      t.test("Phoenix 6 " + version + " devices construct and signals mirror sim state inputs", () -> {
        String result = VendorProbe.run("com/ctre/phoenix6/wpiapi-java", version, PROBE);
        if (result == null) {
          System.out.println("skipped: no Phoenix 6 " + version + " downloaded");
          return;
        }
        VendorProbe.expectLines(
            result, "sim=true", "yaw=45.0", "yawRate=90.0", "yawStatus=OK", "cancoder=0.25", "cancoderVelocity=2.0",
            "rotor=2.5", "other=0.0", "apply=OK", "control=OK", "distinct=0.0", "badId=rejected");
      });
    }
  }
}
