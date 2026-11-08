package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim;

import org.wpilib.math.trajectory.ExponentialProfile;
import org.wpilib.math.trajectory.TrapezoidProfile;
import dev.project516.frcdevwasm.sim.DriverStationData;

/**
 * A Talon FX. It emulates the output stage of the firmware: control requests, closed loops with
 * slot gains, Motion Magic profiles, gear ratios, supply voltage and current limits. The robot
 * program's physics model writes the rotor position and velocity through the sim state, and reads
 * the applied voltage back.
 *
 * <p>The firmware runs a 1 kHz loop on measurements the sim state holds between updates. Here the
 * loop runs once per catch-up with the elapsed time, which gives the same output at the moment it
 * is read.
 */
public final class TalonFxSim extends Device {
  public enum Kind {
    NEUTRAL,
    OPEN_LOOP,
    POSITION,
    VELOCITY,
    MOTION_MAGIC,
    MOTION_MAGIC_EXPO,
    MOTION_MAGIC_VELOCITY,
    FOLLOWER,
    STRICT_FOLLOWER
  }

  /** What a request's output means: duty cycle, volts or amps. */
  public enum Unit {
    DUTY,
    VOLTAGE,
    TORQUE
  }

  /** A control request as the application sent it. Unset profile limits use the config. */
  public static final class Request {
    public Kind kind = Kind.NEUTRAL;
    public Unit unit = Unit.VOLTAGE;
    public double output;
    public double position;
    public double velocity;
    public double acceleration = Double.NaN;
    public double cruiseVelocity = Double.NaN;
    public double jerk = Double.NaN;
    public double expoKv = Double.NaN;
    public double expoKa = Double.NaN;
    public double feedforward;
    public int slot;
    public int leaderId;
    public boolean opposed;

    public Request(Kind kind, Unit unit) {
      this.kind = kind;
      this.unit = unit;
    }
  }

  private record Slot(double kP, double kI, double kD, double kS, double kV, double kA, double kG, boolean arm, boolean closedLoopSign, double armOffset) {}

  private static final double MIN_DT = 0.001;
  private static final double MAX_DT = 0.1;
  private static final double PROFILE_FINISHED = 1e-3;

  // Kraken X60 and X44 stall current (A) and free speed (rpm at 12 V)
  private static final double[] STALL_AMPS = {366, 275};
  private static final double[] FREE_RPM_PER_VOLT = {500, 627.5};
  private static final double[] KT = {0.01937, 0.01473};

  // physics inputs, in the rotor's own counter-clockwise frame
  private double rawPosition;
  private double lastRaw;
  private double rotorVelocity;
  private double rotorAcceleration;
  private boolean accelerationFromSim;
  private double velocityTime;
  private double supplyVoltage = 12;
  private int motorType;

  // cached config
  private double polarity = 1;
  private double sensorToMechanism = 1;
  private double rotorToSensor = 1;
  private int feedbackSource;
  private int remoteSensorId;
  private boolean wrap;
  private double neutralDeadband;
  private double peakForwardDuty = 1;
  private double peakReverseDuty = -1;
  private double peakForwardVoltage = 16;
  private double peakReverseVoltage = -16;
  private double peakForwardTorque = 800;
  private double peakReverseTorque = -800;
  private boolean statorLimited;
  private double statorLimit;
  private boolean supplyLimited;
  private double supplyLimit;
  private double cruiseVelocity;
  private double acceleration;
  private double expoKv;
  private double expoKa;
  private final double[] dutyRamp = new double[2];
  private final double[] voltageRamp = new double[2];
  private final Slot[] slots = new Slot[3];

  private Request request = new Request(Kind.NEUTRAL, Unit.VOLTAGE);
  private SwerveSim owner;

  // closed loop state
  private double integral;
  private boolean profiling;
  private double profilePosition;
  private double profileVelocity;
  private double referencePosition;
  private double referenceVelocity;
  private double referenceAcceleration;
  private double referenceError;
  private double proportionalOutput;
  private double integralOutput;
  private double derivativeOutput;
  private double feedforwardOutput;
  private double closedLoopOutput;
  private double rampValue;
  private boolean atTarget;

