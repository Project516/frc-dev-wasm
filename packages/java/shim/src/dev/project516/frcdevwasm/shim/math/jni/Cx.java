package dev.project516.frcdevwasm.shim.math.jni;

/** Complex scalar for the few places where clarity beats allocation. */
record Cx(double re, double im) {
  static final Cx ZERO = new Cx(0.0, 0.0);

  static Cx real(double r) {
    return new Cx(r, 0.0);
  }

  Cx plus(Cx o) {
    return new Cx(re + o.re, im + o.im);
  }

  Cx minus(Cx o) {
    return new Cx(re - o.re, im - o.im);
  }

  Cx times(Cx o) {
    return new Cx(re * o.re - im * o.im, re * o.im + im * o.re);
  }

  Cx times(double s) {
    return new Cx(re * s, im * s);
  }

  Cx div(Cx o) {
    if (Math.abs(o.im) <= Math.abs(o.re)) {
      double r = o.im / o.re;
      double d = o.re + o.im * r;
      return new Cx((re + im * r) / d, (im - re * r) / d);
    }
    double r = o.re / o.im;
    double d = o.re * r + o.im;
    return new Cx((re * r + im) / d, (im * r - re) / d);
  }

  Cx div(double s) {
    return new Cx(re / s, im / s);
  }

  Cx conj() {
    return new Cx(re, -im);
  }

  Cx neg() {
    return new Cx(-re, -im);
  }

  boolean isZero() {
    return re == 0.0 && im == 0.0;
  }

  boolean same(Cx o) {
    return re == o.re && im == o.im;
  }

  double abs() {
    return Math.hypot(re, im);
  }

  double abs2() {
    return re * re + im * im;
  }

  double norm1() {
    return Math.abs(re) + Math.abs(im);
  }

  Cx sqrt() {
    if (re == 0.0 && im == 0.0) {
      return this;
    }
    double t = Math.sqrt((Math.abs(re) + Math.hypot(re, im)) / 2.0);
    if (re >= 0.0) {
      return new Cx(t, im / (2.0 * t));
    }
    return new Cx(Math.abs(im) / (2.0 * t), Math.copySign(t, im));
  }

  Cx log() {
    return new Cx(Math.log(Math.hypot(re, im)), Math.atan2(im, re));
  }

  Cx exp() {
    double e = Math.exp(re);
    return new Cx(e * Math.cos(im), e * Math.sin(im));
  }

  Cx sinh() {
    return new Cx(Math.sinh(re) * Math.cos(im), Math.cosh(re) * Math.sin(im));
  }

  /** std::pow(complex, real) as in libstdc++. */
  Cx pow(double y) {
    if (im == 0.0 && re > 0.0) {
      return real(Math.pow(re, y));
    }
    Cx t = log();
    double r = Math.exp(y * t.re);
    double theta = y * t.im;
    return new Cx(r * Math.cos(theta), r * Math.sin(theta));
  }

  /** Eigen's complex log1p fallback. */
  Cx log1p() {
    Cx x1p = new Cx(1.0 + re, im);
    Cx l = x1p.log();
    boolean small = x1p.re == 1.0 && x1p.im == 0.0;
    boolean inf = x1p.re == l.re && x1p.im == l.im;
    if (small || inf) {
      return this;
    }
    return times(l.div(new Cx(x1p.re - 1.0, x1p.im)));
  }
}
