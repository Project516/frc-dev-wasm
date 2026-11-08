package dev.project516.frcdevwasm.shim.math.autodiff;

import java.util.List;

/** A handle object that keeps expressions alive, for totalNativeMemoryUsage(). */
public interface ExprOwner {
  void roots(List<Expr> out);
}
