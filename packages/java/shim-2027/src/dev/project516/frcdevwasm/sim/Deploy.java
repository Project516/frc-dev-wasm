package dev.project516.frcdevwasm.sim;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Fills the simulated deploy directory, where Filesystem.getDeployDirectory() looks:
 * {@code <user.dir>/src/main/deploy}. The zip named by system property frcdevwasm.deploy replaces
 * its previous contents. Plain java.io only: it behaves the same on desktop and in the browser JVM.
 */
public final class Deploy {
  private Deploy() {}

  public static void extractIfRequested() {
    String zip = System.getProperty("frcdevwasm.deploy");
    if (zip == null || zip.isEmpty()) {
      return;
    }
    try {
      File dir = new File(System.getProperty("user.dir"), "src/main/deploy").getCanonicalFile();
      deleteTree(dir);
      if (!dir.mkdirs() && !dir.isDirectory()) {
        throw new IOException("cannot create " + dir);
      }
      String root = dir.getPath() + File.separator;
      byte[] buf = new byte[8192];
      try (InputStream in = new FileInputStream(zip);
          ZipInputStream zin = new ZipInputStream(in)) {
        for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
          File target = new File(dir, e.getName()).getCanonicalFile();
          if (!target.getPath().startsWith(root)) {
            continue;
          }
          if (e.isDirectory()) {
            target.mkdirs();
            continue;
          }
          target.getParentFile().mkdirs();
          try (OutputStream out = new FileOutputStream(target)) {
            for (int n; (n = zin.read(buf)) > 0; ) {
              out.write(buf, 0, n);
            }
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      System.err.println("[frc-dev-wasm] cannot extract deploy files: " + e);
    }
  }

  private static void deleteTree(File f) {
    File[] children = f.listFiles();
    if (children != null) {
      for (File c : children) {
        deleteTree(c);
      }
    }
    f.delete();
  }
}
