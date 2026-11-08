package dev.project516.frcdevwasm.sim;

/** Message type registrations for the analog group. */
final class WsAnalog {
  private WsAnalog() {}

  static void register() {
    Ws.type("AI", AnalogInData::ws);
    Ws.type("Encoder", EncoderData::ws);
  }
}
