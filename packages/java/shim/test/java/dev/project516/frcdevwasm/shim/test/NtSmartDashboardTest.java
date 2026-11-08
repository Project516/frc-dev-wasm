package dev.project516.frcdevwasm.shim.test;

import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.util.WPIUtilJNI;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;

/** SmartDashboard on the default instance. */
public final class NtSmartDashboardTest {
  static {
    WPIUtilJNI.now();
  }

  public static void testPutGet() {
    T.check(SmartDashboard.putNumber("gain", 2.5), "putNumber");
    T.eq(2.5, SmartDashboard.getNumber("gain", 0));
    T.eq(7.0, SmartDashboard.getNumber("missing", 7.0), "default for missing key");
    T.check(SmartDashboard.putString("mode", "auto"), "putString");
    T.eq("auto", SmartDashboard.getString("mode", ""));
    T.check(SmartDashboard.putBoolean("on", true), "putBoolean");
    T.eq(true, SmartDashboard.getBoolean("on", false));
    SmartDashboard.putNumberArray("arr", new double[] {1, 2});
    T.eq(new double[] {1, 2}, SmartDashboard.getNumberArray("arr", new double[0]));
    T.check(SmartDashboard.containsKey("gain"), "containsKey");
    T.eq(2.5, NetworkTableInstance.getDefault().getTable("SmartDashboard").getEntry("gain").getDouble(0), "same table");
    SmartDashboard.setDefaultNumber("gain", 99);
    T.eq(2.5, SmartDashboard.getNumber("gain", 0), "default does not overwrite");
    SmartDashboard.setPersistent("gain");
    T.check(SmartDashboard.isPersistent("gain"), "persistent");
  }
}
