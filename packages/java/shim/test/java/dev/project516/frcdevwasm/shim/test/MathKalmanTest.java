package dev.project516.frcdevwasm.shim.test;

import edu.wpi.first.math.MatBuilder;
import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.Matrix;
import edu.wpi.first.math.Nat;
import edu.wpi.first.math.StateSpaceUtil;
import edu.wpi.first.math.VecBuilder;
import edu.wpi.first.math.estimator.AngleStatistics;
import edu.wpi.first.math.estimator.KalmanFilter;
import edu.wpi.first.math.estimator.UnscentedKalmanFilter;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.jni.WPIMathJNI;
import edu.wpi.first.math.numbers.N1;
import edu.wpi.first.math.numbers.N2;
import edu.wpi.first.math.numbers.N3;
import edu.wpi.first.math.numbers.N4;
import edu.wpi.first.math.numbers.N5;
import edu.wpi.first.math.numbers.N6;
import edu.wpi.first.math.system.Discretization;
import edu.wpi.first.math.system.LinearSystem;
import edu.wpi.first.math.system.NumericalIntegration;
import edu.wpi.first.math.system.NumericalJacobian;
import edu.wpi.first.math.system.plant.DCMotor;
import edu.wpi.first.math.system.plant.LinearSystemId;
import edu.wpi.first.math.trajectory.TrajectoryConfig;
import edu.wpi.first.math.trajectory.TrajectoryGenerator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** KalmanFilter and UnscentedKalmanFilter (DARE, rank updates, matrix exp), from allwpilib. */
public final class MathKalmanTest {
  static {
    WPIMathJNI.Helper.setExtractOnStaticLoad(false);
  }

  private static final double DT = 0.005;

