package dev.project516.frcdevwasm.sim;

/** Message type registrations for the digital group. */
final class WsDigital {
  private WsDigital() {}

  static void register() {
    Ws.type("DIO", DioData::ws);
    Ws.type("dPWM", DigitalPwmData::ws);
    Ws.type("AddressableLED", AddressableLedData::ws);
  }
}