  // outputs, in the command frame
  private double voltage;
  private double duty;
  private double statorCurrent;
  private double supplyCurrent;
  private double torqueCurrent;
  private boolean enabled;

  private double lastTime = PhoenixSim.now();
  private boolean dirty = true;
  private boolean syncing;

  TalonFxSim(int id) {
    super("talon fx", id);
    configChanged();
  }

  void setOwner(SwerveSim owner) {
    this.owner = owner;
  }

  @Override
  protected void configChanged() {
    polarity = config.get("Config_Inverted") == 1 ? -1 : 1;
    sensorToMechanism = nonZero(config.get("Config_SensorToMechanismRatio"));
    rotorToSensor = nonZero(config.get("Config_RotorToSensorRatio"));
    feedbackSource = (int) config.get("Config_FeedbackSensorSource");
    remoteSensorId = (int) config.get("Config_FeedbackRemoteSensorID");
    wrap = config.flag("Config_ContinuousWrap");
    neutralDeadband = config.get("Config_DutyCycleNeutralDB");
    peakForwardDuty = config.get("Config_PeakForwardDC");
    peakReverseDuty = config.get("Config_PeakReverseDC");
    peakForwardVoltage = config.get("Config_PeakForwardV");
    peakReverseVoltage = config.get("Config_PeakReverseV");
    peakForwardTorque = config.get("Config_PeakForTorqCurr");
    peakReverseTorque = config.get("Config_PeakRevTorqCurr");
    statorLimited = config.flag("Config_StatorCurrLimitEn");
    statorLimit = config.get("Config_StatorCurrentLimit");
    supplyLimited = config.flag("Config_SupplyCurrLimitEn");
    supplyLimit = config.get("Config_SupplyCurrentLimit");
    cruiseVelocity = config.get("Config_MotionMagicCruiseVelocity");
    acceleration = config.get("Config_MotionMagicAcceleration");
    expoKv = config.get("Config_MotionMagicExpo_kV");
    expoKa = config.get("Config_MotionMagicExpo_kA");
    dutyRamp[0] = config.get("Config_DutyCycleOpenLoopRampPeriod");
    dutyRamp[1] = config.get("Config_DutyCycleClosedLoopRampPeriod");
    voltageRamp[0] = config.get("Config_VoltageOpenLoopRampPeriod");
    voltageRamp[1] = config.get("Config_VoltageClosedLoopRampPeriod");
    for (int i = 0; i < slots.length; i++) {
      String s = "Slot" + i + "_";
      slots[i] =
          new Slot(
              config.get(s + "kP"),
              config.get(s + "kI"),
              config.get(s + "kD"),
              config.get(s + "kS"),
              config.get(s + "kV"),
              config.get(s + "kA"),
              config.get(s + "kG"),
              config.get(s + "kG_Type") == 1,
              config.get(s + "kS_Sign") == 1,
              config.get(s + "kG_PosOffset"));
    }
    dirty = true;
  }

  private static double nonZero(double ratio) {
    return Math.abs(ratio) < 1e-9 ? 1 : ratio;
  }

  // physics inputs

  @Override
  public void setInput(String name, double value) {
    synchronized (PhoenixSim.LOCK) {
      switch (name) {
        case "RawRotorPosition" -> {
          rawPosition += value - lastRaw;
          lastRaw = value;
        }
        case "AddRotorPosition" -> {
          rawPosition += value;
          lastRaw += value;
        }
        case "RotorVelocity" -> {
          double now = PhoenixSim.now();
          double dt = now - velocityTime;
          if (!accelerationFromSim && dt >= MIN_DT && dt <= MAX_DT && value != rotorVelocity) {
            rotorAcceleration = (value - rotorVelocity) / dt;
          }
          rotorVelocity = value;
          velocityTime = now;
        }
        case "RotorAcceleration" -> {
          rotorAcceleration = value;
          accelerationFromSim = true;
        }
        case "SupplyVoltage" -> supplyVoltage = value;
        case "MotorType" -> motorType = value == 1 ? 1 : 0;
        default -> super.setInput(name, value);
      }
      dirty = true;
    }
  }

