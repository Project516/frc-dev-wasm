package dev.project516.frcdevwasm.shim.math.autodiff;

import java.util.Arrays;

/** Graph algorithms of sleipnir/autodiff/expression_graph.hpp and gradient_tree(). */
public final class Graph {
  private Graph() {}

  static final Expr[] EMPTY = new Expr[0];

  /** Topological sort from parent to child; empty for constant roots. */
  public static Expr[] topologicalSort(Expr root) {
    if (root == null || root.type == Expr.CONSTANT) {
      return EMPTY;
    }
    Expr[] stack = new Expr[16];
    int top = 0;
    int count = 1;
    stack[top++] = root;
    while (top > 0) {
      Expr node = stack[--top];
      Expr l = node.lhs;
      Expr r = node.rhs;
      if (l != null && ++l.scratch == 0) {
        stack = push(stack, top++, l);
        count++;
      }
      if (r != null && ++r.scratch == 0) {
        stack = push(stack, top++, r);
        count++;
      }
    }
    Expr[] list = new Expr[count];
    int n = 0;
    stack[top++] = root;
    while (top > 0) {
      Expr node = stack[--top];
      list[n++] = node;
      Expr l = node.lhs;
      Expr r = node.rhs;
      if (l != null && --l.scratch == -1) {
        stack = push(stack, top++, l);
      }
      if (r != null && --r.scratch == -1) {
        stack = push(stack, top++, r);
      }
    }
    return list;
  }

  private static Expr[] push(Expr[] stack, int at, Expr e) {
    if (at == stack.length) {
      stack = Arrays.copyOf(stack, at * 2);
    }
    stack[at] = e;
    return stack;
  }

  /** Recomputes node values from child to parent. */
  public static void updateValues(Expr[] list) {
    for (int i = list.length - 1; i >= 0; i--) {
      Expr node = list[i];
      Expr l = node.lhs;
      if (l != null) {
        Expr r = node.rhs;
        node.val = node.value(l.val, r != null ? r.val : 0.0);
      }
    }
  }

  /** Reverse accumulation: sets every node's adjoint for the root of list. */
  static void computeAdjoints(Expr[] list) {
    list[0].adjoint = 1.0;
    for (int i = 1; i < list.length; i++) {
      list[i].adjoint = 0.0;
    }
    for (Expr node : list) {
      Expr l = node.lhs;
      if (l != null) {
        Expr r = node.rhs;
        if (r != null) {
          l.adjoint += node.gradL(l.val, r.val);
          r.adjoint += node.gradR(l.val, r.val);
        } else {
          l.adjoint += node.gradL(l.val, 0.0);
        }
      }
    }
  }

  /** The gradient of list's root as expressions; entries are null where wrt had no adjoint. */
  public static Expr[] gradientTree(Expr[] list, Expr[] wrt) {
    Expr[] grad = new Expr[wrt.length];
    if (list.length == 0) {
      return grad;
    }
    list[0].adjointExpr = Expr.constant(1.0);
    for (Expr node : list) {
      Expr l = node.lhs;
      if (l != null) {
        Expr r = node.rhs;
        if (r != null) {
          l.adjointExpr = Expr.plus(l.adjointExpr, node.gradExprL(l, r));
          r.adjointExpr = Expr.plus(r.adjointExpr, node.gradExprR(l, r));
        } else {
          l.adjointExpr = Expr.plus(l.adjointExpr, node.gradExprL(l, null));
        }
      }
    }
    for (int row = 0; row < wrt.length; row++) {
      grad[row] = wrt[row].adjointExpr;
      wrt[row].adjointExpr = null;
    }
    for (Expr node : list) {
      node.adjointExpr = null;
    }
    return grad;
  }
}
