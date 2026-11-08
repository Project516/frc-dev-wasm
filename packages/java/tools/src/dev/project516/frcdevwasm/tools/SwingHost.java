package dev.project516.frcdevwasm.tools;

import java.awt.Frame;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.swing.SwingUtilities;

/** Runs a Swing application in a CheerpJ display, with a directory the page can mirror. */
public final class SwingHost {
  private SwingHost() {}

  /** Runs the main class named by args[0] with the remaining args, then maximizes its windows. */
  public static void main(String[] args) throws Exception {
    Method main = Class.forName(args[0]).getMethod("main", String[].class);
    Thread maximizer = new Thread(SwingHost::maximizeNewFrames, "frcdevwasm-maximize");
    maximizer.setDaemon(true);
    maximizer.start();
    main.invoke(null, (Object) Arrays.copyOfRange(args, 1, args.length));
  }

  private static void maximizeNewFrames() {
    Set<Frame> seen = new HashSet<>();
    while (true) {
      for (Frame frame : Frame.getFrames()) {
        if (frame.isVisible() && frame.isResizable() && seen.add(frame)) {
          SwingUtilities.invokeLater(() -> frame.setExtendedState(Frame.MAXIMIZED_BOTH));
        }
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  /** Replaces the contents of dest with the entries of a zip. */
  public static void extract(String zipPath, String dest) throws IOException {
    File root = new File(dest);
    delete(root);
    root.mkdirs();
    String rootPath = root.getCanonicalPath() + File.separator;
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(new File(zipPath).toPath()))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        File file = new File(root, entry.getName());
        if (!file.getCanonicalPath().startsWith(rootPath)) continue;
        if (entry.isDirectory()) {
          file.mkdirs();
          continue;
        }
        file.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(file)) {
          copy(zip, out);
        }
      }
    }
  }

  /** Lists files under dir as lines of "path\tsize\tmodified", with paths relative to dir. */
  public static String list(String dir) {
    StringBuilder out = new StringBuilder();
    list(new File(dir), "", out);
    return out.toString();
  }

  private static void list(File dir, String prefix, StringBuilder out) {
    File[] children = dir.listFiles();
    if (children == null) return;
    for (File child : children) {
      String path = prefix + child.getName();
      if (child.isDirectory()) {
        list(child, path + "/", out);
      } else {
        out.append(path).append('\t').append(child.length()).append('\t').append(child.lastModified()).append('\n');
      }
    }
  }

  private static void delete(File file) {
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) delete(child);
    }
    file.delete();
  }

  private static void copy(InputStream in, OutputStream out) throws IOException {
    byte[] buffer = new byte[8192];
    for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) out.write(buffer, 0, n);
  }
}
