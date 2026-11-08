// DeviceState merges HAL messages of either season. fixtures/hal-2027.jsonl is what the 2027 shim sent to
// the bridge for a robot using PWM, an encoder, AI, LEDs, the driver station and a joystick. Usage: node test/simhost.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { DeviceState } from '../src/sim-host.ts';

const messages = fs
  .readFileSync(path.join(import.meta.dirname, 'fixtures/hal-2027.jsonl'), 'utf8')
  .split('\n')
  .filter(Boolean)
  .map((line) => JSON.parse(line));

const find = (devices, type, device) => devices.find((m) => m.type === type && m.device === device)?.data;

test('2027: one merged message per device, with the 2027 keys', () => {
  const state = new DeviceState();
  for (const message of messages) state.apply(message);
  const devices = state.snapshot();

  assert.deepEqual(find(devices, 'Encoder', '0'), { '<init': true, '>count': 12, '>rate': 3.5, '<reverse_direction': false, '<channel_a': 6, '<channel_b': 7 });
  assert.deepEqual(find(devices, 'AI', '1'), { '<init': true, '>voltage': 2.5 });
  assert.deepEqual(find(devices, 'RoboRIO', ''), { '>vin_voltage': 11.8 });
  const led = find(devices, 'AddressableLED', '0');
  assert.equal(led['<length'], 3);
  assert.deepEqual(led['<data'][2], { r: 10, g: 20, b: 30 });
  assert.deepEqual(find(devices, 'DriverStation', ''), { '>ds': true, '>robotMode': 1, '>enabled': true, '>new_data': true });

  const stick = find(devices, 'Joystick', '0');
  assert.deepEqual(stick['>povs'], [3]);
  assert.equal(stick['>buttons'].length, 64);
  assert.equal(stick['>buttons'][40], true);
  assert.equal(stick['<leds'], 0xff0080);
  assert.equal(stick['<rumble_left'], 65535);
  assert.equal(find(devices, 'Joystick', '1')['<leds'], 0);

  for (const device of devices) {
    assert.ok(!('<speed' in device.data) && !('>period' in device.data) && !('<running' in device.data), `${device.type} has a 2026 key`);
  }
});

test('2027: a freed PWM leaves the state, even when its last message also carries <raw', () => {
  const state = new DeviceState();
  const pwm = messages.filter((m) => m.type === 'PWM');
  assert.equal(pwm.at(-1).data['<init'], false);
  for (const message of pwm.slice(0, -1)) state.apply(message);
  assert.deepEqual(find(state.snapshot(), 'PWM', '3'), { '<init': true, '<raw': 1750, '<output_period': 1 });
  state.apply(pwm.at(-1));
  assert.equal(find(state.snapshot(), 'PWM', '3'), undefined);
});

test('2026 keys merge the same way', () => {
  const state = new DeviceState();
  state.apply({ type: 'PWM', device: '0', data: { '<init': true, '<speed': 0 } });
  state.apply({ type: 'PWM', device: '0', data: { '<speed': 0.5 } });
  state.apply({ type: 'Encoder', device: '0', data: { '<init': true, '>count': 0, '>period': 0.01 } });
  state.apply({ type: 'console', device: '', data: { text: 'x' } });
  assert.deepEqual(find(state.snapshot(), 'PWM', '0'), { '<init': true, '<speed': 0.5 });
  assert.equal(state.snapshot().length, 2);
});
