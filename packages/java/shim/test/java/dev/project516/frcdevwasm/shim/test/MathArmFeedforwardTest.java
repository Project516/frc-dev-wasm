package dev.project516.frcdevwasm.shim.test;

import edu.wpi.first.math.MatBuilder;
import edu.wpi.first.math.Matrix;
import edu.wpi.first.math.Nat;
import edu.wpi.first.math.controller.ArmFeedforward;
import edu.wpi.first.math.jni.ArmFeedforwardJNI;
import edu.wpi.first.math.jni.WPIMathJNI;
import edu.wpi.first.math.numbers.N1;
import edu.wpi.first.math.numbers.N2;
import edu.wpi.first.math.system.NumericalIntegration;
import java.util.function.BiFunction;

/** ArmFeedforward.calculateWithVelocities, from allwpilib ArmFeedforwardTest. */
public final class MathArmFeedforwardTest {
  static {
    WPIMathJNI.Helper.setExtractOnStaticLoad(false);
  }

  private static Matrix<N2, N1> simulate(
      double ks, double kv, double ka, double kg, double currentAngle, double currentVelocity, double input, double dt) {
    final Matrix<N2, N2> a = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {0.0, 1.0, 0.0, -kv / ka});
    final Matrix<N2, N1> b = new Matrix<>(Nat.N2(), Nat.N1(), new double[] {0.0, 1.0 / ka});

    final BiFunction<Matrix<N2, N1>, Matrix<N1, N1>, Matrix<N2, N1>> f =
        (x, u) -> {
          Matrix<N2, N1> c =
              MatBuilder.fill(
                  Nat.N2(),
                  Nat.N1(),
                  0.0,
                  Math.signum(x.get(1, 0)) * (-ks / ka) - (kg / ka) * Math.cos(x.get(0, 0)));
          return a.times(x).plus(b.times(u)).plus(c);
        };