  @Override
  public double getValue(String name) {
    synchronized (PhoenixSim.LOCK) {
      sync();
      return switch (name) {
        case "MotorVoltage" -> polarity * voltage;
        case "TorqueCurrent" -> polarity * torqueCurrent;
        case "SupplyCurrent" -> supplyCurrent;
        default -> super.getValue(name);
      };
    }
  }

  @Override
  protected void action(String spn, double value) {
    if (spn.equals("TalonFX_SetSensorPosition")) {
      rawPosition = polarity * value * sensorToMechanism;
      dirty = true;
    }
  }

  // sensors

  private double sensorPosition() {
    switch (feedbackSource) {
      case 1, 5, 6 -> {
        return PhoenixSim.cancoder(remoteSensorId).position();
      }
      case 2 -> {
        return PhoenixSim.pigeon(remoteSensorId).yaw() / 360;
      }
      default -> {
        return polarity * rawPosition;
      }
    }
  }

  private double sensorVelocity() {
    switch (feedbackSource) {
      case 1 -> {
        return PhoenixSim.cancoder(remoteSensorId).velocity();
      }
      case 5, 6 -> {
        return polarity * rotorVelocity / rotorToSensor;
      }
      default -> {
        return polarity * rotorVelocity;
      }
    }
  }

  /** The mechanism position in rotations. */
  public double position() {
    synchronized (PhoenixSim.LOCK) {
      return sensorPosition() / sensorToMechanism;
    }
  }

  /** The mechanism velocity in rotations per second. */
  public double velocity() {
    synchronized (PhoenixSim.LOCK) {
      return sensorVelocity() / sensorToMechanism;
    }
  }

  /** Sets the mechanism position, as the application does with setPosition. */
  public void setPosition(double rotations) {
    synchronized (PhoenixSim.LOCK) {
      action("TalonFX_SetSensorPosition", rotations);
    }
  }

  // control

  /** Sends a control request. */
  public void control(Request next) {
    synchronized (PhoenixSim.LOCK) {
      sync();
      if (next.kind != request.kind || next.unit != request.unit) {
        integral = 0;
        profiling = false;
      }
      request = next;
      dirty = true;
    }
  }

  /** True while the request is a closed loop on the motor's sensor. */
  public boolean isClosedLoop() {
    return request.kind.ordinal() >= Kind.POSITION.ordinal()
        && request.kind.ordinal() <= Kind.MOTION_MAGIC_VELOCITY.ordinal();
  }

  /** Catches up to the HAL clock. */
  void sync() {
    if (syncing) {
      return;
    }
    double now = PhoenixSim.now();
    double dt = now - lastTime;
    if (dt < MIN_DT && !dirty && enabled == DriverStationData.get().robotEnabled()) {
      return;
    }
    syncing = true;
    try {
      lastTime = now;
      dirty = false;
      step(Math.max(0, Math.min(dt, MAX_DT)));
    } finally {
      syncing = false;
    }
  }

  private double ohms() {
    return 12 / STALL_AMPS[motorType];
  }

  private double rpsPerVolt() {
    return FREE_RPM_PER_VOLT[motorType] / 60;
  }

