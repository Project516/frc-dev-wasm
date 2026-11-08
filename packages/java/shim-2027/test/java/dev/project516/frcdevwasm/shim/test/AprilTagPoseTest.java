package dev.project516.frcdevwasm.shim.test;

import org.wpilib.vision.apriltag.AprilTagPoseEstimate;
import org.wpilib.vision.apriltag.AprilTagPoseEstimator;
import org.wpilib.vision.apriltag.jni.AprilTagJNI;
import org.wpilib.math.geometry.Rotation3d;
import org.wpilib.math.geometry.Transform3d;
import org.wpilib.math.geometry.Translation3d;

/**
 * Pose estimation against the C library. Golden numbers come from compiling apriltag_pose.c from
 * allwpilib v2026.2.1 (matd.c, homography.c) and feeding it the homography and corners below, which
 * are a pinhole projection of a 0.1651 m tag at a known pose, with fixed pixel noise where noted.
 * Camera: fx 600, fy 610, cx 320, cy 240.
 */
public final class AprilTagPoseTest {
  private static final double TAG = 0.1651;
  private static final double FX = 600;
  private static final double FY = 610;
  private static final double CX = 320;
  private static final double CY = 240;

  private static final double[] EXACT_H = {
    31.959206890325504, -17.158739508576446, 360.0,
    22.602366019327604, 28.10490619941274, 219.66666666666666,
    0.021430989438386158, 0.01007036097387561, 1.0
  };
  private static final double[] EXACT_CORNERS = {
    314.45445381995717, 227.75666570615516, 363.35431575727154, 262.1169024910595,
    404.5223186313291, 211.75841777797336, 356.4274795480535, 174.45496131548293
  };
  private static final double[] EXACT_TRUTH_R = {
    0.7601844418546907, -0.6172397038224309, -0.20281706631166482,
    0.5200701578014789, 0.7652000148647886, -0.37946800684019766,
    0.3894183423086505, 0.18298657129998708, 0.90270109637546
  };
  private static final double[] EXACT_TRUTH_T = {0.1, -0.05, 1.5};

  private static final double[] NOISY_H = {
    -0.8683704336997096, -16.770645592145428, 247.31703972273803,
    5.178959506615172, 8.09311359302103, 288.54616403410955,
    -0.04173585008122768, 0.0011616724900267346, 1.0
  };
  private static final double[] NOISY_CORNERS = {
    221.89597688729887, 279.4716756080911, 239.3911215775366, 314.5821491191269,
    275.01685669890026, 298.4340931966384, 254.6248614028875, 264.5405746774329
  };

  private static final double[] FAR_H = {
    5.928907411800765, 8.532620478877561, 349.70486816223024,
    -0.5156479881191558, 21.172636085560583, 255.6007879098736,
    -0.016626850157004235, 0.03421476087395194, 1.0
  };
  private static final double[] FAR_CORNERS = {
    335.26325711794505, 263.8733269341243, 357.8721722394804, 271.48295798116936,
    365.6936072304723, 246.44201279226408, 341.2451291353783, 239.14994774126825
  };

  private static AprilTagPoseEstimator estimator() {
    return new AprilTagPoseEstimator(new AprilTagPoseEstimator.Config(TAG, FX, FY, CX, CY));
  }

  private static void assertPose(String name, Transform3d pose, double[] r, double[] t, double tol) {
    Translation3d tr = pose.getTranslation();
    T.near(t, new double[] {tr.getX(), tr.getY(), tr.getZ()}, tol, name + " translation");
    Rotation3d rot = pose.getRotation();
    for (int col = 0; col < 3; col++) {
      double[] basis = {0, 0, 0};
      basis[col] = 1;
      Translation3d v = new Translation3d(basis[0], basis[1], basis[2]).rotateBy(rot);
      T.near(new double[] {r[col], r[3 + col], r[6 + col]}, new double[] {v.getX(), v.getY(), v.getZ()}, tol, name + " rotation column " + col);
    }
  }

  public static void testHomographyRecoversExactPose() {
    Transform3d pose = estimator().estimateHomography(EXACT_H);
    assertPose("homography", pose, EXACT_TRUTH_R, EXACT_TRUTH_T, 1e-6);
    assertPose(
        "homography vs C",
        pose,
        new double[] {
          0.76018444185469103, -0.61723970382242932, -0.2028170663116694,
          0.52007015780147825, 0.76520001486479083, -0.37946800684019366,
          0.38941834230865147, 0.18298657129998175, 0.9027010963754607
        },
        new double[] {0.099999999516231231, -0.049999999758115629, 1.4999999927434684},
        1e-12);
  }

  public static void testOrthogonalIterationRecoversExactPose() {
    AprilTagPoseEstimate est = estimator().estimateOrthogonalIteration(EXACT_H, EXACT_CORNERS, 50);
    assertPose("pose1", est.pose1, EXACT_TRUTH_R, EXACT_TRUTH_T, 1e-9);
    T.check(est.error1 < 1e-12, "error1 " + est.error1);
    assertPose(
        "pose2",
        est.pose2,
        new double[] {
          0.71069378323752452, -0.6308627691818337, 0.31133023131036008,
          0.55455681287044412, 0.77468531093126902, 0.30385754939823062,
          -0.43287537209514382, -0.043299370521420133, 0.90041328108038232
        },
        new double[] {0.098198865892397874, -0.052928860044284515, 1.5043706691702008},
        1e-8);
    T.near(5.826849067939284e-05, est.error2, 1e-10, "error2");
    T.check(est.getAmbiguity() < 1e-6, "ambiguity");
  }