  // swerve drive observer: states [x, y, theta, vx, vy, vTheta], outputs [x, y, theta]
  private static LinearSystem<N6, N3, N3> swerveObserverSystem() {
    return new LinearSystem<>(
        MatBuilder.fill(
            Nat.N6(), Nat.N6(), 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0),
        MatBuilder.fill(Nat.N6(), Nat.N3(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1),
        MatBuilder.fill(Nat.N3(), Nat.N6(), 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0),
        new Matrix<>(Nat.N3(), Nat.N3()));
  }

  @SuppressWarnings("unchecked")
  public static void testElevatorKalmanFilter() {
    var plant = LinearSystemId.createElevatorSystem(DCMotor.getVex775Pro(2), 5.0, 0.0181864, 1.0);
    new KalmanFilter<>(Nat.N2(), Nat.N1(), (LinearSystem<N2, N1, N1>) plant.slice(0), VecBuilder.fill(0.05, 1.0), VecBuilder.fill(0.0001), DT);
  }

  public static void testSwerveKFStationary() {
    var random = new Random();
    var filter =
        new KalmanFilter<>(Nat.N6(), Nat.N3(), swerveObserverSystem(), VecBuilder.fill(0.1, 0.1, 0.1, 0.1, 0.1, 0.1), VecBuilder.fill(2, 2, 2), 0.020);
    for (int i = 0; i < 100; i++) {
      var measurement = VecBuilder.fill(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
      filter.correct(VecBuilder.fill(0.0, 0.0, 0.0), measurement);
      filter.predict(VecBuilder.fill(0.0, 0.0, 0.0), 0.020);
    }
    T.near(0.0, filter.getXhat(0), 0.3, "x");
  }

  public static void testSwerveKFMovingWithoutAccelerating() {
    var random = new Random();
    var filter =
        new KalmanFilter<>(Nat.N6(), Nat.N3(), swerveObserverSystem(), VecBuilder.fill(0.1, 0.1, 0.1, 0.1, 0.1, 0.1), VecBuilder.fill(4, 4, 4), 0.020);
    filter.setXhat(0, 0.5);
    filter.setXhat(1, 0.5);
    for (int i = 0; i < 300; i++) {
      var measurement = VecBuilder.fill(random.nextGaussian() / 10d, random.nextGaussian() / 10d, random.nextGaussian() / 4d);
      filter.correct(VecBuilder.fill(0.0, 0.0, 0.0), measurement);
      filter.predict(VecBuilder.fill(0.0, 0.0, 0.0), 0.020);
    }
    T.near(0.0, filter.getXhat(0), 0.2, "x");
    T.near(0.0, filter.getXhat(1), 0.2, "y");
  }

  public static void testSwerveKFMovingOverTrajectory() {
    var random = new Random();
    var filter =
        new KalmanFilter<>(Nat.N6(), Nat.N3(), swerveObserverSystem(), VecBuilder.fill(0.1, 0.1, 0.1, 0.1, 0.1, 0.1), VecBuilder.fill(4, 4, 4), 0.020);
    var trajectory =
        TrajectoryGenerator.generateTrajectory(
            List.of(new Pose2d(0, 0, Rotation2d.kZero), new Pose2d(5, 5, Rotation2d.kZero)), new TrajectoryConfig(2, 2));
    var time = 0.0;
    var lastVelocity = VecBuilder.fill(0.0, 0.0, 0.0);
    while (time <= trajectory.getTotalTimeSeconds()) {
      var sample = trajectory.sample(time);
      var measurement =
          VecBuilder.fill(
              sample.poseMeters.getTranslation().getX() + random.nextGaussian() / 5d,
              sample.poseMeters.getTranslation().getY() + random.nextGaussian() / 5d,
              sample.poseMeters.getRotation().getRadians() + random.nextGaussian() / 3d);
      var velocity =
          VecBuilder.fill(
              sample.velocityMetersPerSecond * sample.poseMeters.getRotation().getCos(),
              sample.velocityMetersPerSecond * sample.poseMeters.getRotation().getSin(),
              sample.curvatureRadPerMeter * sample.velocityMetersPerSecond);
      var u = (velocity.minus(lastVelocity)).div(0.020);
      lastVelocity = velocity;
      filter.correct(u, measurement);
      filter.predict(u, 0.020);
      time += 0.020;
    }
    var end = trajectory.sample(trajectory.getTotalTimeSeconds()).poseMeters.getTranslation();
    T.near(end.getX(), filter.getXhat(0), 0.2, "x");
    T.near(end.getY(), filter.getXhat(1), 0.2, "y");
  }

  // ---- unscented filter

  private static Matrix<N5, N1> driveDynamics(Matrix<N5, N1> x, Matrix<N2, N1> u) {
    var motors = DCMotor.getCIM(2);
    var gHigh = 7.08;
    var rb = 0.8382 / 2.0;
    var r = 0.0746125;
    var m = 63.503;
    var j = 5.6;

    var c1 = -Math.pow(gHigh, 2) * motors.KtNMPerAmp / (motors.KvRadPerSecPerVolt * motors.rOhms * r * r);
    var c2 = gHigh * motors.KtNMPerAmp / (motors.rOhms * r);
    var k1 = 1.0 / m + Math.pow(rb, 2) / j;
    var k2 = 1.0 / m - Math.pow(rb, 2) / j;

    var vl = x.get(3, 0);
    var vr = x.get(4, 0);
    var vlVolts = u.get(0, 0);
    var vrVolts = u.get(1, 0);

    var v = 0.5 * (vl + vr);
    return VecBuilder.fill(
        v * Math.cos(x.get(2, 0)),
        v * Math.sin(x.get(2, 0)),
        (vr - vl) / (2.0 * rb),
        k1 * (c1 * vl + c2 * vlVolts) + k2 * (c1 * vr + c2 * vrVolts),
        k2 * (c1 * vl + c2 * vlVolts) + k1 * (c1 * vr + c2 * vrVolts));
  }

  private static Matrix<N3, N1> driveLocalMeasurementModel(Matrix<N5, N1> x, Matrix<N2, N1> u) {
    return VecBuilder.fill(x.get(2, 0), x.get(3, 0), x.get(4, 0));
  }

  private static Matrix<N5, N1> driveGlobalMeasurementModel(Matrix<N5, N1> x, Matrix<N2, N1> u) {
    return x.copy();
  }

  public static void testDriveInit() {
    var dt = 0.005;
    UnscentedKalmanFilter<N5, N2, N3> observer =
        new UnscentedKalmanFilter<>(
            Nat.N5(),
            Nat.N3(),
            MathKalmanTest::driveDynamics,
            MathKalmanTest::driveLocalMeasurementModel,
            VecBuilder.fill(0.5, 0.5, 10.0, 1.0, 1.0),
            VecBuilder.fill(0.0001, 0.01, 0.01),
            AngleStatistics.angleMean(2),
            AngleStatistics.angleMean(0),
            AngleStatistics.angleResidual(2),
            AngleStatistics.angleResidual(0),
            AngleStatistics.angleAdd(2),
            dt);

    var u = VecBuilder.fill(12.0, 12.0);
    observer.predict(u, dt);
    observer.correct(u, driveLocalMeasurementModel(observer.getXhat(), u));

    var globalY = driveGlobalMeasurementModel(observer.getXhat(), u);
    var r = StateSpaceUtil.makeCovarianceMatrix(Nat.N5(), VecBuilder.fill(0.01, 0.01, 0.0001, 0.01, 0.01));
    observer.correct(
        Nat.N5(),
        u,
        globalY,
        MathKalmanTest::driveGlobalMeasurementModel,
        r,
        AngleStatistics.angleMean(2),
        AngleStatistics.angleResidual(2),
        AngleStatistics.angleResidual(2),
        AngleStatistics.angleAdd(2));
  }

  public static void testDriveConvergence() {
    final double dt = 0.005;
    final double rbMeters = 0.8382 / 2.0;

    UnscentedKalmanFilter<N5, N2, N3> observer =
        new UnscentedKalmanFilter<>(
            Nat.N5(),
            Nat.N3(),
            MathKalmanTest::driveDynamics,
            MathKalmanTest::driveLocalMeasurementModel,
            VecBuilder.fill(0.5, 0.5, 10.0, 1.0, 1.0),
            VecBuilder.fill(0.0001, 0.5, 0.5),
            AngleStatistics.angleMean(2),
            AngleStatistics.angleMean(0),
            AngleStatistics.angleResidual(2),
            AngleStatistics.angleResidual(0),
            AngleStatistics.angleAdd(2),
            dt);

    List<Pose2d> waypoints = List.of(new Pose2d(2.75, 22.521, Rotation2d.kZero), new Pose2d(24.73, 19.68, Rotation2d.fromDegrees(5.846)));
    var trajectory = TrajectoryGenerator.generateTrajectory(waypoints, new TrajectoryConfig(8.8, 0.1));

    Matrix<N5, N1> r = new Matrix<>(Nat.N5(), Nat.N1());
    Matrix<N2, N1> u = new Matrix<>(Nat.N2(), Nat.N1());

    var b =
        NumericalJacobian.numericalJacobianU(
            Nat.N5(), Nat.N2(), MathKalmanTest::driveDynamics, new Matrix<>(Nat.N5(), Nat.N1()), new Matrix<>(Nat.N2(), Nat.N1()));

    observer.setXhat(
        VecBuilder.fill(
            trajectory.getInitialPose().getTranslation().getX(),
            trajectory.getInitialPose().getTranslation().getY(),
            trajectory.getInitialPose().getRotation().getRadians(),
            0.0,
            0.0));

    var trueXhat = observer.getXhat();

    double totalTime = trajectory.getTotalTimeSeconds();
    for (int i = 0; i < (totalTime / dt); ++i) {
      var ref = trajectory.sample(dt * i);
      double vl = ref.velocityMetersPerSecond * (1 - (ref.curvatureRadPerMeter * rbMeters));
      double vr = ref.velocityMetersPerSecond * (1 + (ref.curvatureRadPerMeter * rbMeters));

      var nextR =
          VecBuilder.fill(
              ref.poseMeters.getTranslation().getX(),
              ref.poseMeters.getTranslation().getY(),
              ref.poseMeters.getRotation().getRadians(),
              vl,
              vr);

      Matrix<N3, N1> localY = driveLocalMeasurementModel(trueXhat, new Matrix<>(Nat.N2(), Nat.N1()));
      var noiseStdDev = VecBuilder.fill(0.0001, 0.5, 0.5);
      observer.correct(u, localY.plus(StateSpaceUtil.makeWhiteNoiseVector(noiseStdDev)));

      var rdot = nextR.minus(r).div(dt);
      u = new Matrix<>(b.solve(rdot.minus(driveDynamics(r, new Matrix<>(Nat.N2(), Nat.N1())))));
      observer.predict(u, dt);

      r = nextR;
      trueXhat = NumericalIntegration.rk4(MathKalmanTest::driveDynamics, trueXhat, u, dt);
    }

    observer.correct(u, driveLocalMeasurementModel(trueXhat, u));
    var globalY = driveGlobalMeasurementModel(trueXhat, u);
    var cov = StateSpaceUtil.makeCovarianceMatrix(Nat.N5(), VecBuilder.fill(0.01, 0.01, 0.0001, 0.5, 0.5));
    observer.correct(
        Nat.N5(),
        u,
        globalY,
        MathKalmanTest::driveGlobalMeasurementModel,
        cov,
        AngleStatistics.angleMean(2),
        AngleStatistics.angleResidual(2),
        AngleStatistics.angleResidual(2),
        AngleStatistics.angleAdd(2));

    final var finalPosition = trajectory.sample(trajectory.getTotalTimeSeconds());
    T.near(finalPosition.poseMeters.getTranslation().getX(), observer.getXhat(0), 0.055, "x");
    T.near(finalPosition.poseMeters.getTranslation().getY(), observer.getXhat(1), 0.15, "y");
    T.near(finalPosition.poseMeters.getRotation().getRadians(), observer.getXhat(2), 0.000005, "theta");
    T.near(0.0, observer.getXhat(3), 0.1, "vl");
    T.near(0.0, observer.getXhat(4), 0.1, "vr");
  }

  public static void testLinearUKF() {
    var dt = 0.020;
    var plant = LinearSystemId.identifyVelocitySystem(0.02, 0.006);
    var observer =
        new UnscentedKalmanFilter<>(
            Nat.N1(), Nat.N1(), (x, u) -> plant.getA().times(x).plus(plant.getB().times(u)), plant::calculateY, VecBuilder.fill(0.05), VecBuilder.fill(1.0), dt);

    var discAB = Discretization.discretizeAB(plant.getA(), plant.getB(), dt);
    var discA = discAB.getFirst();
    var discB = discAB.getSecond();

    Matrix<N1, N1> ref = VecBuilder.fill(100);
    Matrix<N1, N1> u = VecBuilder.fill(0);
    for (int i = 0; i < (2.0 / dt); ++i) {
      observer.predict(u, dt);
      u = discB.solve(ref.minus(discA.times(ref)));
    }
    T.near(ref.get(0, 0), observer.getXhat(0), 5, "xhat");
  }

  public static void testRoundTripP() {
    var observer =
        new UnscentedKalmanFilter<>(Nat.N2(), Nat.N2(), (x, u) -> x, (x, u) -> x, VecBuilder.fill(0.0, 0.0), VecBuilder.fill(0.0, 0.0), DT);
    var p = MatBuilder.fill(Nat.N2(), Nat.N2(), 2.0, 1.0, 1.0, 2.0);
    observer.setP(p);
    T.check(observer.getP().isEqual(p, 1e-9), "P round trip");
  }

  private static Matrix<N4, N1> motorDynamics(Matrix<N4, N1> x, Matrix<N1, N1> u) {
    double v = x.get(1, 0);
    double kv = x.get(2, 0);
    double ka = x.get(3, 0);
    double volts = u.get(0, 0);
    double a = -kv / ka * v + 1.0 / ka * volts;
    return MatBuilder.fill(Nat.N4(), Nat.N1(), v, a, 0, 0);
  }

  private static Matrix<N3, N1> motorMeasurementModel(Matrix<N4, N1> x, Matrix<N1, N1> u) {
    double p = x.get(0, 0);
    double v = x.get(1, 0);
    double kv = x.get(2, 0);
    double ka = x.get(3, 0);
    double volts = u.get(0, 0);
    double a = -kv / ka * v + 1.0 / ka * volts;
    return MatBuilder.fill(Nat.N3(), Nat.N1(), p, v, a);
  }

  private static Matrix<N1, N1> motorControlInput(double t) {
    return MatBuilder.fill(
        Nat.N1(),
        Nat.N1(),
        MathUtil.clamp(
            8 * Math.sin(Math.PI * Math.sqrt(2.0) * t) + 6 * Math.sin(Math.PI * Math.sqrt(3.0) * t) + 4 * Math.sin(Math.PI * Math.sqrt(5.0) * t),
            -12.0,
            12.0));
  }

  public static void testMotorConvergence() {
    final double dt = 0.01;
    final int steps = 500;
    final double trueKv = 3;
    final double trueKa = 0.2;
    final double posStddev = 0.02;
    final double velStddev = 0.1;
    final double accelStddev = 0.1;

    var states = new ArrayList<>(Collections.nCopies(steps + 1, MatBuilder.fill(Nat.N4(), Nat.N1(), 0.0, 0.0, 0.0, 0.0)));
    var inputs = new ArrayList<>(Collections.nCopies(steps, MatBuilder.fill(Nat.N1(), Nat.N1(), 0.0)));
    var measurements = new ArrayList<>(Collections.nCopies(steps + 1, MatBuilder.fill(Nat.N3(), Nat.N1(), 0.0, 0.0, 0.0)));
    states.set(0, MatBuilder.fill(Nat.N4(), Nat.N1(), 0.0, 0.0, trueKv, trueKa));

    var a = MatBuilder.fill(Nat.N4(), Nat.N4(), 0.0, 1.0, 0.0, 0.0, 0.0, -trueKv / trueKa, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    var b = MatBuilder.fill(Nat.N4(), Nat.N1(), 0.0, 1.0 / trueKa, 0.0, 0.0);

    var discAB = Discretization.discretizeAB(a, b, dt);
    var discA = discAB.getFirst();
    var discB = discAB.getSecond();

    for (int i = 0; i < steps; ++i) {
      inputs.set(i, motorControlInput(i * dt));
      states.set(i + 1, discA.times(states.get(i)).plus(discB.times(inputs.get(i))));
      measurements.set(
          i,
          motorMeasurementModel(states.get(i + 1), inputs.get(i))
              .plus(StateSpaceUtil.makeWhiteNoiseVector(VecBuilder.fill(posStddev, velStddev, accelStddev))));
    }

    var p0 = MatBuilder.fill(Nat.N4(), Nat.N4(), 0.001, 0.0, 0.0, 0.0, 0.0, 0.001, 0.0, 0.0, 0.0, 0.0, 10, 0.0, 0.0, 0.0, 0.0, 10);

    var observer =
        new UnscentedKalmanFilter<N4, N1, N3>(
            Nat.N4(),
            Nat.N3(),
            MathKalmanTest::motorDynamics,
            MathKalmanTest::motorMeasurementModel,
            VecBuilder.fill(0.1, 1.0, 1e-10, 1e-10),
            VecBuilder.fill(posStddev, velStddev, accelStddev),
            dt);
    observer.setXhat(MatBuilder.fill(Nat.N4(), Nat.N1(), 0.0, 0.0, 2.0, 2.0));
    observer.setP(p0);

    for (int i = 0; i < steps; ++i) {
      observer.predict(inputs.get(i), dt);
      observer.correct(inputs.get(i), measurements.get(i));
    }
    T.near(trueKv, observer.getXhat(2), trueKv * 0.5, "kV");
    T.near(trueKa, observer.getXhat(3), trueKa * 0.5, "kA");
  }
}