  private void step(double dt) {
    if (owner != null) {
      owner.sync();
    }
    enabled = DriverStationData.get().robotEnabled();
    Request r = request;
    double s = supplyVoltage;
    double omega = polarity * rotorVelocity;
    double out = 0;
    Unit unit = r.unit;
    boolean neutral = !enabled;
    boolean closedLoop = false;

    switch (r.kind) {
      case NEUTRAL -> neutral = true;
      case OPEN_LOOP -> out = r.output;
      case FOLLOWER, STRICT_FOLLOWER -> {
        TalonFxSim leader = PhoenixSim.talon(r.leaderId);
        if (leader == this) {
          neutral = true;
        } else {
          leader.sync();
          double sign = r.kind == Kind.STRICT_FOLLOWER ? 1 : leader.polarity * polarity;
          out = leader.duty * (r.opposed ? -sign : sign);
          unit = Unit.DUTY;
        }
      }
      default -> {
        closedLoop = true;
        if (!neutral) {
          out = closedLoop(r, dt);
        }
      }
    }

    if (neutral) {
      voltage = 0;
      duty = 0;
      statorCurrent = 0;
      supplyCurrent = 0;
      torqueCurrent = 0;
      integral = 0;
      profiling = false;
      rampValue = 0;
      atTarget = false;
      referencePosition = position();
      referenceVelocity = 0;
      referenceError = 0;
      closedLoopOutput = 0;
      return;
    }

    if (unit != Unit.TORQUE) {
      out = ramp(out, unit == Unit.DUTY ? dutyRamp : voltageRamp, unit == Unit.DUTY ? 1 : 12, closedLoop, dt);
    }
    double u;
    double current = Double.NaN;
    switch (unit) {
      case DUTY -> u = out * s;
      case VOLTAGE -> u = out;
      default -> {
        current = Math.max(peakReverseTorque, Math.min(peakForwardTorque, out));
        u = current * ohms() + omega / rpsPerVolt();
      }
    }
    if (unit != Unit.TORQUE && Math.abs(u) < neutralDeadband * s) {
      u = 0;
    }
    u = Math.max(Math.max(peakReverseVoltage, peakReverseDuty * s), Math.min(Math.min(peakForwardVoltage, peakForwardDuty * s), u));
    u = Math.max(-s, Math.min(s, u));
    u = limitCurrent(u, omega, s);

    voltage = u;
    duty = s > 0 ? u / s : 0;
    statorCurrent = (u - omega / rpsPerVolt()) / ohms();
    supplyCurrent = Math.max(0, duty * statorCurrent);
    torqueCurrent = Double.isNaN(current) ? statorCurrent : current;
    if (!closedLoop) {
      closedLoopOutput = 0;
    }
  }

  private double ramp(double target, double[] period, double fullScale, boolean closedLoop, double dt) {
    double p = period[closedLoop ? 1 : 0];
    if (p > 0) {
      double max = fullScale / p * dt;
      target = rampValue + Math.max(-max, Math.min(max, target - rampValue));
    }
    rampValue = target;
    return target;
  }

  private double limitCurrent(double u, double omega, double s) {
    double emf = omega / rpsPerVolt();
    double limit = Double.POSITIVE_INFINITY;
    if (statorLimited && statorLimit > 0) {
      limit = statorLimit;
    }
    if (supplyLimited && supplyLimit > 0 && Math.abs(u) > 1e-6) {
      limit = Math.min(limit, supplyLimit * s / Math.abs(u));
    }
    double current = (u - emf) / ohms();
    if (Math.abs(current) > limit) {
      return emf + Math.signum(current) * limit * ohms();
    }
    return u;
  }

  // closed loops

