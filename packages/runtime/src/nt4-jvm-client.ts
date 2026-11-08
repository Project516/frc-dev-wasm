// Connects a JVM that has no sockets to the NT4 server in the page. The JVM sends
// NetworkTables client messages (docs/bridge-protocol.md); this turns them into
// NT4 frames on a connection like any dashboard's, and the server's frames back.
import type { BridgeMessage } from './robot.ts';
import type { SwingBridge } from './swing.ts';
import { Writer, unpackAll, type Unpacked } from './msgpack.ts';
import { fromBridge, toBridge, typeId, writeValue, type Nt4Connection, type Nt4Server, type NtValue } from './nt4-server.ts';

const PROTOCOL = 'v4.1.networktables.first.wpi.edu';
const SUBSCRIPTION = 1;
const RETRY_MS = 1000;

export class Nt4JvmClient implements SwingBridge {
  private conn?: Nt4Connection;
  /** Set from nt.hello to nt.bye: the JVM wants a connection, and gets one again after it is lost. */
  private identity?: string;
  private retry?: ReturnType<typeof setTimeout>;
  private send: (message: BridgeMessage) => void = () => undefined;
  /** NT4 type of each topic the server announced, by server id. */
  private readonly served = new Map<number, string>();
  /** NT4 type of each topic the JVM publishes, by the JVM's id for it. */
  private readonly published = new Map<number, string>();

  constructor(private readonly server: Nt4Server) {}

  /** Handles messages from the JVM, which `reply` answers. A connection lasts from `nt.hello` to `nt.bye`. */
  onMessages(messages: BridgeMessage[], reply: (message: BridgeMessage) => void): void {
    this.send = reply;
    for (const message of messages) {
      const data = message.data as Record<string, any>;
      switch (message.type) {
        case 'nt.hello':
          this.identity = String(data.identity || 'JVM');
          this.connect();
          break;
        case 'nt.bye':
          this.identity = undefined;
          this.close();
          break;
        case 'nt.announce':
          this.published.set(data.id, data.type);
          this.text({ method: 'publish', params: { name: data.name, type: data.type, pubuid: data.id, properties: data.properties ?? {} } });
          break;
        case 'nt.unannounce':
          this.published.delete(data.id);
          this.text({ method: 'unpublish', params: { pubuid: data.id } });
          break;
        case 'nt.properties':
          this.text({ method: 'setproperties', params: { name: data.name, update: data.properties ?? {} } });
          break;
        case 'nt.value': {
          const type = this.published.get(data.id);
          if (type === undefined) break;
          const w = new Writer();
          w.array(4);
          w.int(data.id);
          w.int(Number(data.ts) || 0);
          w.int(typeId(type));
          writeValue(w, typeId(type), fromBridge(type, data.value));
          this.conn?.send(w.finish());
          break;
        }
      }
    }
  }

  close(): void {
    clearTimeout(this.retry);
    this.retry = undefined;
    this.conn?.close();
    this.forget();
  }

  private forget(): void {
    this.conn = undefined;
    this.served.clear();
    this.published.clear();
  }

  // The server refuses connections and drops them while no robot program runs.
  private connect(): void {
    this.close();
    if (this.identity === undefined) return;
    this.conn = this.server.connect(`/nt/${encodeURIComponent(this.identity)}`, [PROTOCOL], {
      text: (data) => this.onText(data),
      binary: (data) => this.onBinary(data),
      close: () => {
        this.forget();
        this.send({ type: 'nt.disconnected', device: '', data: {} });
        this.retry = setTimeout(() => this.connect(), RETRY_MS);
      },
    }) ?? undefined;
    if (!this.conn) {
      this.retry = setTimeout(() => this.connect(), RETRY_MS);
      return;
    }
    this.text({ method: 'subscribe', params: { topics: [''], subuid: SUBSCRIPTION, options: { prefix: true, all: true } } });
    this.send({ type: 'nt.connected', device: '', data: {} });
  }

  private text(message: object): void {
    this.conn?.send(JSON.stringify([message]));
  }

  private onText(frame: string): void {
    for (const message of JSON.parse(frame) as Array<{ method: string; params: Record<string, any> }>) {
      const p = message.params;
      if (message.method === 'announce') {
        // The reply to our own publish repeats an announce that subscribing already sent.
        if (p.pubuid !== undefined) continue;
        this.served.set(p.id, p.type);
        this.send({ type: 'nt.announce', device: '', data: { id: p.id, name: p.name, type: p.type, properties: p.properties ?? {} } });
      } else if (message.method === 'unannounce') {
        this.served.delete(p.id);
        this.send({ type: 'nt.unannounce', device: '', data: { id: p.id, name: p.name } });
      } else if (message.method === 'properties') {
        this.send({ type: 'nt.properties', device: '', data: { name: p.name, properties: p.update ?? {} } });
      }
    }
  }

  private onBinary(frame: Uint8Array): void {
    for (const message of unpackAll(frame)) {
      if (!Array.isArray(message) || message.length !== 4) continue;
      const [id, ts, , value] = message as [number, number, number, Unpacked];
      const type = this.served.get(id);
      if (type === undefined) continue;
      this.send({ type: 'nt.value', device: '', data: { id, ts, value: toBridge(type, value as NtValue) } });
    }
  }
}
