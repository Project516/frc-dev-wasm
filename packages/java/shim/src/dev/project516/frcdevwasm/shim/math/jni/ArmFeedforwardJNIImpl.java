package dev.project516.frcdevwasm.shim.math.jni;

public final class ArmFeedforwardJNIImpl {
  private ArmFeedforwardJNIImpl() {}

  /** Value, first and second derivative with respect to the voltage. */
  private record Jet(double v, double g, double h) {
    static Jet constant(double v) {
      return new Jet(v, 0.0, 0.0);
    }

    Jet plus(Jet o) {
      return new Jet(v + o.v, g + o.g, h + o.h);
    }

    Jet minus(Jet o) {
      return new Jet(v - o.v, g - o.g, h - o.h);
    }

    Jet times(double s) {
      return new Jet(v * s, g * s, h * s);
    }

    Jet cos() {
      double c = Math.cos(v);
      double s = Math.sin(v);
      return new Jet(c, -s * g, -c * g * g - s * h);
    }
  }

  private static final class Plant {
    final double ks;
    final double kv;
    final double ka;
    final double kg;
    final double dt;
    final double currentAngle;
    final double currentVelocity;
    final double nextVelocity;
    double cost;
    double gradient;
    double hessian;

    Plant(double ks, double kv, double ka, double kg, double dt, double angle, double vel, double next) {
      this.ks = ks;
      this.kv = kv;
      this.ka = ka;
      this.kg = kg;
      this.dt = dt;
      this.currentAngle = angle;
      this.currentVelocity = vel;
      this.nextVelocity = next;
    }

    /** Arm dynamics: d(angle) = velocity, d(velocity) = (-kv v + u - ks sgn(v) - kg cos(angle)) / ka. */
    private Jet[] f(Jet x0, Jet x1, Jet u) {
      Jet c =
          Jet.constant(-(ks / ka) * Math.signum(x1.v())).minus(x0.cos().times(kg / ka));
      return new Jet[] {x1, x1.times(-kv / ka).plus(u.times(1.0 / ka)).plus(c)};
    }

    /** Next-velocity miss and its derivatives for voltage u over one RK4 step. */
    void evaluate(double voltage) {
      Jet u = new Jet(voltage, 1.0, 0.0);
      Jet x0 = Jet.constant(currentAngle);
      Jet x1 = Jet.constant(currentVelocity);
      double h = dt;
      Jet[] k1 = f(x0, x1, u);
      Jet[] k2 = f(x0.plus(k1[0].times(h * 0.5)), x1.plus(k1[1].times(h * 0.5)), u);
      Jet[] k3 = f(x0.plus(k2[0].times(h * 0.5)), x1.plus(k2[1].times(h * 0.5)), u);
      Jet[] k4 = f(x0.plus(k3[0].times(h)), x1.plus(k3[1].times(h)), u);
      Jet next = x1.plus(k1[1].plus(k2[1].times(2.0)).plus(k3[1].times(2.0)).plus(k4[1]).times(h / 6.0));
      Jet d = Jet.constant(nextVelocity).minus(next);
      cost = d.v() * d.v();
      gradient = 2.0 * d.v() * d.g();
      hessian = 2.0 * (d.g() * d.g() + d.v() * d.h());
    }
  }

  private static double sgn(double x) {
    return (0 < x ? 1 : 0) - (x < 0 ? 1 : 0);
  }

  public static double calculate(
      double ks,
      double kv,
      double ka,
      double kg,
      double currentAngle,
      double currentVelocity,
      double nextVelocity,
      double dt) {
    if (kv < 0) {
      kv = 0;
    }
    if (ka < 0) {
      ka = 0;
    }
    if (dt <= 0) {
      dt = 0.02;
    }

    double acceleration = (nextVelocity - currentVelocity) / dt;
    double guess =
        ks * sgn(currentVelocity)
            + kv * currentVelocity
            + ka * acceleration
            + kg * Math.cos(currentAngle);

    // small kA makes the solver ill-conditioned
    if (ka < 1e-1) {
      return guess;
    }

    Plant plant = new Plant(ks, kv, ka, kg, dt, currentAngle, currentVelocity, nextVelocity);
    double x = guess;
    plant.evaluate(x);

    double errorK = Double.POSITIVE_INFINITY;
    double errorK1 = Math.abs(plant.gradient);
    for (int iteration = 0; iteration < 50 && errorK1 < (1.0 - 1e-10) * errorK; ++iteration) {
      errorK = errorK1;

      // Newton step with the Hessian regularized to at least 1e-4
      double step = -plant.gradient / Math.max(plant.hessian, 1e-4);

      double oldCost = plant.cost;
      double alpha = 1.0;
      double trial = x + alpha * step;
      plant.evaluate(trial);
      while (plant.cost > oldCost) {
        alpha *= 0.5;
        trial = x + alpha * step;
        plant.evaluate(trial);
      }
      x = trial;
      errorK1 = Math.abs(plant.gradient);
    }
    return x;
  }
}
