package dev.project516.frcdevwasm.shim.test;

import static edu.wpi.first.math.util.Units.feetToMeters;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Transform2d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.jni.TrajectoryUtilJNI;
import edu.wpi.first.math.jni.WPIMathJNI;
import edu.wpi.first.math.kinematics.DifferentialDriveKinematics;
import edu.wpi.first.math.trajectory.Trajectory;
import edu.wpi.first.math.trajectory.TrajectoryConfig;
import edu.wpi.first.math.trajectory.TrajectoryGenerator;
import edu.wpi.first.math.trajectory.TrajectoryUtil;
import edu.wpi.first.math.trajectory.constraint.DifferentialDriveKinematicsConstraint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Trajectory JSON, from allwpilib TrajectoryJsonTest, plus the C++ wire format. */
public final class MathTrajectoryUtilTest {
  static {
    WPIMathJNI.Helper.setExtractOnStaticLoad(false);
  }

  private static Trajectory sampleTrajectory() {
    var constraints = List.of(new DifferentialDriveKinematicsConstraint(new DifferentialDriveKinematics(0.5), 3));
    final double maxVelocity = feetToMeters(12.0);
    final double maxAccel = feetToMeters(12);

    var sideStart = new Pose2d(feetToMeters(1.54), feetToMeters(23.23), Rotation2d.kPi);
    var crossScale = new Pose2d(feetToMeters(23.7), feetToMeters(6.8), Rotation2d.fromDegrees(-160));

    var waypoints = new ArrayList<Pose2d>();
    waypoints.add(sideStart);
    waypoints.add(sideStart.plus(new Transform2d(new Translation2d(feetToMeters(-13), feetToMeters(0)), Rotation2d.kZero)));
    waypoints.add(sideStart.plus(new Transform2d(new Translation2d(feetToMeters(-19.5), feetToMeters(5)), Rotation2d.kCW_Pi_2)));
    waypoints.add(crossScale);

    TrajectoryConfig config = new TrajectoryConfig(maxVelocity, maxAccel).setReversed(true).addConstraints(constraints);
    return TrajectoryGenerator.generateTrajectory(waypoints, config);
  }

  public static void testDeserializeMatches() {
    var trajectory = sampleTrajectory();
    var deserialized = TrajectoryUtil.deserializeTrajectory(TrajectoryUtil.serializeTrajectory(trajectory));
    T.eq(trajectory.getStates(), deserialized.getStates());
  }

