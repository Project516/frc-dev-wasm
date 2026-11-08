# Vendor simulation

Vendor jars are downloaded by the browser and transformed there by `NativeStubber`, which finds the
Impl classes through the `impls.txt` index in the tools jar. Each native method calls a static method of the
same name in a handwritten Impl class when one exists, and otherwise returns a default. This page
lists what the Impl classes emulate and where they approximate. The desktop vendor libraries run
device firmware in a native sim library. Here the firmware is Java in
`packages/java/shim/src/dev/project516/frcdevwasm/shim/vendor`.

## Time

Devices catch up to the HAL clock whenever the program touches them, so
`SimHooks.pauseTiming`, `stepTiming` and the Sim GUI pause and step control them. The 1 kHz
firmware loop runs once per catch-up with the elapsed time, capped at 0.1 s, and the output is the
one the firmware would hold at that moment. All JNI entry points share one lock.

## Phoenix 6

| Area | State |
| --- | --- |
| Configs | Apply and refresh read back, defaults included, for every device. Partial configs merge. |
| Enable | Output is neutral while the Driver Station is disabled. |
| TalonFX open loop | DutyCycleOut, VoltageOut, TorqueCurrentFOC, NeutralOut, CoastOut, StaticBrake, ModulateVBatOut. |
| TalonFX followers | Follower (aligned or opposed, with both inverts) and StrictFollower. |
| TalonFX closed loop | Position, Velocity, MotionMagic, MotionMagicExpo, MotionMagicVelocity and the Dynamic variants, in Voltage, DutyCycle and TorqueCurrentFOC. |
| Gains | kP, kI, kD, kS (velocity sign or closed loop sign), kV, kA, kG (static or arm cosine with offset), slots 0 to 2, FeedForward, continuous wrap. |
| Ratios | SensorToMechanismRatio, RotorToSensorRatio, and Remote, Fused or Sync CANcoder and Pigeon 2 yaw as the feedback sensor. |
| Limits | Peak duty, peak voltage, neutral deadband, supply voltage, voltage and duty ramps, stator and supply current limits (from the motor model, see below). |
| Signals | MotorVoltage, DutyCycle, TorqueCurrent, StatorCurrent, SupplyCurrent, SupplyVoltage, rotor and mechanism Position and Velocity, ClosedLoopReference, Error, Output and its terms, MotionMagic state, enable state. Everything else reads zero. |
| TalonFXSimState | `setRawRotorPosition`, `addRotorPosition`, `setRotorVelocity`, `setRotorAcceleration`, `setSupplyVoltage`, `getMotorVoltage`, `getTorqueCurrent`, `getSupplyCurrent`, orientation. `setPosition` survives later raw updates. |
| CANcoder | Raw position integrates with homing, sensor direction, magnet offset, absolute position wrap, velocity. |
| Pigeon 2 | Raw yaw integrates with `setYaw`, pitch, roll, angular velocity, quaternion and gravity vector from the angles. |
| Swerve | SwerveJNI in `SwerveSim`: Idle, SwerveDriveBrake, FieldCentric, RobotCentric, PointWheelsAt, ApplyRobotSpeeds, ApplyFieldSpeeds, Java requests, module state and position, a WPILib pose estimator over the module positions and Pigeon yaw, `resetPose`, `seedFieldCentric`, `tareEverything`, `addVisionMeasurement`, `samplePoseAt`, telemetry callback. `updateSimState` moves the pose through the module and Pigeon sim states. |

Approximations:

- The motor model is a Kraken X60 (366 A stall, 500 rpm per volt) or X44. Torque current requests
  convert to voltage as `I * R + back EMF`. Current limits clamp that estimate. The sim state's
  motor type selects the constants.
- Motion Magic jerk is ignored, so a nonzero jerk gives a trapezoid. Expo uses WPILib's
  `ExponentialProfile` with a 12 V input.
- Position and velocity signals do not model sensor filtering or latency. Acceleration comes from
  the sim state, or from the rotor velocity change between updates.
- Swerve control and odometry tick at most every 10 ms, at the rate the program touches the
  devices, not at the configured odometry frequency. The telemetry callback runs at 50 Hz.
  Wheel force feedforward and slip current are ignored.
- `seedFieldCentric(rotation)` resets the pose rotation. `tareEverything` also zeroes the drive
  motor positions.
- Not emulated, reads defaults: TalonFXS, CANrange, CANdi, CANdle, differential requests, animations,
  Orchestra, the signal logger, hoot replay, hardware and software limit switches, faults,
  temperature (25 C) and the Sim GUI device views.
- Devices on different CAN buses with the same id share state.
- Phoenix 6 26.1 passes the CAN bus to some natives as a string and later versions as a buffer.
  Both are handled.

## REVLib

| Area | State |
| --- | --- |
| Parameters | `c_Spark_Set/GetParameter*`, configs through `configure`, reset modes, the types REVLib flattens with. Defaults: output range -1 to 1, conversion factors 1, smart current limit 80 A. |
| Closed loop | `SparkSim.iterate` closed loops through the native hooks: position, velocity and current control with kP, kI, kD, kV, kS, kG, kCos, I zone, max I accumulation, output range and position wrap. |
| MAXMotion | Position (trapezoid) and velocity (acceleration limit), with cruise velocity and max acceleration. |
| Encoders | Position and velocity from the sim device, in the units of the conversion factors. |
| Current limit | `GetSimCurrentLimitOutput` scales the output to the smart current limit. |

Approximations:

- Gains in volts (kS, kV, kA, kG, kCos) become duty cycle with the bus voltage that `iterate` last
  reported.
- The I accumulator and the D term count in 1 ms firmware ticks. This is assumed, not measured.
- The smart current limit is the stall limit at every speed, not the speed-dependent curve.
- Not emulated: absolute, alternate and analog sensor sims, limit switches, followers, faults,
  open loop ramp, the D filter, closed loop ramp.

## ReduxLib

Reports its own version so the library starts. Devices read as idle.
