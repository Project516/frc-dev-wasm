package dev.project516.frcdevwasm.shim.math.autodiff;

import org.wpilib.math.autodiff.NativeSparseTriplets;

public final class JacobianJNIImpl {
  private JacobianJNIImpl() {}

  public static long create(long[] variables, long[] wrt) {
    return Handles.add(new Jacobian(Handles.exprs(variables), Handles.exprs(wrt), false));
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
