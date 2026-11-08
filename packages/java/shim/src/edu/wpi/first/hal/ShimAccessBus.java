package edu.wpi.first.hal;

/** Reaches the package-private DMAJNISample.BaseStore for the DMA shim. */
public final class ShimAccessBus {
  private ShimAccessBus() {}

  public static Object dmaBaseStore(int valueType, int index) {
    return new DMAJNISample.BaseStore(valueType, index);
  }
}
