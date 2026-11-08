// An NT4 server in the page. The robot program's NetworkTables instance feeds it
// through bridge messages, and dashboards connect to it through virtual
// WebSockets (see nt4-socket-shim.js). Spec: allwpilib ntcore/doc/networktables4.adoc.
import type { BridgeMessage } from './robot.ts';
import { Writer, unpackAll, type Unpacked } from './msgpack.ts';

export type NtValue = boolean | number | string | Uint8Array | boolean[] | number[] | string[];

export interface NtTopic {
  name: string;
  type: string;
  properties: Record<string, unknown>;
  value?: NtValue;
  /** Server time in microseconds of the latest value. */
  timestamp: number;
}

export type NtEvent =
  | { kind: 'announce'; topic: NtTopic }
  | { kind: 'unannounce'; topic: NtTopic }
  | { kind: 'properties'; topic: NtTopic }
  | { kind: 'value'; topic: NtTopic };

/** What a virtual socket gives the server, so it can deliver frames. */
export interface Nt4Peer {
  text(data: string): void;
  binary(data: Uint8Array): void;
  close(code: number, reason: string): void;
}

export interface Nt4Connection {
  protocol: string;
  send(data: string | Uint8Array): void;
  close(): void;
}

const PROTOCOLS = ['v4.1.networktables.first.wpi.edu', 'networktables.first.wpi.edu', 'rtt.networktables.first.wpi.edu'];
const FLUSH_MS = 20;

const TYPE_IDS: Record<string, number> = {
  boolean: 0, double: 1, int: 2, float: 3, string: 4, json: 4,
  'boolean[]': 16, 'double[]': 17, 'int[]': 18, 'float[]': 19, 'string[]': 20,
};

export function typeId(type: string): number {
  return TYPE_IDS[type] ?? 5;
}

function isRawType(type: string): boolean {
  return typeId(type) === 5;
}

interface Topic extends NtTopic {
  id: number;
  robotId?: number;
  /** Publisher count from dashboards. */
  clientPublishers: number;
}

interface Subscription {
  topics: string[];
  prefix: boolean;
  topicsOnly: boolean;
  all: boolean;
}

class Client {
  readonly subs = new Map<number, Subscription>();
  readonly pubs = new Map<number, Topic>();
  readonly announced = new Set<number>();
  readonly text: object[] = [];
  /** Pending values per topic id: all of them for `all` subscriptions, otherwise only the latest. */
  readonly values = new Map<number, Array<[number, NtValue]>>();

  constructor(
    readonly name: string,
    readonly peer: Nt4Peer,
    readonly rttOnly: boolean,
  ) {}

  matches(topic: Topic, wantValues: boolean): Subscription | undefined {
    for (const sub of this.subs.values()) {
      if (wantValues && sub.topicsOnly) continue;
      for (const t of sub.topics) {
        if (sub.prefix ? topic.name.startsWith(t) : topic.name === t) return sub;
      }
    }
    return undefined;
  }
}

export class Nt4Server {
  /** Serve only while a robot program is attached, like a robot's own server. */
  private readonly robotOnly: boolean;
  private readonly topics = new Map<string, Topic>();
  private readonly byId = new Map<number, Topic>();
  private readonly byRobotId = new Map<number, Topic>();
  private readonly clients = new Set<Client>();
  private readonly listeners = new Set<(event: NtEvent) => void>();
  private nextId = 1;
  private clockOffset = 0;
  private clockSynced = false;
  private flushTimer?: ReturnType<typeof setInterval>;
  private toRobot?: (message: BridgeMessage) => void;

  constructor({ robotOnly = false }: { robotOnly?: boolean } = {}) {
    this.robotOnly = robotOnly;
  }

  /** Server time in microseconds, kept on the robot's FPGA clock while a robot runs. */
  now(): number {
    return Math.round(performance.now() * 1000 + this.clockOffset);
  }

