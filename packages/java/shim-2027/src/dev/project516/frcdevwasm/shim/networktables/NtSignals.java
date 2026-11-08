package dev.project516.frcdevwasm.shim.networktables;

/**
 * Signal objects for ntcore handles. The Java listener thread in
 * NetworkTableInstance waits on a listener poller handle through
 * WPIUtilJNI.waitForObjects, so both sides share the sim.Sync registry. The default provider uses
 * it; setProvider replaces it for tests.
 */
public final class NtSignals {
  /** Auto-reset signal objects keyed by handle, as wpi::CreateSignalObject and friends. */
  public interface Provider {
    void create(int handle);

    void set(int handle);

    void destroy(int handle);
  }

  /** Backed by the wpiutil Synchronization port, which WPIUtilJNI.waitForObjects also uses. */
  private static volatile Provider provider =
      new Provider() {
        @Override
        public void create(int handle) {
          dev.project516.frcdevwasm.sim.Sync.createSignalObject(handle, false, false);
        }

        @Override
        public void set(int handle) {
          dev.project516.frcdevwasm.sim.Sync.setSignalObject(handle);
        }

        @Override
        public void destroy(int handle) {
          dev.project516.frcdevwasm.sim.Sync.destroySignalObject(handle);
        }
      };

  private NtSignals() {}

  public static void setProvider(Provider p) {
    provider = p;
  }

  static void create(int handle) {
    Provider p = provider;
    if (p != null) {
      p.create(handle);
    }
  }

  static void set(int handle) {
    Provider p = provider;
    if (p != null) {
      p.set(handle);
    }
  }

  static void destroy(int handle) {
    Provider p = provider;
    if (p != null) {
      p.destroy(handle);
    }
  }
}