    return NumericalIntegration.rk4(
        f,
        MatBuilder.fill(Nat.N2(), Nat.N1(), currentAngle, currentVelocity),
        MatBuilder.fill(Nat.N1(), Nat.N1(), input),
        dt);
  }

  private static void calculateAndSimulate(
      ArmFeedforward armFF, double ks, double kv, double ka, double kg, double currentAngle, double currentVelocity, double nextVelocity, double dt) {
    final double input = armFF.calculateWithVelocities(currentAngle, currentVelocity, nextVelocity);
    T.near(nextVelocity, simulate(ks, kv, ka, kg, currentAngle, currentVelocity, input, dt).get(1, 0), 1e-4, "next velocity");
  }

  public static void testCalculate() {
    final double ks = 0.5;
    final double kv = 1.5;
    final double ka = 2;
    final double kg = 1;
    final ArmFeedforward armFF = new ArmFeedforward(ks, kg, kv, ka);

    T.near(0.5, armFF.calculate(Math.PI / 3, 0.0), 0.002, "static");
    T.near(2.5, armFF.calculate(Math.PI / 3, 1.0), 0.002, "moving");

    calculateAndSimulate(armFF, ks, kv, ka, kg, Math.PI / 3, 1.0, 1.05, 0.020);
    calculateAndSimulate(armFF, ks, kv, ka, kg, Math.PI / 3, 1.0, 0.95, 0.020);
    calculateAndSimulate(armFF, ks, kv, ka, kg, -Math.PI / 3, 1.0, 1.05, 0.020);
    calculateAndSimulate(armFF, ks, kv, ka, kg, -Math.PI / 3, 1.0, 0.95, 0.020);
  }

  public static void testCalculateIllConditionedModel() {
    final double ks = 0.39671;
    final double kv = 2.7167;
    final double ka = 1e-2;
    final double kg = 0.2708;
    final ArmFeedforward armFF = new ArmFeedforward(ks, kg, kv, ka);

    final double currentAngle = 1.0;
    final double currentVelocity = 0.02;
    final double nextVelocity = 0.0;
    var averageAccel = (nextVelocity - currentVelocity) / 0.02;

    T.eq(
        ks + kv * currentVelocity + ka * averageAccel + kg * Math.cos(currentAngle),
        armFF.calculateWithVelocities(currentAngle, currentVelocity, nextVelocity));
  }

  public static void testCalculateIllConditionedGradient() {
    final double ks = 0.39671;
    final double kv = 2.7167;
    final double ka = 0.50799;
    final double kg = 0.2708;
    final ArmFeedforward armFF = new ArmFeedforward(ks, kg, kv, ka);
    calculateAndSimulate(armFF, ks, kv, ka, kg, 1.0, 0.02, 0.0, 0.02);
  }

  public static void testAchievableVelocity() {
    final ArmFeedforward armFF = new ArmFeedforward(0.5, 1, 1.5, 2);
    T.near(6, armFF.maxAchievableVelocity(12, Math.PI / 3, 1), 0.002, "max");
    T.near(-9, armFF.minAchievableVelocity(11.5, Math.PI / 3, 1), 0.002, "min");
  }

  public static void testAchievableAcceleration() {
    final ArmFeedforward armFF = new ArmFeedforward(0.5, 1, 1.5, 2);
    T.near(4.75, armFF.maxAchievableAcceleration(12, Math.PI / 3, 1), 0.002, "max +");
    T.near(6.75, armFF.maxAchievableAcceleration(12, Math.PI / 3, -1), 0.002, "max -");
    T.near(-7.25, armFF.minAchievableAcceleration(12, Math.PI / 3, 1), 0.002, "min +");
    T.near(-5.25, armFF.minAchievableAcceleration(12, Math.PI / 3, -1), 0.002, "min -");
  }

  public static void testNegativeGains() {
    T.throwsA(IllegalArgumentException.class, () -> new ArmFeedforward(0.5, 1, -1.5, 2));
    T.throwsA(IllegalArgumentException.class, () -> new ArmFeedforward(0.5, 1, 1.5, -2));
  }

  public static void testRawCallAgreesWithTheWrapper() {
    var ff = new ArmFeedforward(0.3, 0.7, 1.9, 0.8, 0.01);
    double viaWrapper = ff.calculateWithVelocities(0.4, 0.5, 0.6);
    double raw = ArmFeedforwardJNI.calculate(0.3, 1.9, 0.8, 0.7, 0.4, 0.5, 0.6, 0.01);
    T.eq(viaWrapper, raw);
  }

  public static void testHitsTheTargetVelocityAcrossManyStates() {
    var rng = new java.util.Random(6);
    for (int i = 0; i < 300; i++) {
      double ks = rng.nextDouble();
      double kv = 0.5 + 3 * rng.nextDouble();
      double ka = 0.15 + rng.nextDouble();
      double kg = rng.nextDouble() * 2;
      double dt = 0.005 + 0.03 * rng.nextDouble();
      double angle = (rng.nextDouble() - 0.5) * 6;
      double vel = (rng.nextDouble() - 0.5) * 6;
      double next = vel + (rng.nextDouble() - 0.5) * 0.4;
      var ff = new ArmFeedforward(ks, kg, kv, ka, dt);
      double u = ff.calculateWithVelocities(angle, vel, next);
      double reached = simulate(ks, kv, ka, kg, angle, vel, u, dt).get(1, 0);
      // ks changes sign with velocity, so crossing zero velocity may leave a small miss
      boolean crosses = vel * next <= 0 || Math.abs(vel) < 0.05;
      T.near(next, reached, crosses ? 0.05 : 1e-4, "case " + i);
    }
  }

  public static void testVoltageEqualsFeedforwardInTheSmallGainLimit() {
    // for ka < 0.1 the closed form is used
    var ff = new ArmFeedforward(0.2, 0.3, 1.1, 0.05, 0.02);
    double expected = 0.2 * Math.signum(2.0) + 0.3 * Math.cos(0.9) + 1.1 * 2.0 + 0.05 * (2.5 - 2.0) / 0.02;
    T.near(expected, ff.calculateWithVelocities(0.9, 2.0, 2.5), 1e-12, "closed form");
  }
}
