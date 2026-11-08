package dev.project516.frcdevwasm.shim.vendor.reduxrobotics.canand;

import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReduxJNIImpl {
  private static final Pattern JAR = Pattern.compile("ReduxLib-java-(\\d+)\\.(\\d+)\\.(\\d+)");

  private ReduxJNIImpl() {}

  /**
   * ReduxLib refuses to start unless the native core reports the vendordep's own version, packed
   * as year, minor and patch. The version comes from the jar name.
   */
  public static int getDriverVersion() {
    Matcher m = JAR.matcher(System.getProperty("java.class.path", ""));
    if (!m.find()) {
      URL jar = ReduxJNIImpl.class.getClassLoader().getResource("com/reduxrobotics/canand/ReduxJNI.class");
      m = JAR.matcher(String.valueOf(jar));
      if (!m.find()) {
        return 0;
      }
    }
    return Integer.parseInt(m.group(1)) << 16
        | Integer.parseInt(m.group(2)) << 8
        | Integer.parseInt(m.group(3));
  }
}
