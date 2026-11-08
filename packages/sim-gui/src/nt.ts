import { signal } from '@preact/signals';
import type { SimHost, NtTopic, NtValue } from '@frc-dev-wasm/runtime';

const RENDER_MS = 100;

export interface TreeNode {
  /** Full path of the node, `/SmartDashboard/x`. */
  path: string;
  name: string;
  topic?: NtTopic;
  children: TreeNode[];
}

/** Topics mirrored from the host. Value changes are batched so views redraw at most every 100 ms. */
export class NtModel {
  readonly topics = new Map<string, NtTopic>();
  /** Bumps when topics are added or removed. */
  readonly structure = signal(0);
  /** Bumps at most every 100 ms while values change. */
  readonly values = signal(0);

  private readonly changed = new Set<() => void>();
  private structureDirty = false;
  private valuesDirty = false;
  private readonly timer: ReturnType<typeof setInterval>;
  private readonly unlisten: () => void;

  constructor(private readonly host: SimHost) {
    for (const topic of host.nt.getTopics()) this.topics.set(topic.name, topic);
    this.unlisten = host.nt.listen((event) => {
      if (event.kind === 'unannounce') {
        this.topics.delete(event.topic.name);
        this.structureDirty = true;
      } else {
        if (!this.topics.has(event.topic.name)) this.structureDirty = true;
        this.topics.set(event.topic.name, event.topic);
      }
      this.valuesDirty = true;
      for (const callback of this.changed) callback();
    });
    this.timer = setInterval(() => this.flush(), RENDER_MS);
    this.structure.value++;
    this.values.value++;
  }

  private flush(): void {
    if (this.structureDirty) {
      this.structureDirty = false;
      this.structure.value++;
    }
    if (this.valuesDirty) {
      this.valuesDirty = false;
      this.values.value++;
    }
  }

  /** Calls back on every change without the 100 ms batching, for views that draw on their own schedule. */
  onChange(callback: () => void): () => void {
    this.changed.add(callback);
    return () => this.changed.delete(callback);
  }

  /** Re-reads the topics from the host, for when the robot program starts or stops. */
  refresh(): void {
    this.topics.clear();
    for (const topic of this.host.nt.getTopics()) this.topics.set(topic.name, topic);
    this.structureDirty = true;
    this.valuesDirty = true;
    this.flush();
  }

  publish(topic: NtTopic, value: NtValue): void {
    this.host.nt.publish(topic.name, topic.type, value);
  }

  now(): number {
    return this.host.nt.now();
  }

  latestTimestamp(): number {
    let latest = 0;
    for (const topic of this.topics.values()) if (topic.timestamp > latest) latest = topic.timestamp;
    return latest;
  }

  value(name: string): NtValue | undefined {
    return this.topics.get(name)?.value;
  }

  /** Tables whose `.type` topic equals `type`, such as Field2d. */
  tablesOfType(type: string): string[] {
    const found: string[] = [];
    for (const topic of this.topics.values()) {
      if (topic.name.endsWith('/.type') && topic.value === type) found.push(topic.name.slice(0, -6));
    }
    return found.sort();
  }

  dispose(): void {
    clearInterval(this.timer);
    this.unlisten();
  }
}

export function buildTree(topics: Iterable<NtTopic>): TreeNode {
  const root: TreeNode = { path: '', name: '', children: [] };
  const index = new Map<string, TreeNode>([['', root]]);
  for (const topic of topics) {
    const parts = topic.name.split('/').filter(Boolean);
    let parent = root;
    let path = '';
    for (const part of parts) {
      path += `/${part}`;
      let node = index.get(path);
      if (!node) {
        node = { path, name: part, children: [] };
        index.set(path, node);
        parent.children.push(node);
      }
      parent = node;
    }
    parent.topic = topic;
  }
  const sort = (node: TreeNode) => {
    node.children.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
    node.children.forEach(sort);
  };
  sort(root);
  return root;
}

export function formatValue(value: NtValue | undefined): string {
  if (value === undefined) return '';
  if (typeof value === 'number') return String(Number(value.toPrecision(7)));
  if (typeof value === 'string') return value;
  if (typeof value === 'boolean') return String(value);
  if (value instanceof Uint8Array) return `${value.length} bytes`;
  const items = value.slice(0, 8).map((v) => (typeof v === 'number' ? Number(v.toPrecision(7)) : v));
  return `[${items.join(', ')}${value.length > 8 ? `, ... (${value.length})` : ''}]`;
}

/** Parses edited text for an NT type. Returns undefined when it does not fit. */
export function parseValue(type: string, text: string): NtValue | undefined {
  const items = () => text.replace(/^\s*\[|\]\s*$/g, '').split(',').map((s) => s.trim()).filter((s) => s !== '');
  switch (type) {
    case 'boolean':
      return text === 'true' ? true : text === 'false' ? false : undefined;
    case 'double':
    case 'float':
    case 'int': {
      const n = Number(text);
      return text.trim() !== '' && Number.isFinite(n) ? n : undefined;
    }
    case 'string':
    case 'json':
      return text;
    case 'double[]':
    case 'float[]':
    case 'int[]': {
      const numbers = items().map(Number);
      return numbers.every(Number.isFinite) ? numbers : undefined;
    }
    case 'boolean[]': {
      const flags = items();
      return flags.every((s) => s === 'true' || s === 'false') ? flags.map((s) => s === 'true') : undefined;
    }
    case 'string[]':
      try {
        const parsed: unknown = JSON.parse(text);
        return Array.isArray(parsed) && parsed.every((s) => typeof s === 'string') ? (parsed as string[]) : undefined;
      } catch {
        return items();
      }
    default:
      return undefined;
  }
}

export function isEditableType(type: string): boolean {
  return ['boolean', 'double', 'float', 'int', 'string', 'json', 'double[]', 'float[]', 'int[]', 'boolean[]', 'string[]'].includes(type);
}