  private double closedLoop(Request r, double dt) {
    Slot g = slots[Math.max(0, Math.min(2, r.slot))];
    double position = position();
    double velocity = velocity();
    double acceleration = polarity * rotorAcceleration / rotorToSensor / sensorToMechanism;
    boolean positional = r.kind != Kind.VELOCITY && r.kind != Kind.MOTION_MAGIC_VELOCITY;

    double refPosition = r.position;
    double refVelocity = r.velocity;
    double refAcceleration = 0;
    switch (r.kind) {
      case MOTION_MAGIC, MOTION_MAGIC_EXPO -> {
        if (!profiling) {
          profilePosition = position;
          profileVelocity = velocity;
          profiling = true;
        }
        double goal = wrap ? profilePosition + wrapped(r.position - profilePosition) : r.position;
        double previousVelocity = profileVelocity;
        if (r.kind == Kind.MOTION_MAGIC) {
          double cruise = limit(r.cruiseVelocity, cruiseVelocity);
          double accel = limit(r.acceleration, this.acceleration);
          if (accel <= 0) {
            profilePosition = goal;
            profileVelocity = 0;
          } else {
            var state =
                new TrapezoidProfile(new TrapezoidProfile.Constraints(cruise <= 0 ? 1e9 : cruise, accel))
                    .calculate(dt, new TrapezoidProfile.State(profilePosition, profileVelocity), new TrapezoidProfile.State(goal, 0));
            profilePosition = state.position;
            profileVelocity = state.velocity;
          }
        } else {
          double kV = limit(r.expoKv, expoKv);
          double kA = limit(r.expoKa, expoKa);
          double cruise = limit(r.cruiseVelocity, cruiseVelocity);
          if (kV <= 0 || kA <= 0) {
            profilePosition = goal;
            profileVelocity = 0;
          } else {
            double maxInput = cruise > 0 ? Math.min(12, cruise * kV) : 12;
            var state =
                new ExponentialProfile(ExponentialProfile.Constraints.fromCharacteristics(maxInput, kV, kA))
                    .calculate(dt, new ExponentialProfile.State(profilePosition, profileVelocity), new ExponentialProfile.State(goal, 0));
            profilePosition = state.position;
            profileVelocity = state.velocity;
          }
        }
        refPosition = profilePosition;
        refVelocity = profileVelocity;
        refAcceleration = dt > 0 ? (profileVelocity - previousVelocity) / dt : 0;
        atTarget = Math.abs(goal - profilePosition) < PROFILE_FINISHED && Math.abs(profileVelocity) < PROFILE_FINISHED;
      }
      case MOTION_MAGIC_VELOCITY -> {
        if (!profiling) {
          profileVelocity = velocity;
          profiling = true;
        }
        double accel = limit(r.acceleration, this.acceleration);
        double cruise = limit(r.cruiseVelocity, cruiseVelocity);
        double target = cruise > 0 ? Math.max(-cruise, Math.min(cruise, r.velocity)) : r.velocity;
        double previousVelocity = profileVelocity;
        if (accel <= 0) {
          profileVelocity = target;
        } else {
          double max = accel * dt;
          profileVelocity += Math.max(-max, Math.min(max, target - profileVelocity));
        }
        refVelocity = profileVelocity;
        refAcceleration = dt > 0 ? (profileVelocity - previousVelocity) / dt : 0;
        atTarget = Math.abs(target - profileVelocity) < PROFILE_FINISHED;
      }
      case VELOCITY -> {
        refAcceleration = Double.isNaN(r.acceleration) ? 0 : r.acceleration;
        atTarget = false;
      }
      default -> atTarget = false;
    }

    double p;
    double d;
    double error;
    if (positional) {
      error = refPosition - position;
      if (wrap) {
        error = wrapped(error);
      }
      p = g.kP * error;
      d = g.kD * (refVelocity - velocity);
    } else {
      error = refVelocity - velocity;
      p = g.kP * error;
      d = g.kD * (refAcceleration - acceleration);
    }
    double scale = r.unit == Unit.DUTY ? 1 : r.unit == Unit.VOLTAGE ? 12 : 800;
    if (g.kI != 0) {
      integral = Math.max(-scale / Math.abs(g.kI), Math.min(scale / Math.abs(g.kI), integral + error * dt));
    }
    double i = g.kI * integral;
    double pid = p + i + d;

    double sign = g.closedLoopSign ? Math.signum(pid) : Math.signum(refVelocity);
    double gravity = g.arm ? g.kG * Math.cos(2 * Math.PI * (position + g.armOffset)) : g.kG;
    double ff = g.kS * sign + g.kV * refVelocity + g.kA * refAcceleration + gravity + r.feedforward;

    referencePosition = positional ? refPosition : position;
    referenceVelocity = refVelocity;
    referenceAcceleration = refAcceleration;
    referenceError = error;
    proportionalOutput = p;
    integralOutput = i;
    derivativeOutput = d;
    feedforwardOutput = ff;
    closedLoopOutput = pid;
    return pid + ff;
  }

