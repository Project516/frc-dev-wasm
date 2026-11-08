// A log of the four SysId tests on a simulated mechanism with known gains.
import { WpiLog } from './wpilog.mjs';

export const GAINS = { ks: 0.3, kv: 2.0, ka: 0.5 };
const DT = 0.02;

// Returns [time, voltage, position, velocity] samples for a voltage profile.
function run(voltage, seconds, direction) {
  let position = 0;
  let velocity = 0;
  const samples = [];
  for (let t = 0; t <= seconds; t += DT) {
    const volts = direction * voltage(t);
    const friction = velocity === 0 && Math.abs(volts) <= GAINS.ks ? 0 : GAINS.ks * Math.sign(volts || velocity);
    const acceleration = (volts - friction - GAINS.kv * velocity) / GAINS.ka;
    samples.push([t, volts, position, velocity]);
    velocity += acceleration * DT;
    position += velocity * DT;
  }
  return samples;
}

export function sysidLog() {
  const log = new WpiLog();
  const state = log.start('sysid-test-state-drive', 'string');
  const voltage = log.start('drive-voltage', 'double');
  const position = log.start('drive-position', 'double');
  const velocity = log.start('drive-velocity', 'double');
  const tests = [
    ['quasistatic-forward', (t) => 0.25 * t, 6, 1],
    ['quasistatic-reverse', (t) => 0.25 * t, 6, -1],
    ['dynamic-forward', () => 7, 2.5, 1],
    ['dynamic-reverse', () => 7, 2.5, -1],
  ];
  let at = 1_000_000;
  for (const [name, profile, seconds, direction] of tests) {
    log.string(state, at, name);
    for (const [t, volts, pos, vel] of run(profile, seconds, direction)) {
      const ts = at + Math.round(t * 1e6);
      log.double(voltage, ts, volts);
      log.double(position, ts, pos);
      log.double(velocity, ts, vel);
    }
    at += Math.round(seconds * 1e6) + 500_000;
    log.string(state, at, 'none');
    at += 2_000_000;
  }
  return log.toBytes();
}