  /** Connects a robot program. Values set by dashboards are forwarded through `send`. */
  attachRobot(send: (message: BridgeMessage) => void): void {
    this.toRobot = send;
  }

  /** Forgets robot-published topics after the robot program exits. */
  detachRobot(): void {
    this.toRobot = undefined;
    if (this.robotOnly) {
      for (const client of [...this.clients]) {
        this.disconnect(client);
        client.peer.close(1001, 'robot program stopped');
      }
    }
    this.clockSynced = false;
    this.byRobotId.clear();
    for (const topic of [...this.topics.values()]) {
      topic.robotId = undefined;
      if (topic.clientPublishers === 0 && !this.isRetained(topic)) this.remove(topic);
    }
  }

  /** Handles an `nt.*` bridge message from the robot program. */
  handleRobotMessage(message: BridgeMessage): void {
    const data = message.data as Record<string, any>;
    switch (message.type) {
      case 'nt.announce': {
        let topic = this.topics.get(data.name);
        if (!topic) topic = this.create(data.name, data.type, data.properties ?? {});
        else if (topic.type !== data.type) {
          this.remove(topic);
          topic = this.create(data.name, data.type, data.properties ?? {});
        }
        topic.robotId = data.id;
        this.byRobotId.set(data.id, topic);
        break;
      }
      case 'nt.unannounce': {
        const topic = this.byRobotId.get(data.id) ?? this.topics.get(data.name);
        if (!topic) break;
        this.byRobotId.delete(data.id);
        topic.robotId = undefined;
        if (topic.clientPublishers === 0 && !this.isRetained(topic)) this.remove(topic);
        break;
      }
      case 'nt.properties': {
        const topic = this.topics.get(data.name);
        if (topic) this.updateProperties(topic, data.properties ?? {}, undefined);
        break;
      }
      case 'nt.value': {
        const topic = this.byRobotId.get(data.id);
        if (!topic) break;
        const ts = Number(data.ts);
        const offset = ts - performance.now() * 1000;
        // A value's timestamp is never later than its arrival, so the largest offset is the closest.
        if (!this.clockSynced || offset > this.clockOffset) {
          this.clockOffset = offset;
          this.clockSynced = true;
        }
        this.setValue(topic, fromBridge(topic.type, data.value), ts, undefined);
        break;
      }
    }
  }