  private static double limit(double fromRequest, double fromConfig) {
    return Double.isNaN(fromRequest) ? fromConfig : fromRequest;
  }

  private static double wrapped(double error) {
    return error - Math.rint(error);
  }

  // signals

  @Override
  public double signal(String spn) {
    synchronized (PhoenixSim.LOCK) {
      sync();
      boolean positional = request.kind != Kind.VELOCITY && request.kind != Kind.MOTION_MAGIC_VELOCITY;
      return switch (spn) {
        case "PRO_MotorOutput_MotorVoltage" -> voltage;
        case "PRO_MotorOutput_DutyCycle" -> duty;
        case "PRO_MotorOutput_TorqueCurrent" -> torqueCurrent;
        case "PRO_SupplyAndTemp_StatorCurrent" -> statorCurrent;
        case "PRO_SupplyAndTemp_SupplyCurrent" -> supplyCurrent;
        case "PRO_SupplyAndTemp_SupplyVoltage" -> supplyVoltage;
        case "PRO_SupplyAndTemp_DeviceTemp", "PRO_SupplyAndTemp_ProcessorTemp", "PRO_SupplyAndTemp_DeviceTemp2" -> 25;
        case "PRO_MotorOutput_RotorPolarity" -> polarity < 0 ? 1 : 0;
        case "PRO_RotorPosAndVel_Position" -> polarity * rawPosition;
        case "PRO_RotorPosAndVel_Velocity" -> polarity * rotorVelocity;
        case "PRO_PosAndVel_Position" -> position();
        case "PRO_PosAndVel_Velocity" -> velocity();
        case "PRO_PosAndVel_Acceleration" -> polarity * rotorAcceleration / rotorToSensor / sensorToMechanism;
        case "PRO_PIDOutput_Slot", "PRO_DiffPIDOutput_Slot" -> request.slot;
        case "PRO_PIDStateEnables_RobotEnable", "PRO_PIDStateEnables_DeviceEnable" -> enabled ? 1 : 0;
        case "PRO_PIDStateEnables_MotionMagicAtTarget" -> atTarget ? 1 : 0;
        case "PRO_PIDStateEnables_IsMotionMagicRunning" -> isMotionMagic() && enabled ? 1 : 0;
        case "PRO_PIDRefPIDErr_PIDRef_Position" -> positional ? referencePosition : referenceVelocity;
        case "PRO_PIDRefSlopeECUTime_ReferenceSlope_Position" -> positional ? referenceVelocity : referenceAcceleration;
        case "PRO_PIDRefPIDErr_PIDErr_Position" -> referenceError;
        case "PRO_PIDOutput_Output_DC" -> closedLoopOutput;
        case "PRO_PIDOutput_ProportionalOutput_DC" -> proportionalOutput;
        case "PRO_PIDStateEnables_IntegratedAccum_DC" -> integralOutput;
        case "PRO_PIDOutput_DerivativeOutput_DC" -> derivativeOutput;
        case "PRO_PIDStateEnables_FeedForward_DC" -> feedforwardOutput;
        case "Version_IsProLicensed" -> 1;
        case "TalonFX_MotorConstants_kT" -> KT[motorType];
        case "TalonFX_MotorConstants_kV" -> FREE_RPM_PER_VOLT[motorType];
        case "TalonFX_MotorConstants_StallCurrent" -> STALL_AMPS[motorType];
        default -> Double.NaN;
      };
    }
  }

  private boolean isMotionMagic() {
    return request.kind == Kind.MOTION_MAGIC
        || request.kind == Kind.MOTION_MAGIC_EXPO
        || request.kind == Kind.MOTION_MAGIC_VELOCITY;
  }
}
