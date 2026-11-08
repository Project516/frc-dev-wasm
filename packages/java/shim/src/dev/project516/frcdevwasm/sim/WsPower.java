package dev.project516.frcdevwasm.sim;

/** Message type registrations for the power group. */
final class WsPower {
  private WsPower() {}

  static void register() {
    Ws.type("RoboRIO", RoboRioData::ws);
    Ws.type("CTREPCM", CTREPCMData::ws);
    Ws.type("Solenoid", CTREPCMData::solenoidWs);
    Ws.type("PowerDistribution", PowerDistributionData::ws);
    Ws.type("PDChannel", PowerDistributionData::channelWs);
    Ws.type("REVPH", REVPHData::ws);
    Ws.type("REVPHSolenoid", REVPHData::solenoidWs);
  }
}
