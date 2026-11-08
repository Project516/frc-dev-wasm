// A SimHost that behaves like a small robot program, for developing the GUI without the Java side.
import type { BridgeMessage, NtEvent, NtTopic, NtValue, SimHost, SimStatus } from '@frc-dev-wasm/runtime';

type Data = Record<string, unknown>;

const STEP_MS = 20;

export class MockHost implements SimHost {
  /** Everything the GUI sent while a robot was running. */
  readonly sent: BridgeMessage[] = [];
  /** Values the GUI wrote to NetworkTables. */
  readonly published: { name: string; type: string; value: NtValue }[] = [];

  private state: SimStatus = 'stopped';
  private readonly devices = new Map<string, BridgeMessage>();
  private readonly messageListeners = new Set<(messages: BridgeMessage[]) => void>();
  private readonly statusListeners = new Set<(status: SimStatus) => void>();
  private readonly ntListeners = new Set<(event: NtEvent) => void>();
  private readonly topics = new Map<string, NtTopic>();
  private timer?: ReturnType<typeof setInterval>;
  private tick = 0;
  private simTime = 0;
  private paused = false;
  private nextTelemetry = 0;

  readonly nt = {
    listen: (listener: (event: NtEvent) => void) => {
      this.ntListeners.add(listener);
      return () => this.ntListeners.delete(listener);
    },
    getTopics: () => [...this.topics.values()],
    publish: (name: string, type: string, value: NtValue) => {
      const topic = this.topics.get(name);
      if (topic && topic.type !== type) return;
      this.published.push({ name, type, value });
      this.setTopic(name, type, value);
    },
    now: () => this.simTime * 1000,
  };

  listen(listener: (messages: BridgeMessage[]) => void): () => void {
    this.messageListeners.add(listener);
    return () => this.messageListeners.delete(listener);
  }

  send(message: BridgeMessage): void {
    if (this.state !== 'running') return;
    this.sent.push(message);
    if (message.type === 'DriverStation') {
      const { ['>new_data']: _newData, ...state } = message.data;
      this.emit([{ type: 'DriverStation', device: '', data: state }]);
    } else if (message.type === 'sim.timing') {
      const op = message.data.op;
      if (op === 'pause') this.paused = true;
      if (op === 'resume') this.paused = false;
      if (op === 'step') this.advance(Number(message.data.us) / 1000);
      this.emit([{ type: 'sim.timing', device: '', data: { paused: this.paused } }]);
    } else if (message.type === 'AI' || message.type === 'DIO') {
      this.merge(message.type, message.device, message.data);
    }
  }

  snapshot(): BridgeMessage[] {
    return [...this.devices.values()].map((m) => ({ ...m, data: { ...m.data } }));
  }

  status(): SimStatus {
    return this.state;
  }

  year(): string {
    return '2026';
  }

  onStatus(listener: (status: SimStatus) => void): () => void {
    this.statusListeners.add(listener);
    return () => this.statusListeners.delete(listener);
  }

  /** Brings the mock robot up, like Run Simulate Robot Code. */
  start(): void {
    if (this.state !== 'stopped') return;
    this.setStatus('starting');
    this.devices.clear();
    this.topics.clear();
    this.sent.length = 0;
    this.published.length = 0;
    this.tick = 0;
    this.simTime = 0;
    this.paused = false;
    this.populate();
    this.setStatus('running');
    this.timer = setInterval(() => this.advance(STEP_MS), STEP_MS);
  }

  stop(): void {
    clearInterval(this.timer);
    this.devices.clear();
    this.topics.clear();
    this.setStatus('stopped');
  }

  private setStatus(status: SimStatus): void {
    this.state = status;
    for (const listener of this.statusListeners) listener(status);
  }

  private emit(messages: BridgeMessage[]): void {
    for (const message of messages) this.keep(message);
    for (const listener of this.messageListeners) listener(messages);
  }

