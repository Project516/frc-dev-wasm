package dev.project516.frcdevwasm.shim.test;

import edu.wpi.first.apriltag.AprilTag;
import edu.wpi.first.apriltag.AprilTagDetector;
import edu.wpi.first.apriltag.AprilTagFieldLayout;
import edu.wpi.first.apriltag.AprilTagFields;
import edu.wpi.first.apriltag.jni.AprilTagJNI;
import java.io.IOException;

public final class AprilTagDetectorTest {
  public static void testDefaultConfig() {
    try (AprilTagDetector d = new AprilTagDetector()) {
      T.eq(new AprilTagDetector.Config(), d.getConfig());
      T.eq(new AprilTagDetector.QuadThresholdParameters(), d.getQuadThresholdParameters());
    }
  }

  public static void testConfigRoundTrip() {
    try (AprilTagDetector d = new AprilTagDetector()) {
      AprilTagDetector.Config c = new AprilTagDetector.Config();
      c.numThreads = 3;
      c.quadDecimate = 1.5f;
      c.quadSigma = 0.25f;
      c.refineEdges = false;
      c.decodeSharpening = 0.5;
      c.debug = true;
      d.setConfig(c);
      T.eq(c, d.getConfig());
      T.check(d.getConfig() != c, "returns a copy");
      c.numThreads = 9;
      T.eq(3, d.getConfig().numThreads);

      AprilTagDetector.QuadThresholdParameters q = new AprilTagDetector.QuadThresholdParameters();
      q.minClusterPixels = 50;
      q.maxNumMaxima = 4;
      q.criticalAngle = 0.5;
      q.maxLineFitMSE = 3.5f;
      q.minWhiteBlackDiff = 9;
      q.deglitch = true;
      d.setQuadThresholdParameters(q);
      T.eq(q, d.getQuadThresholdParameters());
    }
  }

  public static void testNullConfigResetsToDefaults() {
    long det = AprilTagJNI.createDetector();
    try {
      AprilTagDetector.Config c = new AprilTagDetector.Config();
      c.numThreads = 7;
      AprilTagJNI.setDetectorConfig(det, c);
      AprilTagJNI.setDetectorConfig(det, null);
      T.eq(new AprilTagDetector.Config(), AprilTagJNI.getDetectorConfig(det));
      AprilTagJNI.setDetectorQTP(det, null);
      T.eq(new AprilTagDetector.QuadThresholdParameters(), AprilTagJNI.getDetectorQTP(det));
    } finally {
      AprilTagJNI.destroyDetector(det);
    }
  }

  public static void testDetectorsAreIndependent() {
    try (AprilTagDetector a = new AprilTagDetector();
        AprilTagDetector b = new AprilTagDetector()) {
      AprilTagDetector.Config c = new AprilTagDetector.Config();
      c.numThreads = 4;
      a.setConfig(c);
      T.eq(1, b.getConfig().numThreads);
    }
  }

  public static void testFamilies() {
    try (AprilTagDetector d = new AprilTagDetector()) {
      d.addFamily("tag36h11");
      d.addFamily("tag36h11");
      d.addFamily("tag16h5", 1);
      T.eq("unknown family name 'tag25h9'", T.throwsA(IllegalArgumentException.class, () -> d.addFamily("tag25h9")).getMessage());
      d.removeFamily("tag16h5");
      d.removeFamily("tag16h5");
      d.clearFamilies();
    }
    long det = AprilTagJNI.createDetector();
    try {
      T.check(AprilTagJNI.addFamily(det, "tag16h5", 2), "tag16h5");
      T.check(!AprilTagJNI.addFamily(det, "nope", 2), "unknown");
      T.throwsA(NullPointerException.class, () -> AprilTagJNI.addFamily(det, null, 2));
      T.throwsA(NullPointerException.class, () -> AprilTagJNI.removeFamily(det, null));
    } finally {
      AprilTagJNI.destroyDetector(det);
    }
  }

  public static void testDetectFindsNothing() {
    long det = AprilTagJNI.createDetector();
    try {
      AprilTagJNI.addFamily(det, "tag36h11", 2);
      T.eq(0, AprilTagJNI.detect(det, 640, 480, 640, 1L).length);
      T.eq("bufAddr cannot be null", T.throwsA(NullPointerException.class, () -> AprilTagJNI.detect(det, 640, 480, 640, 0L)).getMessage());
    } finally {
      AprilTagJNI.destroyDetector(det);
    }
  }

  public static void testNullHandleIsRejected() {
    T.eq("det cannot be null", T.throwsA(NullPointerException.class, () -> AprilTagJNI.getDetectorConfig(0)).getMessage());
    T.throwsA(NullPointerException.class, () -> AprilTagJNI.clearFamilies(0));
    T.throwsA(NullPointerException.class, () -> AprilTagJNI.detect(0, 1, 1, 1, 1));
    AprilTagJNI.destroyDetector(0);
  }

  public static void testUsingADestroyedDetectorFails() {
    long det = AprilTagJNI.createDetector();
    AprilTagJNI.destroyDetector(det);
    T.throwsA(IllegalArgumentException.class, () -> AprilTagJNI.getDetectorConfig(det));
  }

  public static void testFieldLayoutLoads() throws IOException {
    AprilTagFieldLayout layout = AprilTagFields.k2025ReefscapeWelded.loadAprilTagLayoutField();
    T.check(layout.getTagPose(1).isPresent(), "tag 1");
    T.check(layout.getFieldLength() > 10, "field length");
    T.check(!layout.getTags().isEmpty(), "tags");
    AprilTag first = layout.getTags().get(0);
    T.check(first.ID >= 1, "tag id");
  }

  public static void testTagImageGeneratorsAcceptAFrameAndLeaveItEmpty() {
    T.eq("frame is null", T.throwsA(NullPointerException.class, () -> AprilTagJNI.generate16h5AprilTagImage(null, 0, 1)).getMessage());
    T.eq("frame is null", T.throwsA(NullPointerException.class, () -> AprilTagJNI.generate36h11AprilTagImage(null, 0, 1)).getMessage());
    AprilTagJNI.generate16h5AprilTagImage(null, 1, 1);
    AprilTagJNI.generate36h11AprilTagImage(null, 1, 1);
  }
}
