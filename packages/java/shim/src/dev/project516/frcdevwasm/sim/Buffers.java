package dev.project516.frcdevwasm.sim;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Access to the start of a direct ByteBuffer, as the JNI layer does through
 * GetDirectBufferAddress: position and limit are ignored.
 */
public final class Buffers {
  private Buffers() {}

  /** Copy of the first n bytes; zeros when the buffer is null. */
  public static byte[] bytes(ByteBuffer b, int n) {
    byte[] out = new byte[Math.max(n, 0)];
    if (b != null) {
      for (int i = 0; i < out.length; i++) {
        out[i] = b.get(i);
      }
    }
    return out;
  }

  public static void putBytes(ByteBuffer b, byte[] data) {
    for (int i = 0; i < data.length; i++) {
      b.put(i, data[i]);
    }
  }

  /** Copy of the first n native-order ints. */
  public static int[] ints(ByteBuffer b, int n) {
    int[] out = new int[Math.max(n, 0)];
    ByteBuffer view = b.duplicate().order(ByteOrder.nativeOrder());
    for (int i = 0; i < out.length; i++) {
      out[i] = view.getInt(i * 4);
    }
    return out;
  }

  public static void putInts(ByteBuffer b, int[] data, int n) {
    ByteBuffer view = b.duplicate().order(ByteOrder.nativeOrder());
    for (int i = 0; i < n; i++) {
      view.putInt(i * 4, data[i]);
    }
  }
}