  private keep(message: BridgeMessage): void {
    if (message.data['<init'] === false) {
      this.devices.delete(`${message.type}/${message.device}`);
      return;
    }
    const key = `${message.type}/${message.device}`;
    const current = this.devices.get(key);
    if (current) Object.assign(current.data, message.data);
    else this.devices.set(key, { type: message.type, device: message.device, data: { ...message.data } });
  }

  private merge(type: string, device: string, data: Data): void {
    this.keep({ type, device, data });
  }

  private populate(): void {
    const add = (type: string, device: string, data: Data) => this.keep({ type, device, data });
    add('PWM', '0', { '<init': true, '<speed': 0 });
    add('PWM', '1', { '<init': true, '<speed': 0 });
    add('PWM', '2', { '<init': true, '<position': 0.25 });
    add('DIO', '0', { '<init': true, '<input': true, '<>value': false });
    add('DIO', '1', { '<init': true, '<input': false, '<>value': false });
    add('AI', '0', { '<init': true, '>voltage': 2.5 });
    add('Encoder', '0', { '<init': true, '<channel_a': 2, '<channel_b': 3, '>count': 0, '>period': Number.MAX_VALUE });
    add('Relay', '0', { '<init_fwd': true, '<init_rev': true, '<fwd': false, '<rev': false });
    for (let i = 0; i < 4; i++) add('Solenoid', `0,${i}`, { '<init': true, '<output': false });
    add('CTREPCM', '0', { '<init': true, '>on': false, '<closed_loop': true, '>pressure_switch': true, '>current': 0 });
    add('RoboRIO', '', { '>fpga_button': false, '>vin_voltage': 12.4, '>vin_current': 1.2, '>6v_voltage': 6, '>6v_current': 0, '>6v_active': true, '>6v_faults': 0 });
    add('AddressableLED', '0', { '<init': true, '<length': 30, '<running': true, '<output_port': 5, '<data': [] });
    add('Gyro', 'ADXRS450[0]', { '<init': true, '>connected': true, '>angle_x': 0, '>rate_x': 0 });
    add('SimDevice', 'Elevator[1]', { '<>position': 0.1, '>limitSwitch': false, '<voltage': 0, '<>name': 'lift' });
    add('DriverStation', '', { '>ds': false, '>enabled': false, '>autonomous': false, '>test': false });

    this.setTopic('/SmartDashboard/Field/.type', 'string', 'Field2d');
    this.setTopic('/SmartDashboard/Field/Robot', 'double[]', [2, 4, 0]);
    this.setTopic('/SmartDashboard/Field/Target', 'double[]', [8.5, 4, 90]);
    const path: number[] = [];
    for (let i = 0; i < 24; i++) path.push(2 + i * 0.3, 2 + Math.sin(i / 4) * 1.2, 0);
    this.setTopic('/SmartDashboard/Field/Trajectory', 'double[]', path);
    this.setTopic('/SmartDashboard/Counter', 'double', 0);
    this.setTopic('/SmartDashboard/Enabled flag', 'boolean', false);
    this.setTopic('/SmartDashboard/Auto name', 'string', 'Two piece');
    this.setTopic('/SmartDashboard/Gains', 'double[]', [0.1, 0, 0.01]);
    this.setTopic('/SmartDashboard/Mech/.type', 'string', 'Mechanism2d');
    this.setTopic('/SmartDashboard/Mech/dims', 'double[]', [2, 2]);
    this.setTopic('/SmartDashboard/Mech/backgroundColor', 'string', '#08081f');
    this.setTopic('/SmartDashboard/Mech/Base/x', 'double', 1);
    this.setTopic('/SmartDashboard/Mech/Base/y', 'double', 0.2);
    this.setTopic('/SmartDashboard/Mech/Base/Arm/.type', 'string', 'line');
    this.setTopic('/SmartDashboard/Mech/Base/Arm/angle', 'double', 60);
    this.setTopic('/SmartDashboard/Mech/Base/Arm/length', 'double', 0.8);
    this.setTopic('/SmartDashboard/Mech/Base/Arm/color', 'string', '#ffcc00');
    this.setTopic('/SmartDashboard/Mech/Base/Arm/weight', 'double', 6);
    this.setTopic('/SmartDashboard/Mech/Base/Arm/Wrist/.type', 'string', 'line');
    this.setTopic('/SmartDashboard/Mech/Base/Arm/Wrist/angle', 'double', -30);
    this.setTopic('/SmartDashboard/Mech/Base/Arm/Wrist/length', 'double', 0.4);
    this.setTopic('/SmartDashboard/Mech/Base/Arm/Wrist/color', 'string', '#00cc66');
    this.setTopic('/SmartDashboard/Mech/Base/Arm/Wrist/weight', 'double', 4);
    for (let i = 0; i < 300; i++) this.setTopic(`/Telemetry/Module${i % 6}/Signal${i}`, 'double', 0);
    this.emit([{ type: 'sim.timing', device: '', data: { paused: false } }]);
  }

