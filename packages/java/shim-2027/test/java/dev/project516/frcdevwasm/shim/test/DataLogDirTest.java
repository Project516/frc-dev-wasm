package dev.project516.frcdevwasm.shim.test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.wpilib.datalog.DataLogBackgroundWriter;
import org.wpilib.datalog.IntegerLogEntry;
import org.wpilib.util.WPIUtilJNIWrapper;

/** DataLogBackgroundWriter.setDir and getDir, which move the log file to another directory. */
public final class DataLogDirTest {
  static {
    WPIUtilJNIWrapper.Helper.setExtractOnStaticLoad(false);
  }

  public static void testSetDirMovesTheFile() throws IOException {
    Path from = Files.createTempDirectory("datalog-from");
    Path to = Files.createTempDirectory("datalog-to");
    try (DataLogBackgroundWriter log = new DataLogBackgroundWriter(from.toString(), "a.wpilog")) {
      T.eq(from.toString(), log.getDir());
      new IntegerLogEntry(log, "n").append(1);
      log.setDir(to.toString(), 1 << 20);
      T.eq(to.toString(), log.getDir());
      new IntegerLogEntry(log, "m").append(2);
    }
    T.check(!new File(from.toFile(), "a.wpilog").exists(), "old file removed");
    long size = new File(to.toFile(), "a.wpilog").length();
    T.check(size > 0, "log in the new directory");
    // Both entries are in the moved file: the second was appended after the move.
    T.check(Files.readString(to.resolve("a.wpilog"), java.nio.charset.StandardCharsets.ISO_8859_1).contains("m"), "later records follow");
  }

  public static void testSetDirKeepsTheDirOnFailure() throws IOException {
    Path from = Files.createTempDirectory("datalog-from");
    Path to = Files.createTempDirectory("datalog-to");
    Files.writeString(to.resolve("b.wpilog"), "taken");
    try (DataLogBackgroundWriter log = new DataLogBackgroundWriter(from.toString(), "b.wpilog")) {
      new IntegerLogEntry(log, "n").append(1);
      log.setDir(to.toString(), 1 << 20);
      T.eq(from.toString(), log.getDir(), "existing files are never overwritten");
      log.setDir(from.resolve("missing").toString(), 1 << 20);
      T.eq(from.toString(), log.getDir(), "the directory must exist");
      log.setDir(Files.createTempDirectory("datalog-other").toString(), 0);
      T.eq(from.toString(), log.getDir(), "too big");
    }
    T.eq("taken", Files.readString(to.resolve("b.wpilog")));
  }

  public static void testSetDirRejectsANegativeSize() throws IOException {
    Path dir = Files.createTempDirectory("datalog-negative");
    T.throwsA(IllegalArgumentException.class, () -> {
      try (DataLogBackgroundWriter log = new DataLogBackgroundWriter(dir.toString(), "c.wpilog")) {
        log.setDir("x", -1);
      }
    });
  }
}
