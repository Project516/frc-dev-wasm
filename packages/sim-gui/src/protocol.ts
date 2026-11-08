// Where the HAL message keys differ between WPILib years (docs/bridge-protocol.md).
export interface Protocol {
  /** DriverStation carries `>robotMode` where 2026 has `>autonomous` and `>test`. */
  robotMode: boolean;
  /** Joystick POVs are a direction bitmask where 2026 has an angle. */
  povBitmask: boolean;
  maxButtons: number;
  maxPovs: number;
  /** Joystick `<leds` output. */
  joystickLeds: boolean;
  /** PWM shows `<speed` and `<position`, or the pulse width `<raw` and `<output_period`. */
  pwmSpeed: boolean;
  /** Encoder input is `>period`, or `>rate`. */
  encoderPeriod: boolean;
  /** RoboRIO has the user button `>fpga_button`. */
  userButton: boolean;
  /** AddressableLED reports `<running`. */
  ledRunning: boolean;
  /** DriverStation has the op mode keys `>opmode`, `<opmode_options` and `<program_state`. */
  opModes: boolean;
}

const PROTOCOLS: Record<string, Protocol> = {
  '2026': {
    robotMode: false,
    povBitmask: false,
    maxButtons: 32,
    maxPovs: 12,
    joystickLeds: false,
    pwmSpeed: true,
    encoderPeriod: true,
    userButton: true,
    ledRunning: true,
    opModes: false,
  },
  '2027': {
    robotMode: true,
    povBitmask: true,
    maxButtons: 64,
    maxPovs: 8,
    joystickLeds: true,
    pwmSpeed: false,
    encoderPeriod: false,
    userButton: false,
    ledRunning: false,
    opModes: true,
  },
};

export const protocolFor = (year: string): Protocol => PROTOCOLS[year] ?? PROTOCOLS['2026'];

// POVDirection bits: up 1, right 2, down 4, left 8. Index is the angle divided by 45.
const POV_BITS = [0x01, 0x03, 0x02, 0x06, 0x04, 0x0c, 0x08, 0x09];

/** The POV value the robot reads for an angle in degrees, -1 for centered. */
export function povBits(angle: number): number {
  return angle < 0 ? 0 : POV_BITS[Math.round(angle / 45) % 8];
}

/** The robot's view of a joystick. POV angles become bitmasks and counts are capped for the year. */
export function joystickInputs(
  protocol: Protocol,
  data: { axes: number[]; buttons: boolean[]; povs: number[] },
): Record<string, unknown> {
  return {
    '>axes': data.axes,
    '>buttons': data.buttons.slice(0, protocol.maxButtons),
    '>povs': protocol.povBitmask ? data.povs.slice(0, protocol.maxPovs).map(povBits) : data.povs,
  };
}