  /** Called by the socket shim for each new `/nt/<name>` connection. Returns null when no subprotocol matches or no robot serves. */
  connect(path: string, protocols: string[], peer: Nt4Peer): Nt4Connection | null {
    const protocol = PROTOCOLS.find((p) => protocols.includes(p));
    if (!protocol || (this.robotOnly && !this.toRobot)) return null;
    const name = decodeURIComponent(path.replace(/^.*\/nt\//, '')) || 'client';
    const client = new Client(name, peer, protocol.startsWith('rtt.'));
    this.clients.add(client);
    this.flushTimer ??= setInterval(() => this.flush(), FLUSH_MS);
    return {
      protocol,
      send: (data) => {
        try {
          if (typeof data === 'string') this.onText(client, data);
          else this.onBinary(client, data);
        } catch (err) {
          console.warn('[nt4] bad message from', client.name, err);
        }
      },
      close: () => this.disconnect(client),
    };
  }

  /** Listens to topic changes, for in-page tools. Returns an unsubscribe function. */
  listen(listener: (event: NtEvent) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  getTopics(): NtTopic[] {
    return [...this.topics.values()];
  }

  /** Sets a value from an in-page tool, as a dashboard would. */
  publish(name: string, type: string, value: NtValue): void {
    let topic = this.topics.get(name);
    if (!topic) topic = this.create(name, type, {});
    if (topic.type !== type) return;
    this.setValue(topic, value, this.now(), undefined);
    this.forwardToRobot(topic, value);
  }

  private isRetained(topic: Topic): boolean {
    return topic.properties.retained === true || topic.properties.persistent === true;
  }

  private create(name: string, type: string, properties: Record<string, unknown>): Topic {
    const topic: Topic = { id: this.nextId++, name, type, properties, timestamp: 0, clientPublishers: 0 };
    this.topics.set(name, topic);
    this.byId.set(topic.id, topic);
    for (const client of this.clients) {
      if (client.matches(topic, false)) this.announce(client, topic);
    }
    this.emit({ kind: 'announce', topic });
    return topic;
  }

  private remove(topic: Topic): void {
    this.topics.delete(topic.name);
    this.byId.delete(topic.id);
    for (const client of this.clients) {
      client.values.delete(topic.id);
      if (client.announced.delete(topic.id)) {
        client.text.push({ method: 'unannounce', params: { name: topic.name, id: topic.id } });
      }
    }
    this.emit({ kind: 'unannounce', topic });
  }

  private announce(client: Client, topic: Topic, pubuid?: number): void {
    client.announced.add(topic.id);
    const params: Record<string, unknown> = { name: topic.name, id: topic.id, type: topic.type, properties: topic.properties };
    if (pubuid !== undefined) params.pubuid = pubuid;
    client.text.push({ method: 'announce', params });
  }

  private updateProperties(topic: Topic, update: Record<string, unknown>, from: Client | undefined): void {
    for (const [key, value] of Object.entries(update)) {
      if (value === null) delete topic.properties[key];
      else topic.properties[key] = value;
    }
    for (const client of this.clients) {
      if (!client.announced.has(topic.id)) continue;
      const params: Record<string, unknown> = { name: topic.name, update };
      if (client === from) params.ack = true;
      client.text.push({ method: 'properties', params });
    }
    this.emit({ kind: 'properties', topic });
  }

  private setValue(topic: Topic, value: NtValue, ts: number, from: Client | undefined): void {
    if (ts < topic.timestamp && topic.value !== undefined) return;
    if (topic.properties.cached !== false) {
      topic.value = value;
      topic.timestamp = ts;
    }
    for (const client of this.clients) {
      if (client === from) continue;
      const sub = client.matches(topic, true);
      if (!sub) continue;
      if (!client.announced.has(topic.id)) this.announce(client, topic);
      const pending = client.values.get(topic.id);
      if (pending && sub.all) pending.push([ts, value]);
      else client.values.set(topic.id, [[ts, value]]);
    }
    this.emit({ kind: 'value', topic });
  }

  private forwardToRobot(topic: Topic, value: NtValue): void {
    this.toRobot?.({ type: 'nt.set', device: '', data: { name: topic.name, type: topic.type, value: toBridge(topic.type, value) } });
  }

  private onText(client: Client, data: string): void {
    const messages = JSON.parse(data);
    if (!Array.isArray(messages)) return;
    for (const message of messages) {
      const params = message?.params;
      if (typeof message?.method !== 'string' || typeof params !== 'object' || params === null) continue;
      switch (message.method) {
        case 'publish': {
          let topic = this.topics.get(params.name);
          if (!topic) topic = this.create(params.name, params.type, params.properties ?? {});
          topic.clientPublishers++;
          client.pubs.set(params.pubuid, topic);
          // Some clients only look at the first announce in a frame for their publish reply.
          this.flushText(client);
          this.announce(client, topic, params.pubuid);
          this.flushText(client);
          break;
        }
        case 'unpublish': {
          const topic = client.pubs.get(params.pubuid);
          if (!topic) break;
          client.pubs.delete(params.pubuid);
          this.releasePublisher(topic);
          break;
        }
        case 'setproperties': {
          const topic = this.topics.get(params.name);
          if (topic) this.updateProperties(topic, params.update ?? {}, client);
          break;
        }
        case 'subscribe': {
          const options = params.options ?? {};
          client.subs.set(params.subuid, {
            topics: Array.isArray(params.topics) ? params.topics : [],
            prefix: options.prefix === true,
            topicsOnly: options.topicsonly === true,
            all: options.all === true,
          });
          for (const topic of this.topics.values()) {
            if (!client.matches(topic, false)) continue;
            if (!client.announced.has(topic.id)) this.announce(client, topic);
            if (topic.value !== undefined && client.matches(topic, true)) client.values.set(topic.id, [[topic.timestamp, topic.value]]);
          }
          break;
        }
        case 'unsubscribe':
          client.subs.delete(params.subuid);
          break;
      }
    }
  }

  private onBinary(client: Client, data: Uint8Array): void {
    for (const message of unpackAll(data)) {
      if (!Array.isArray(message) || message.length !== 4) continue;
      const [id, ts, type, value] = message as [number, number, number, Unpacked];
      if (id === -1) {
        const w = new Writer();
        w.array(4);
        w.int(-1);
        w.int(this.now());
        w.int(type);
        writeValue(w, type, value as NtValue);
        client.peer.binary(w.finish());
        continue;
      }
      if (client.rttOnly) continue;
      const topic = client.pubs.get(id);
      if (!topic || typeId(topic.type) !== type) continue;
      const v = normalize(topic.type, value);
      this.setValue(topic, v, ts === 0 ? this.now() : ts, client);
      this.forwardToRobot(topic, v);
    }
  }

  private releasePublisher(topic: Topic): void {
    topic.clientPublishers--;
    if (topic.clientPublishers <= 0 && topic.robotId === undefined && !this.isRetained(topic)) this.remove(topic);
  }

  private disconnect(client: Client): void {
    if (!this.clients.delete(client)) return;
    for (const topic of client.pubs.values()) this.releasePublisher(topic);
    if (this.clients.size === 0 && this.flushTimer) {
      clearInterval(this.flushTimer);
      this.flushTimer = undefined;
    }
  }

  private flush(): void {
    for (const client of this.clients) {
      this.flushText(client);
      if (client.values.size) {
        const w = new Writer();
        for (const [id, pending] of client.values) {
          const topic = this.byId.get(id);
          if (!topic) continue;
          for (const [ts, value] of pending) {
            w.array(4);
            w.int(id);
            w.int(ts);
            w.int(typeId(topic.type));
            writeValue(w, typeId(topic.type), value);
          }
        }
        client.values.clear();
        if (w.size) client.peer.binary(w.finish());
      }
    }
  }

  private flushText(client: Client): void {
    if (!client.text.length) return;
    client.peer.text(JSON.stringify(client.text));
    client.text.length = 0;
  }

  private emit(event: NtEvent): void {
    for (const listener of this.listeners) listener(event);
  }
}

export function writeValue(w: Writer, type: number, value: NtValue | Unpacked): void {
  switch (type) {
    case 0: return w.bool(Boolean(value));
    case 1: return w.f64(Number(value));
    case 2: return w.int(Math.trunc(Number(value)));
    case 3: return w.f32(Number(value));
    case 4: return w.str(String(value));
    case 5: return w.bin(value instanceof Uint8Array ? value : new Uint8Array());
    default: {
      const items = Array.isArray(value) ? value : [];
      w.array(items.length);
      for (const item of items) {
        if (type === 16) w.bool(Boolean(item));
        else if (type === 17) w.f64(Number(item));
        else if (type === 18) w.int(Math.trunc(Number(item)));
        else if (type === 19) w.f32(Number(item));
        else w.str(String(item));
      }
    }
  }
}

function normalize(type: string, value: Unpacked): NtValue {
  if (isRawType(type)) return value instanceof Uint8Array ? value : new Uint8Array();
  return value as NtValue;
}

export function fromBridge(type: string, value: unknown): NtValue {
  if (isRawType(type)) return typeof value === 'string' ? base64Decode(value) : new Uint8Array();
  return value as NtValue;
}

export function toBridge(type: string, value: NtValue): unknown {
  return isRawType(type) && value instanceof Uint8Array ? base64Encode(value) : value;
}

function base64Decode(s: string): Uint8Array {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function base64Encode(bytes: Uint8Array): string {
  let bin = '';
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}