  public static void testPathweaverFileRoundTrip() throws IOException {
    var trajectory = sampleTrajectory();
    Path file = Files.createTempFile("frc-traj", ".json");
    try {
      TrajectoryUtil.toPathweaverJson(trajectory, file);
      var loaded = TrajectoryUtil.fromPathweaverJson(file);
      T.eq(trajectory.getStates(), loaded.getStates());
      T.eq(
          TrajectoryUtil.serializeTrajectory(trajectory),
          new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    } finally {
      Files.deleteIfExists(file);
    }
  }

  public static void testWireFormat() {
    // time, velocity, acceleration, x, y, rotation radians, curvature
    double[] elements = {0.0, 0.5, 1.0, 2.0, 3.0, 0.25, 0.1, 1.5, 0.0, -2.5, -1e-5, 1e20, -3.0, 123456.789};
    String json = TrajectoryUtilJNI.serializeTrajectory(elements);
    T.eq(
        "[{\"acceleration\":1.0,\"curvature\":0.1,\"pose\":{\"rotation\":{\"radians\":0.25},\"translation\":{\"x\":2.0,\"y\":3.0}},\"time\":0.0,\"velocity\":0.5},"
            + "{\"acceleration\":-2.5,\"curvature\":123456.789,\"pose\":{\"rotation\":{\"radians\":-3.0},\"translation\":{\"x\":-1e-05,\"y\":1e+20}},\"time\":1.5,\"velocity\":0.0}]",
        json);
    T.eq(elements, TrajectoryUtilJNI.deserializeTrajectory(json));
  }

  public static void testEmptyTrajectory() {
    T.eq("[]", TrajectoryUtilJNI.serializeTrajectory(new double[0]));
    T.eq(new double[0], TrajectoryUtilJNI.deserializeTrajectory("[]"));
  }

  public static void testNumberFormatting() {
    double[] values = {0.0, 1.0, -1.0, 0.5, 100.0, 1e14, 1e15, 1e16, 123456789012345.0, 0.1, 0.0001, 0.00001, 1.2345e-7, 6.02e23, -0.0, Math.PI, 1.0 / 3.0, Double.MAX_VALUE, Double.MIN_VALUE};
    String[] expected = {"0.0", "1.0", "-1.0", "0.5", "100.0", "100000000000000.0", "1e+15", "1e+16", "123456789012345.0", "0.1", "0.0001", "1e-05", "1.2345e-07", "6.02e+23", "-0.0", "3.141592653589793", "0.3333333333333333", "1.7976931348623157e+308", "5e-324"};
    for (int i = 0; i < values.length; i++) {
      double[] e = {0, 0, 0, values[i], 0, 0, 0};
      String json = TrajectoryUtilJNI.serializeTrajectory(e);
      String needle = "\"x\":" + expected[i] + ",";
      T.check(json.contains(needle), "expected " + needle + " in " + json);
      T.eq(Double.doubleToLongBits(values[i]), Double.doubleToLongBits(TrajectoryUtilJNI.deserializeTrajectory(json)[3]), "round trip " + values[i]);
    }
  }

  public static void testNonFiniteNumbersBecomeNull() {
    String json = TrajectoryUtilJNI.serializeTrajectory(new double[] {Double.NaN, 0, 0, 0, 0, 0, 0});
    T.check(json.contains("\"time\":null"), json);
    var e = T.throwsA(TrajectoryUtil.TrajectorySerializationException.class, () -> TrajectoryUtilJNI.deserializeTrajectory(json));
    T.eq("[json.exception.type_error.302] type must be number, but is null", e.getMessage());
  }

  public static void testParserAcceptsWhitespaceKeyOrderAndIntegers() {
    String json =
        "  [ { \"velocity\" : 2 , \"time\" : 1 , \"pose\" : { \"translation\" : { \"y\" : 4 , \"x\" : 3 } , \"rotation\" : { \"radians\" : 5 } },\n"
            + "\"curvature\" : 7e0 , \"acceleration\" : 6E+0 } ]  ";
    T.eq(new double[] {1, 2, 6, 3, 4, 5, 7}, TrajectoryUtilJNI.deserializeTrajectory(json));
  }

  private static String deserializeError(String json) {
    return T.throwsA(TrajectoryUtil.TrajectorySerializationException.class, () -> TrajectoryUtilJNI.deserializeTrajectory(json)).getMessage();
  }

  public static void testDeserializeErrors() {
    T.eq(
        "[json.exception.parse_error.101] parse error at line 1, column 1: syntax error while parsing value - unexpected end of input; expected '[', '{', or a literal",
        deserializeError(""));
    T.check(deserializeError("[{").startsWith("[json.exception.parse_error.101]"), "truncated");
    T.check(deserializeError("[1,]").startsWith("[json.exception.parse_error.101]"), "trailing comma");
    T.check(deserializeError("[] x").startsWith("[json.exception.parse_error.101]"), "trailing text");
    T.eq("[json.exception.type_error.302] type must be array, but is object", deserializeError("{}"));
    T.eq("[json.exception.type_error.304] cannot use at() with number", deserializeError("[1]"));
    T.eq("[json.exception.out_of_range.403] key 'pose' not found", deserializeError("[{}]"));
    T.eq(
        "[json.exception.type_error.302] type must be number, but is string",
        deserializeError("[{\"pose\":{\"translation\":{\"x\":\"a\",\"y\":1},\"rotation\":{\"radians\":0}}}]"));
  }

  public static void testIOExceptionMessages() throws IOException {
    Path missing = Path.of(System.getProperty("java.io.tmpdir"), "frc-missing-dir", "nothing.json");
    try {
      TrajectoryUtilJNI.fromPathweaverJson(missing.toString());
      throw new AssertionError("expected IOException");
    } catch (IOException e) {
      T.eq("Cannot open file: " + missing, e.getMessage());
    }
    try {
      TrajectoryUtilJNI.toPathweaverJson(new double[7], missing.toString());
      throw new AssertionError("expected IOException");
    } catch (IOException e) {
      T.eq("Cannot open file: " + missing, e.getMessage());
    }

    Path bad = Files.createTempFile("frc-bad", ".json");
    try {
      Files.writeString(bad, "[{\"time\":");
      try {
        TrajectoryUtilJNI.fromPathweaverJson(bad.toString());
        throw new AssertionError("expected IOException");
      } catch (IOException e) {
        T.check(e.getMessage().startsWith("[json.exception.parse_error.101]"), e.getMessage());
      }
    } finally {
      Files.deleteIfExists(bad);
    }
  }

  public static void testStringEscapesInKeysAreHandled() {
    String json = "[{\"\\u0074ime\":1,\"velocity\":2,\"acceleration\":3,\"curvature\":4,\"pose\":{\"translation\":{\"x\":5,\"y\":6},\"rotation\":{\"radians\":7}}}]";
    T.eq(new double[] {1, 2, 3, 5, 6, 7, 4}, TrajectoryUtilJNI.deserializeTrajectory(json));
  }
}
