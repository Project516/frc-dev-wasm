package dev.project516.frcdevwasm.vendor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** The jar transform as the browser runs it, with the Impl index that the build put in this jar. */
public final class VendorTransform {
  private static NativeStubber stubber;

  private VendorTransform() {}

  /**
   * Writes the transformed jar to {@code out} and returns "changed". Returns "unchanged" and writes
   * nothing when {@code in} needs no change.
   */
  public static synchronized String transform(String in, String out) throws IOException {
    if (stubber == null) {
      try (InputStream index = VendorTransform.class.getResourceAsStream("impls.txt")) {
        if (index == null) {
          throw new IOException("impls.txt is missing from the tools jar");
        }
        stubber = NativeStubber.fromIndex(index);
      }
    }
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    boolean changed;
    try (InputStream is = new FileInputStream(in)) {
      changed = stubber.transform(is, buffer, new File(in).getName());
    }
    if (!changed) {
      return "unchanged";
    }
    File parent = new File(out).getAbsoluteFile().getParentFile();
    if (parent != null) {
      parent.mkdirs();
    }
    try (OutputStream os = new FileOutputStream(out)) {
      buffer.writeTo(os);
    }
    return "changed";
  }
}
