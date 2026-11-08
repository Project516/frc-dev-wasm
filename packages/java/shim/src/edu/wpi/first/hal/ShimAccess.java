package edu.wpi.first.hal;

/** Reaches package-private constructors of WPILib hal classes for the shim implementations. */
public final class ShimAccess {
  private ShimAccess() {}

  public static PWMConfigDataResult pwmConfigDataResult(
      int max, int deadbandMax, int center, int deadbandMin, int min) {
    return new PWMConfigDataResult(max, deadbandMax, center, deadbandMin, min);
  }
}