  public static void testEstimateMatchesCForNoisyCorners() {
    AprilTagPoseEstimate est = estimator().estimateOrthogonalIteration(NOISY_H, NOISY_CORNERS, 50);
    assertPose(
        "pose1",
        est.pose1,
        new double[] {
          0.50463537325440777, -0.8042143806684946, -0.31397829540650413,
          0.82674668933325157, 0.34540886314626379, 0.44405250695893367,
          -0.24866252579930881, -0.48366511884360214, 0.83918710731106461
        },
        new double[] {-0.29846388830601106, 0.19954529885011782, 2.4846703871457523},
        1e-8);
    T.near(3.7941320845438313e-05, est.error1, 1e-10, "error1");
    assertPose(
        "pose2",
        est.pose2,
        new double[] {
          0.44924194467794776, -0.88599308694671386, 0.11488222240454629,
          0.86851120573735696, 0.40294978476014121, -0.28865161782051074,
          0.20945157113427884, 0.22945091162248174, 0.95051686913226008
        },
        new double[] {-0.2994338769720668, 0.20129702466664623, 2.4838358191621528},
        1e-8);
    T.near(5.5536033990799411e-05, est.error2, 1e-10, "error2");

    assertPose(
        "homography",
        estimator().estimateHomography(NOISY_H),
        new double[] {
          0.33033714247296037, -0.87780471149152717, 0.34689517261858238,
          0.50722441116577222, 0.47504390064368973, 0.71906654016075844,
          -0.79599043274422288, -0.061580686456913641, 0.60216862259267578
        },
        new double[] {-0.24568724035678693, 0.16140847317301135, 2.0281554803456241},
        1e-9);
  }

  public static void testEstimatePicksTheLowerErrorPose() {
    Transform3d pose = estimator().estimate(FAR_H, FAR_CORNERS);
    assertPose(
        "best",
        pose,
        new double[] {
          0.90579101389101413, -0.25653311021127156, 0.33724383244118783,
          0.28532900531810046, 0.95768115756647365, -0.03787029398251647,
          -0.31325707953448051, 0.13052801924288254, 0.94065532386419726
        },
        new double[] {0.19301053059382631, 0.096713963334062583, 3.8692025378459434},
        1e-8);
  }

  public static void testIterationCountIsHonored() {
    AprilTagPoseEstimate est = estimator().estimateOrthogonalIteration(FAR_H, FAR_CORNERS, 3);
    assertPose(
        "pose1",
        est.pose1,
        new double[] {
          0.87050400442979603, -0.12605885342899364, 0.47574356931424416,
          0.35072795917549471, 0.83703077917006008, -0.41996353814891468,
          -0.34527188840721162, 0.53243651282958604, 0.77285100949768326
        },
        new double[] {0.17065646106001547, 0.086458046779516268, 3.4282359465880701},
        1e-8);
    T.near(0.00067737409633255381, est.error1, 1e-10, "error1");
    T.near(5.356728471508202e-05, est.error2, 1e-10, "error2");
  }

  public static void testZeroIterationsKeepTheHomographyPose() {
    AprilTagPoseEstimate est = estimator().estimateOrthogonalIteration(EXACT_H, EXACT_CORNERS, 0);
    T.eq(Double.POSITIVE_INFINITY, est.error1);
    assertPose("pose1", est.pose1, EXACT_TRUTH_R, EXACT_TRUTH_T, 1e-6);
  }

  public static void testRotationIsAProperQuaternion() {
    Transform3d pose = estimator().estimate(EXACT_H, EXACT_CORNERS);
    var q = pose.getRotation().getQuaternion();
    T.near(1.0, Math.sqrt(q.getW() * q.getW() + q.getX() * q.getX() + q.getY() * q.getY() + q.getZ() * q.getZ()), 1e-12, "norm");
  }

  public static void testBadArraysAreRejected() {
    double[] h = new double[9];
    double[] c = new double[8];
    T.eq("homography cannot be null", T.throwsA(NullPointerException.class, () -> AprilTagJNI.estimatePoseHomography(null, TAG, FX, FY, CX, CY)).getMessage());
    T.eq("homography array must be size 9", T.throwsA(IllegalArgumentException.class, () -> AprilTagJNI.estimatePoseHomography(new double[8], TAG, FX, FY, CX, CY)).getMessage());
    T.eq("corners cannot be null", T.throwsA(NullPointerException.class, () -> AprilTagJNI.estimatePose(h, null, TAG, FX, FY, CX, CY)).getMessage());
    T.eq("corners array must be size 8", T.throwsA(IllegalArgumentException.class, () -> AprilTagJNI.estimatePoseOrthogonalIteration(h, new double[4], TAG, FX, FY, CX, CY, 5)).getMessage());
    T.eq("homography array must be size 9", T.throwsA(IllegalArgumentException.class, () -> AprilTagJNI.estimatePose(new double[3], c, TAG, FX, FY, CX, CY)).getMessage());
  }
}
