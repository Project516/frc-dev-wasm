import { batch, computed, effect, signal, type ReadonlySignal } from '@preact/signals';
import type { Protocol } from './protocol.ts';

export const STATIONS = ['red1', 'red2', 'red3', 'blue1', 'blue2', 'blue3'] as const;
export type Station = (typeof STATIONS)[number];
export type Mode = 'disconnected' | 'disabled' | 'autonomous' | 'teleop' | 'test';

export const MODES: { mode: Mode; label: string }[] = [
  { mode: 'disconnected', label: 'Disconnected' },
  { mode: 'disabled', label: 'Disabled' },
  { mode: 'autonomous', label: 'Autonomous' },
  { mode: 'teleop', label: 'Teleoperated' },
  { mode: 'test', label: 'Test' },
];

export interface OpModeOption {
  /** Decimal string: ids go past 2^53. Robot mode in bits 57-56, a 56-bit name hash below. */
  id: string;
  name: string;
  group: string;
}

const TICK_MS = 100;
// HAL_RobotMode. A disabled robot is in teleop, as with `>autonomous` and `>test` both false.
const AUTONOMOUS = 1;
const TELEOP = 2;
const TEST = 3;
const HASH_MASK = (1n << 56n) - 1n;
const MODE_MASK = 3n << 56n;

const isId = (value: unknown): value is string => typeof value === 'string' && /^\d+$/.test(value);
const robotModeOf = (id: string) => Number((BigInt(id) >> 56n) & 3n);

/** Driver station and FMS state, sent to the robot as halsim_ws DriverStation messages. */
export class DriverStation {
  readonly attached = signal(false);
  readonly enabled = signal(false);
  readonly autonomous = signal(false);
  readonly test = signal(false);
  readonly fms = signal(false);
  readonly estop = signal(false);
  readonly station = signal<Station>('red1');
  readonly matchTime = signal(-1);
  readonly gameData = signal('');
  /** The selected op mode id, "0" for none. */
  readonly opMode = signal('0');
  readonly opModes = signal<OpModeOption[]>([]);
  /** The control word the robot program observes. */
  readonly programState = signal('0');

  readonly mode = computed<Mode>(() => {
    if (!this.attached.value) return 'disconnected';
    if (!this.enabled.value) return 'disabled';
    if (this.test.value) return 'test';
    return this.autonomous.value ? 'autonomous' : 'teleop';
  });

  private readonly robotMode = computed(() => (this.test.value ? TEST : this.autonomous.value ? AUTONOMOUS : TELEOP));

  /** The op modes of the current robot mode. */
  readonly modeOpModes = computed(() => this.opModes.value.filter((o) => robotModeOf(o.id) === this.robotMode.value));

  /** Whether the robot program runs the selected op mode: none selected, matching, or not yet. */
  readonly opModeStatus = computed(() => {
    const word = BigInt(this.programState.value);
    // As HAL_ControlWord_GetOpModeId: the id of the word, 0 without a hash.
    const running = (word & HASH_MASK) === 0n ? '0' : String(word & (HASH_MASK | MODE_MASK));
    if (running !== this.opMode.value) return 'BAD';
    return running === '0' ? 'NONE' : 'GOOD';
  });

  private remaining = -1;
  private readonly sendData: (data: Record<string, unknown>) => void;
  private readonly protocol: () => Protocol;

  constructor(sendData: (data: Record<string, unknown>) => void, paused: ReadonlySignal<boolean>, protocol: () => Protocol) {
    this.sendData = sendData;
    this.protocol = protocol;
    effect(() => {
      if (!(this.attached.value && this.enabled.value && !paused.value)) return;
      let last = performance.now();
      const timer = setInterval(() => {
        const now = performance.now();
        this.countDown((now - last) / 1000);
        last = now;
      }, TICK_MS);
      return () => clearInterval(timer);
    });
  }

  private send(data: Record<string, unknown>): void {
    this.sendData({ ...data, '>new_data': true });
  }

  private countDown(seconds: number): void {
    if (this.remaining <= 0) return;
    this.remaining -= seconds;
    if (this.remaining < 0) this.remaining = -1;
    const shown = this.remaining < 0 ? -1 : Math.round(this.remaining * 10) / 10;
    if (shown === this.matchTime.value) return;
    this.matchTime.value = shown;
    this.send({ '>match_time': shown });
  }

