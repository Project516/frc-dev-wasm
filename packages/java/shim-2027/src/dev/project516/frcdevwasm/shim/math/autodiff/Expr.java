package dev.project516.frcdevwasm.shim.math.autodiff;

/**
 * An expression node of Sleipnir's autodiff graph (slp::detail::Expression). One class with an
 * operator code instead of a subclass per operator. The factories prune and fold constants exactly
 * as Sleipnir does, since that decides expression types and graph shapes.
 */
public final class Expr {
  public static final int NONE = 0;
  public static final int CONSTANT = 1;
  public static final int LINEAR = 2;
  public static final int QUADRATIC = 3;
  public static final int NONLINEAR = 4;

  static final byte CONST = 0;
  static final byte DECISION = 1;
  static final byte PLUS = 2;
  static final byte MINUS = 3;
  static final byte NEG = 4;
  static final byte MUL = 5;
  static final byte DIV = 6;
  static final byte ABS = 7;
  static final byte ACOS = 8;
  static final byte ASIN = 9;
  static final byte ATAN = 10;
  static final byte ATAN2 = 11;
  static final byte CBRT = 12;
  static final byte COS = 13;
  static final byte COSH = 14;
  static final byte EXP = 15;
  static final byte HYPOT = 16;
  static final byte LOG = 17;
  static final byte LOG10 = 18;
  static final byte MAX = 19;
  static final byte MIN = 20;
  static final byte POW = 21;
  static final byte SIGN = 22;
  static final byte SIN = 23;
  static final byte SINH = 24;
  static final byte SQRT = 25;
  static final byte TAN = 26;
  static final byte TANH = 27;
  static final byte IS_NONNEGATIVE = 28;
  static final byte IS_POSITIVE = 29;

  private static final double LN10 = 2.302585092994046;

  public double val;
  double adjoint;
  Expr adjointExpr;
  final Expr lhs;
  final Expr rhs;
  /** Incoming edge count during topological sorts, or a Jacobian column; -1 otherwise. */
  int scratch = -1;
  final byte op;
  final byte type;
  /** Topological sort cached by Variable value(); the graph below a node never changes. */
  Expr[] graph;

  private Expr(byte op, int type, double val, Expr lhs, Expr rhs) {
    this.op = op;
    this.type = (byte) type;
    this.val = val;
    this.lhs = lhs;
    this.rhs = rhs;
  }

  public int type() {
    return type;
  }

  public boolean isDependent() {
    return lhs != null;
  }

  public static Expr constant(double value) {
    return new Expr(CONST, CONSTANT, value, null, null);
  }

  public static Expr decision(double value) {
    return new Expr(DECISION, LINEAR, value, null, null);
  }

  private static Expr unary(byte op, int type, Expr x) {
    return new Expr(op, type, 0.0, x, null);
  }

  private static Expr binary(byte op, int type, Expr l, Expr r) {
    return new Expr(op, type, 0.0, l, r);
  }

  boolean isConstant(double c) {
    return type == CONSTANT && val == c;
  }

  // Operator factories, in the order of sleipnir/autodiff/expression.hpp.

  public static Expr mul(Expr l, Expr r) {
    if (l.isConstant(0.0)) {
      return l;
    } else if (r.isConstant(0.0)) {
      return r;
    } else if (l.isConstant(1.0)) {
      return r;
    } else if (r.isConstant(1.0)) {
      return l;
    }
    if (l.type == CONSTANT && r.type == CONSTANT) {
      return constant(l.val * r.val);
    }
    int t;
    if (l.type == CONSTANT) {
      t = r.type;
    } else if (r.type == CONSTANT) {
      t = l.type;
    } else if (l.type == LINEAR && r.type == LINEAR) {
      t = QUADRATIC;
    } else {
      t = NONLINEAR;
    }
    return binary(MUL, t, l, r);
  }

  public static Expr div(Expr l, Expr r) {
    if (l.isConstant(0.0)) {
      return l;
    } else if (r.isConstant(1.0)) {
      return l;
    }
    if (l.type == CONSTANT && r.type == CONSTANT) {
      return constant(l.val / r.val);
    }
    return binary(DIV, r.type == CONSTANT ? l.type : NONLINEAR, l, r);
  }

  /** Null operands are allowed because adjoint accumulation starts from none. */
  public static Expr plus(Expr l, Expr r) {
    if (l == null || l.isConstant(0.0)) {
      return r;
    } else if (r == null || r.isConstant(0.0)) {
      return l;
    }
    if (l.type == CONSTANT && r.type == CONSTANT) {
      return constant(l.val + r.val);
    }
    return binary(PLUS, Math.max(l.type, r.type), l, r);
  }