  private setTopic(name: string, type: string, value: NtValue): void {
    let topic = this.topics.get(name);
    const created = !topic;
    if (!topic) {
      topic = { name, type, properties: {}, timestamp: 0 };
      this.topics.set(name, topic);
    }
    topic.value = value;
    topic.timestamp = this.simTime * 1000;
    const kind = created ? 'announce' : 'value';
    for (const listener of this.ntListeners) listener({ kind, topic });
    if (created) for (const listener of this.ntListeners) listener({ kind: 'value', topic });
  }

  private advance(ms: number): void {
    if (this.paused && ms === STEP_MS) return;
    this.simTime += ms;
    this.tick++;
    const t = this.simTime / 1000;
    const wave = Math.sin(t * 2);
    const ds = this.devices.get('DriverStation/')?.data ?? {};
    const enabled = ds['>enabled'] === true;
    const messages: BridgeMessage[] = [
      { type: 'PWM', device: '0', data: { '<speed': enabled ? wave : 0 } },
      { type: 'PWM', device: '1', data: { '<speed': enabled ? -wave * 0.5 : 0 } },
      { type: 'DIO', device: '1', data: { '<>value': wave > 0 } },
      { type: 'Relay', device: '0', data: { '<fwd': wave > 0.3, '<rev': wave < -0.3 } },
      { type: 'Solenoid', device: '0,1', data: { '<output': wave > 0 } },
      { type: 'Gyro', device: 'ADXRS450[0]', data: { '>angle_x': (t * 20) % 360, '>rate_x': 20 } },
      { type: 'Encoder', device: '0', data: { '>count': Math.round(t * 100), '>period': 0.01 } },
    ];
    if (this.tick % 5 === 0) {
      const colors = Array.from({ length: 30 }, (_, i) => {
        const hue = (i * 12 + t * 120) % 360;
        return hslToRgb(hue / 360, 1, 0.5);
      });
      messages.push({ type: 'AddressableLED', device: '0', data: { '<data': colors } });
    }
    this.emit(messages);

    const angle = t * 0.6;
    this.setTopic('/SmartDashboard/Field/Robot', 'double[]', [8.27 + Math.cos(angle) * 4, 4.1 + Math.sin(angle) * 2.5, (angle * 180) / Math.PI + 90]);
    this.setTopic('/SmartDashboard/Counter', 'double', this.tick);
    if (this.tick >= this.nextTelemetry) {
      this.nextTelemetry = this.tick + 2;
      for (let i = 0; i < 300; i++) this.setTopic(`/Telemetry/Module${i % 6}/Signal${i}`, 'double', Math.sin(t + i));
    }
  }
}

function hslToRgb(h: number, s: number, l: number): { r: number; g: number; b: number } {
  const a = s * Math.min(l, 1 - l);
  const f = (n: number) => {
    const k = (n + h * 12) % 12;
    return Math.round(255 * (l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1))));
  };
  return { r: f(0), g: f(8), b: f(4) };
}