  /** The mode keys of a message. */
  private modeData(): Record<string, unknown> {
    if (!this.protocol().robotMode) return { '>autonomous': this.autonomous.peek(), '>test': this.test.peek() };
    return { '>robotMode': this.robotMode.peek() };
  }

  full(): Record<string, unknown> {
    return {
      '>enabled': this.enabled.value,
      ...this.modeData(),
      '>ds': this.attached.value,
      '>estop': this.estop.value,
      '>fms': this.fms.value,
      '>station': this.station.value,
      '>match_time': this.matchTime.value,
      '>game_data': this.gameData.value,
      ...(this.protocol().opModes ? { '>opmode': this.opMode.value } : {}),
    };
  }

  resend(): void {
    this.send(this.full());
  }

  setMode(mode: Mode): void {
    const wasAttached = this.attached.peek();
    const wasEnabled = this.enabled.peek();
    const enabled = mode === 'autonomous' || mode === 'teleop' || mode === 'test';
    batch(() => {
      this.attached.value = mode !== 'disconnected';
      this.enabled.value = enabled;
      this.autonomous.value = mode === 'autonomous';
      this.test.value = mode === 'test';
      // Leaving an enabled state resets the match clock, as halsim_gui does.
      if (wasEnabled && !enabled) {
        this.matchTime.value = -1;
        this.remaining = -1;
      }
    });
    const data: Record<string, unknown> = {
      '>enabled': this.enabled.peek(),
      ...this.modeData(),
      '>ds': this.attached.peek(),
    };
    if (wasEnabled && !enabled) data['>match_time'] = -1;
    // A selection belongs to one robot mode.
    if (this.protocol().opModes && this.opMode.peek() !== '0' && robotModeOf(this.opMode.peek()) !== this.robotMode.peek()) {
      this.opMode.value = '0';
      data['>opmode'] = '0';
    }
    if (!wasAttached && this.attached.peek()) Object.assign(data, this.full());
    this.send(data);
  }

  setFms(value: boolean): void {
    this.fms.value = value;
    this.send({ '>fms': value });
  }

  setStation(value: Station): void {
    this.station.value = value;
    this.send({ '>station': value });
  }

  setMatchTime(value: number): void {
    const time = Number.isFinite(value) ? value : -1;
    this.matchTime.value = time;
    this.remaining = time;
    this.send({ '>match_time': time });
  }

  /** The robot program is gone, and its op modes with it. */
  clearOpModes(): void {
    batch(() => {
      this.opModes.value = [];
      this.opMode.value = '0';
      this.programState.value = '0';
    });
  }

  setOpMode(id: string): void {
    this.opMode.value = id;
    this.send({ '>opmode': id });
  }

  setGameData(value: string): void {
    this.gameData.value = value;
    this.send({ '>game_data': value });
  }

  /** Applies a DriverStation message from the robot program. */
  receive(data: Record<string, unknown>): void {
    batch(() => {
      if (typeof data['>enabled'] === 'boolean') this.enabled.value = data['>enabled'];
      if (typeof data['>autonomous'] === 'boolean') this.autonomous.value = data['>autonomous'];
      if (typeof data['>test'] === 'boolean') this.test.value = data['>test'];
      const mode = data['>robotMode'];
      if (typeof mode === 'number') {
        this.autonomous.value = mode === AUTONOMOUS;
        this.test.value = mode === TEST;
      }
      if (typeof data['>ds'] === 'boolean') this.attached.value = data['>ds'];
      if (typeof data['>fms'] === 'boolean') this.fms.value = data['>fms'];
      if (typeof data['>estop'] === 'boolean') this.estop.value = data['>estop'];
      if (typeof data['>game_data'] === 'string') this.gameData.value = data['>game_data'];
      if (isId(data['>opmode'])) this.opMode.value = data['>opmode'];
      if (isId(data['<program_state'])) this.programState.value = data['<program_state'];
      const options = data['<opmode_options'];
      if (Array.isArray(options)) {
        this.opModes.value = options
          .filter((o) => o && isId(o.id) && typeof o.name === 'string')
          .map((o) => ({ id: o.id, name: o.name, group: typeof o.group === 'string' ? o.group : '' }));
      }
      const station = data['>station'];
      if (STATIONS.includes(station as Station)) this.station.value = station as Station;
      const time = data['>match_time'];
      if (typeof time === 'number' && time !== this.matchTime.peek()) {
        this.matchTime.value = time;
        this.remaining = time;
      }
    });
  }
}