  public static Expr minus(Expr l, Expr r) {
    if (l.isConstant(0.0)) {
      return r.isConstant(0.0) ? r : neg(r);
    } else if (r.isConstant(0.0)) {
      return l;
    }
    if (l.type == CONSTANT && r.type == CONSTANT) {
      return constant(l.val - r.val);
    }
    return binary(MINUS, Math.max(l.type, r.type), l, r);
  }

  public static Expr neg(Expr x) {
    if (x.isConstant(0.0)) {
      return x;
    }
    if (x.type == CONSTANT) {
      return constant(-x.val);
    }
    return unary(NEG, x.type, x);
  }

  public static Expr cbrt(Expr x) {
    if (x.type == CONSTANT) {
      if (x.val == 0.0 || x.val == -1.0 || x.val == 1.0) {
        return x;
      }
      return constant(Math.cbrt(x.val));
    }
    return unary(CBRT, NONLINEAR, x);
  }

  public static Expr abs(Expr x) {
    if (x.isConstant(0.0)) {
      return x;
    }
    if (x.type == CONSTANT) {
      return constant(Math.abs(x.val));
    }
    return unary(ABS, NONLINEAR, x);
  }

  public static Expr acos(Expr x) {
    if (x.isConstant(0.0)) {
      return constant(Math.PI / 2.0);
    }
    if (x.type == CONSTANT) {
      return constant(Math.acos(x.val));
    }
    return unary(ACOS, NONLINEAR, x);
  }

  public static Expr asin(Expr x) {
    if (x.isConstant(0.0)) {
      return x;
    }
    if (x.type == CONSTANT) {
      return constant(Math.asin(x.val));
    }
    return unary(ASIN, NONLINEAR, x);
  }

  public static Expr atan(Expr x) {
    if (x.isConstant(0.0)) {
      return x;
    }
    if (x.type == CONSTANT) {
      return constant(Math.atan(x.val));
    }
    return unary(ATAN, NONLINEAR, x);
  }

  public static Expr atan2(Expr y, Expr x) {
    if (y.type == CONSTANT && x.type == CONSTANT) {
      return constant(Math.atan2(y.val, x.val));
    }
    return binary(ATAN2, NONLINEAR, y, x);
  }

  public static Expr cos(Expr x) {
    if (x.isConstant(0.0)) {
      return constant(1.0);
    }
    if (x.type == CONSTANT) {
      return constant(Math.cos(x.val));
    }
    return unary(COS, NONLINEAR, x);
  }

  public static Expr cosh(Expr x) {
    if (x.isConstant(0.0)) {
      return constant(1.0);
    }
    if (x.type == CONSTANT) {
      return constant(Math.cosh(x.val));
    }
    return unary(COSH, NONLINEAR, x);
  }

  public static Expr exp(Expr x) {
    if (x.isConstant(0.0)) {
      return constant(1.0);
    }
    if (x.type == CONSTANT) {
      return constant(Math.exp(x.val));
    }
    return unary(EXP, NONLINEAR, x);
  }

  public static Expr hypot(Expr x, Expr y) {
    if (x.isConstant(0.0)) {
      return abs(y);
    } else if (y.isConstant(0.0)) {
      return abs(x);
    }
    if (x.type == CONSTANT && y.type == CONSTANT) {
      return constant(Math.hypot(x.val, y.val));
    }
    return binary(HYPOT, NONLINEAR, x, y);
  }

  static Expr isNonnegative(Expr x) {
    if (x.type == CONSTANT) {
      return constant(x.val >= 0.0 ? 1.0 : 0.0);
    }
    return unary(IS_NONNEGATIVE, NONLINEAR, x);
  }

  static Expr isPositive(Expr x) {
    if (x.type == CONSTANT) {
      return constant(x.val > 0.0 ? 1.0 : 0.0);
    }
    return unary(IS_POSITIVE, NONLINEAR, x);
  }

  /** Sleipnir returns the zero itself for log(0), not -infinity. */
  public static Expr log(Expr x) {
    if (x.isConstant(0.0)) {
      return x;
    }
    if (x.type == CONSTANT) {
      return constant(Math.log(x.val));
    }
    return unary(LOG, NONLINEAR, x);
  }

  public static Expr log10(Expr x) {
    if (x.isConstant(0.0)) {
      return x;
    }
    if (x.type == CONSTANT) {
      return constant(Math.log10(x.val));
    }
    return unary(LOG10, NONLINEAR, x);
  }

  public static Expr max(Expr a, Expr b) {
    if (a.type == CONSTANT && b.type == CONSTANT) {
      return constant(stdMax(a.val, b.val));
    }
    return binary(MAX, NONLINEAR, a, b);
  }

