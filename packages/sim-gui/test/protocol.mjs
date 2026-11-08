// The driver station and joystick messages of each WPILib season, under Node. fixtures/ds-2026.json is what the
// 2026 driver station sent for the same steps before the 2027 protocol existed. Usage: node test/protocol.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { signal } from '@preact/signals';
import { DriverStation } from '../src/ds.ts';
import { joystickInputs, povBits, protocolFor } from '../src/protocol.ts';

const here = import.meta.dirname;
const hal2027 = fs
  .readFileSync(path.join(here, '../../runtime/test/fixtures/hal-2027.jsonl'), 'utf8')
  .split('\n')
  .filter(Boolean)
  .map((line) => JSON.parse(line));

function driverStation(year) {
  const sent = [];
  // Paused, so the match clock has no timer
  const ds = new DriverStation((data) => sent.push(data), signal(true), () => protocolFor(year));
  return { ds, sent };
}

test('2026: the driver station sends what it did before', () => {
  const { ds, sent } = driverStation('2026');
  for (const mode of ['teleop', 'autonomous', 'test', 'disabled', 'teleop', 'disconnected']) ds.setMode(mode);
  ds.setFms(true);
  ds.setStation('blue2');
  ds.setMatchTime(15);
  ds.setGameData('abc');
  ds.resend();
  ds.receive({ '>enabled': true, '>autonomous': true, '>ds': true });
  sent.push({ mode: ds.mode.value });
  ds.receive({ '>test': true, '>autonomous': false });
  sent.push({ mode: ds.mode.value });
  assert.deepEqual(sent, JSON.parse(fs.readFileSync(path.join(here, 'fixtures/ds-2026.json'), 'utf8')));
});

test('an unknown year uses the 2026 keys', () => {
  assert.equal(protocolFor('2099'), protocolFor('2026'));
});

test('2027: modes go out as >robotMode', () => {
  const { ds, sent } = driverStation('2027');
  ds.setMode('teleop');
  assert.deepEqual(sent[0], {
    '>enabled': true,
    '>robotMode': 2,
    '>ds': true,
    '>estop': false,
    '>fms': false,
    '>station': 'red1',
    '>match_time': -1,
    '>game_data': '',
    '>opmode': '0',
    '>new_data': true,
  });
  ds.setMode('autonomous');
  assert.deepEqual(sent[1], { '>enabled': true, '>robotMode': 1, '>ds': true, '>new_data': true });
  ds.setMode('test');
  assert.deepEqual(sent[2], { '>enabled': true, '>robotMode': 3, '>ds': true, '>new_data': true });
  ds.setMode('disabled');
  assert.deepEqual(sent[3], { '>enabled': false, '>robotMode': 2, '>ds': true, '>match_time': -1, '>new_data': true });
  ds.resend();
  assert.equal(sent[4]['>robotMode'], 2);
  for (const data of sent) assert.ok(!('>autonomous' in data) && !('>test' in data));
});

test('2027: the robot mode of a message sets the mode', () => {
  const { ds } = driverStation('2027');
  const robot = hal2027.find((m) => m.type === 'DriverStation' && '>robotMode' in m.data);
  ds.receive(robot.data);
  assert.equal(ds.mode.value, 'teleop');
  ds.receive({ '>robotMode': 1 });
  assert.equal(ds.mode.value, 'autonomous');
  ds.receive({ '>robotMode': 3 });
  assert.equal(ds.mode.value, 'test');
  ds.receive({ '>robotMode': 2 });
  assert.equal(ds.mode.value, 'teleop');
  ds.receive({ '>enabled': false });
  assert.equal(ds.mode.value, 'disabled');
});

test('2027: op modes are picked for the robot mode, with ids above 2^53 kept exact', () => {
  const { ds, sent } = driverStation('2027');
  const teleop = String((2n << 56n) | 0x00fedcba98765432n);
  const auto = String((1n << 56n) | 0x00123456789abcden);
  ds.receive({
    '<opmode_options': [
      { id: teleop, name: 'Teleop', group: '', description: '', textColor: -1, backgroundColor: -1 },
      { id: auto, name: 'Auto', group: 'Match', description: '', textColor: -1, backgroundColor: -1 },
      { id: 'junk', name: 'Bad' },
    ],
  });
  assert.deepEqual(ds.modeOpModes.value.map((o) => o.id), [teleop]);
  ds.setOpMode(teleop);
  assert.deepEqual(sent.at(-1), { '>opmode': teleop, '>new_data': true });
  assert.equal(ds.opModeStatus.value, 'BAD');
  ds.receive({ '<program_state': String(BigInt(teleop) | (1n << 58n)) });
  assert.equal(ds.opModeStatus.value, 'GOOD');
  ds.setMode('autonomous');
  assert.equal(sent.at(-1)['>opmode'], '0');
  assert.equal(ds.opMode.value, '0');
  assert.deepEqual(ds.modeOpModes.value.map((o) => o.id), [auto]);
  ds.setMode('teleop');
  ds.setOpMode(teleop);
  ds.setMode('disabled');
  assert.equal(ds.opMode.value, teleop);
  ds.receive({ '>opmode': auto });
  assert.equal(ds.opMode.value, auto);
  ds.clearOpModes();
  assert.deepEqual([ds.opModes.value, ds.opMode.value, ds.programState.value], [[], '0', '0']);
});

test('2026: op mode messages are not sent', () => {
  const { ds, sent } = driverStation('2026');
  ds.setMode('teleop');
  ds.setMode('autonomous');
  ds.resend();
  for (const data of sent) assert.ok(!('>opmode' in data));
});

test('POV angles become the bits of POVDirection', () => {
  const bits = [-1, 0, 45, 90, 135, 180, 225, 270, 315].map(povBits);
  assert.deepEqual(bits, [0x00, 0x01, 0x03, 0x02, 0x06, 0x04, 0x0c, 0x08, 0x09]);
  // The shim reports UP_RIGHT as 3
  const stick = hal2027.find((m) => m.type === 'Joystick' && m.device === '0' && '>povs' in m.data);
  assert.deepEqual(stick.data['>povs'], [povBits(45)]);
});

test('joystick inputs: 2026 keeps angles, 2027 sends bitmasks and up to 64 buttons', () => {
  const data = { axes: [0.5], buttons: Array.from({ length: 70 }, (_, i) => i % 2 === 0), povs: [-1, 90, 315] };
  const old = joystickInputs(protocolFor('2026'), data);
  assert.deepEqual(old['>povs'], [-1, 90, 315]);
  assert.equal(old['>buttons'].length, 32);
  const next = joystickInputs(protocolFor('2027'), data);
  assert.deepEqual(next['>povs'], [0, 2, 9]);
  assert.equal(next['>buttons'].length, 64);
  assert.deepEqual(next['>axes'], [0.5]);
  const many = joystickInputs(protocolFor('2027'), { axes: [], buttons: [], povs: Array(12).fill(0) });
  assert.equal(many['>povs'].length, 8);
});
