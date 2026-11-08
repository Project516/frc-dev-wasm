package dev.project516.frcdevwasm.bridge;

/** Transport over the Bridge natives implemented by the page. */
final class NativeTransport implements Transport {
  @Override
  public void send(String json) {
    Bridge.send(json);
  }

  @Override
  public String receive() {
    return Bridge.receive();
  }
}