  public static Expr min(Expr a, Expr b) {
    if (a.type == CONSTANT && b.type == CONSTANT) {
      return constant(stdMin(a.val, b.val));
    }
    return binary(MIN, NONLINEAR, a, b);
  }

  public static Expr pow(Expr base, Expr power) {
    if (base.isConstant(0.0) || base.isConstant(1.0)) {
      return base;
    }
    if (power.isConstant(0.0)) {
      return constant(1.0);
    } else if (power.isConstant(1.0)) {
      return base;
    }
    if (base.type == CONSTANT && power.type == CONSTANT) {
      return constant(Math.pow(base.val, power.val));
    }
    if (power.isConstant(2.0)) {
      return binary(MUL, base.type == LINEAR ? QUADRATIC : NONLINEAR, base, base);
    }
    return binary(POW, NONLINEAR, base, power);
  }

  public static Expr sign(Expr x) {
    if (x.type == CONSTANT) {
      if (x.val < 0.0) {
        return constant(-1.0);
      } else if (x.val == 0.0) {
        return x;
      }
      return constant(1.0);
    }
    return unary(SIGN, NONLINEAR, x);
  }

  public static Expr sin(Expr x) {
    if (x.isConstant(0.0)) {
      return x;
    }
    if (x.type == CONSTANT) {
      return constant(Math.sin(x.val));
    }
    return unary(SIN, NONLINEAR, x);
  }

  public static Expr sinh(Expr x) {
    if (x.isConstant(0.0)) {
      return x;
    }
    if (x.type == CONSTANT) {
      return constant(Math.sinh(x.val));
    }
    return unary(SINH, NONLINEAR, x);
  }

  public static Expr sqrt(Expr x) {
    if (x.type == CONSTANT) {
      if (x.val == 0.0 || x.val == 1.0) {
        return x;
      }
      return constant(Math.sqrt(x.val));
    }
    return unary(SQRT, NONLINEAR, x);
  }

  public static Expr tan(Expr x) {
    if (x.isConstant(0.0)) {
      return x;
    }
    if (x.type == CONSTANT) {
      return constant(Math.tan(x.val));
    }
    return unary(TAN, NONLINEAR, x);
  }

  public static Expr tanh(Expr x) {
    if (x.isConstant(0.0)) {
      return x;
    }
    if (x.type == CONSTANT) {
      return constant(Math.tanh(x.val));
    }
    return unary(TANH, NONLINEAR, x);
  }

  /** std::max, which keeps a when the comparison is false (NaN, equal values). */
  static double stdMax(double a, double b) {
    return a < b ? b : a;
  }

  static double stdMin(double a, double b) {
    return b < a ? b : a;
  }

  /** The node's value from its operands' values. */
  double value(double l, double r) {
    switch (op) {
      case CONST:
      case DECISION:
        return val;
      case PLUS:
        return l + r;
      case MINUS:
        return l - r;
      case NEG:
        return -l;
      case MUL:
        return l * r;
      case DIV:
        return l / r;
      case ABS:
        return Math.abs(l);
      case ACOS:
        return Math.acos(l);
      case ASIN:
        return Math.asin(l);
      case ATAN:
        return Math.atan(l);
      case ATAN2:
        return Math.atan2(l, r);
      case CBRT:
        return Math.cbrt(l);
      case COS:
        return Math.cos(l);
      case COSH:
        return Math.cosh(l);
      case EXP:
        return Math.exp(l);
      case HYPOT:
        return Math.hypot(l, r);
      case LOG:
        return Math.log(l);
      case LOG10:
        return Math.log10(l);
      case MAX:
        return stdMax(l, r);
      case MIN:
        return stdMin(l, r);
      case POW:
        return Math.pow(l, r);
      case SIGN:
        return l < 0.0 ? -1.0 : l == 0.0 ? 0.0 : 1.0;
      case SIN:
        return Math.sin(l);
      case SINH:
        return Math.sinh(l);
      case SQRT:
        return Math.sqrt(l);
      case TAN:
        return Math.tan(l);
      case TANH:
        return Math.tanh(l);
      case IS_NONNEGATIVE:
        return l >= 0.0 ? 1.0 : 0.0;
      case IS_POSITIVE:
        return l > 0.0 ? 1.0 : 0.0;
      default:
        throw new IllegalStateException();
    }
  }

