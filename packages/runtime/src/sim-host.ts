import type { Nt4Server } from './nt4-server.ts';
import type { BridgeMessage } from './robot.ts';

export type SimStatus = 'stopped' | 'starting' | 'running';

/**
 * What the IDE window offers the Sim GUI page. The page finds it at
 * `window.parent[SIM_HOST_GLOBAL]`, since it runs in a same-origin iframe.
 */
export interface SimHost {
  /** Bridge messages from the robot program as they arrive. Returns an unsubscribe function. */
  listen(listener: (messages: BridgeMessage[]) => void): () => void;
  /** Sends a message to the robot program. Ignored when none is running. */
  send(message: BridgeMessage): void;
  /** Current state of every initialized HAL device, one merged message per device. */
  snapshot(): BridgeMessage[];
  status(): SimStatus;
  /** WPILib year of the robot project, which decides the keys of the HAL messages. */
  year(): string;
  /** Workspace path of the robot project being simulated, where the Sim GUI keeps its layout. */
  projectRoot?(): string | undefined;
  onStatus(listener: (status: SimStatus) => void): () => void;
  readonly nt: Pick<Nt4Server, 'listen' | 'getTopics' | 'publish' | 'now'>;
}

export const SIM_HOST_GLOBAL = 'frcDevWasmSim';

/** Merges HAL device deltas into full device state. */
export class DeviceState {
  private readonly devices = new Map<string, BridgeMessage>();

  apply(message: BridgeMessage): void {
    if (message.type.startsWith('nt.') || message.type === 'console' || message.type === 'exit') return;
    const key = `${message.type}\u0000${message.device}`;
    if (message.data['<init'] === false) {
      this.devices.delete(key);
      return;
    }
    const current = this.devices.get(key);
    if (current) Object.assign(current.data, message.data);
    else this.devices.set(key, { type: message.type, device: message.device, data: { ...message.data } });
  }

  snapshot(): BridgeMessage[] {
    return [...this.devices.values()].map((m) => ({ type: m.type, device: m.device, data: { ...m.data } }));
  }

  clear(): void {
    this.devices.clear();
  }
}
