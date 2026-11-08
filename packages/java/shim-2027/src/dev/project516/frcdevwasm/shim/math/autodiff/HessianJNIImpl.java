package dev.project516.frcdevwasm.shim.math.autodiff;

import org.wpilib.math.autodiff.NativeSparseTriplets;

/** slp::Hessian with both triangles. */
public final class HessianJNIImpl {
  private HessianJNIImpl() {}

  public static long create(long variable, long[] wrt) {
    return Handles.add(Jacobian.hessian(Handles.expr(variable), Handles.exprs(wrt), false));
  }

  public static void destroy(long handle) {
    Handles.remove(handle);
  }

  public static long[] get(long handle) {
    return Handles.addAll(((Jacobian) Handles.get(handle)).get());
  }

  public static NativeSparseTriplets value(long handle) {
    return ((Jacobian) Handles.get(handle)).value().triplets();
  }
}