  /** The adjoint contribution to the left operand. */
  double gradL(double l, double r) {
    double a = adjoint;
    switch (op) {
      case PLUS:
      case MINUS:
        return a;
      case NEG:
        return -a;
      case MUL:
        return a * r;
      case DIV:
        return a / r;
      case ABS:
        return l < 0.0 ? -a : l > 0.0 ? a : 0.0;
      case ACOS:
        return -a / Math.sqrt(1.0 - l * l);
      case ASIN:
        return a / Math.sqrt(1.0 - l * l);
      case ATAN:
        return a / (1.0 + l * l);
      case ATAN2:
        return a * r / (l * l + r * r);
      case CBRT: {
        double c = Math.cbrt(l);
        return a / (3.0 * c * c);
      }
      case COS:
        return a * -Math.sin(l);
      case COSH:
        return a * Math.sinh(l);
      case EXP:
        return a * Math.exp(l);
      case HYPOT:
        return a * l / Math.hypot(l, r);
      case LOG:
        return a / l;
      case LOG10:
        return a / (LN10 * l);
      case MAX:
        return l >= r ? a : 0.0;
      case MIN:
        return l <= r ? a : 0.0;
      case POW:
        return a * Math.pow(l, r - 1.0) * r;
      case SIN:
        return a * Math.cos(l);
      case SINH:
        return a * Math.cosh(l);
      case SQRT:
        return a / (2.0 * Math.sqrt(l));
      case TAN: {
        double c = Math.cos(l);
        return a / (c * c);
      }
      case TANH: {
        double c = Math.cosh(l);
        return a / (c * c);
      }
      default:
        return 0.0;
    }
  }

  /** The adjoint contribution to the right operand. */
  double gradR(double l, double r) {
    double a = adjoint;
    switch (op) {
      case PLUS:
        return a;
      case MINUS:
        return -a;
      case MUL:
        return a * l;
      case DIV:
        return a * -l / (r * r);
      case ATAN2:
        return a * -l / (l * l + r * r);
      case HYPOT:
        return a * r / Math.hypot(l, r);
      case MAX:
        return l >= r ? 0.0 : a;
      case MIN:
        return l <= r ? 0.0 : a;
      case POW:
        return a * Math.pow(l, r) * Math.log(l);
      default:
        return 0.0;
    }
  }

  /** The adjoint contribution to the left operand as an expression. */
  Expr gradExprL(Expr l, Expr r) {
    Expr a = adjointExpr;
    switch (op) {
      case PLUS:
      case MINUS:
        return a;
      case NEG:
        return neg(a);
      case MUL:
        return mul(a, r);
      case DIV:
        return div(a, r);
      case ABS:
        return mul(a, sign(l));
      case ACOS:
        return div(neg(a), sqrt(minus(constant(1.0), mul(l, l))));
      case ASIN:
        return div(a, sqrt(minus(constant(1.0), mul(l, l))));
      case ATAN:
        return div(a, plus(constant(1.0), mul(l, l)));
      case ATAN2:
        return div(mul(a, r), plus(mul(l, l), mul(r, r)));
      case CBRT: {
        Expr c = cbrt(l);
        return div(a, mul(mul(constant(3.0), c), c));
      }
      case COS:
        return mul(a, neg(sin(l)));
      case COSH:
        return mul(a, sinh(l));
      case EXP:
        return mul(a, exp(l));
      case HYPOT:
        return div(mul(a, l), hypot(l, r));
      case LOG:
        return div(a, l);
      case LOG10:
        return div(a, mul(constant(LN10), l));
      case MAX:
        return mul(a, isNonnegative(minus(l, r)));
      case MIN:
        return mul(a, isNonnegative(minus(r, l)));
      case POW:
        return mul(mul(a, pow(l, minus(r, constant(1.0)))), r);
      case SIN:
        return mul(a, cos(l));
      case SINH:
        return mul(a, cosh(l));
      case SQRT:
        return div(a, mul(constant(2.0), sqrt(l)));
      case TAN: {
        Expr c = cos(l);
        return div(a, mul(c, c));
      }
      case TANH: {
        Expr c = cosh(l);
        return div(a, mul(c, c));
      }
      default:
        return constant(0.0);
    }
  }

  /** The adjoint contribution to the right operand as an expression. */
  Expr gradExprR(Expr l, Expr r) {
    Expr a = adjointExpr;
    switch (op) {
      case PLUS:
        return a;
      case MINUS:
        return neg(a);
      case MUL:
        return mul(a, l);
      case DIV:
        return div(mul(a, neg(l)), mul(r, r));
      case ATAN2:
        return div(mul(a, neg(l)), plus(mul(l, l), mul(r, r)));
      case HYPOT:
        return div(mul(a, r), hypot(l, r));
      case MAX:
        return mul(a, isPositive(minus(r, l)));
      case MIN:
        return mul(a, isPositive(minus(l, r)));
      case POW:
        return mul(mul(a, pow(l, r)), log(l));
      default:
        return constant(0.0);
    }
  }
}
