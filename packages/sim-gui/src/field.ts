import type { NtValue } from '@frc-dev-wasm/runtime';
import type { NtModel } from './nt.ts';

const FIELD_JAR = 'runtime/2026/wpilib/fieldImages.jar';
const FIELD_JSON = '2026-rebuilt.json';
const FIELD_PNG = '2026-field.png';
const METERS_PER_FOOT = 0.3048;

export const ROBOT_LENGTH = 0.8204;
export const ROBOT_WIDTH = 0.6858;

export interface FieldImage {
  image: ImageBitmap;
  /** Field origin (bottom left corner of the playing field) in image pixels. */
  originX: number;
  originY: number;
  pixelsPerMeter: number;
}

export interface Pose {
  x: number;
  y: number;
  deg: number;
}

export interface FieldObject {
  name: string;
  poses: Pose[];
}

interface FieldConfig {
  'field-corners': { 'top-left': [number, number]; 'bottom-right': [number, number] };
  'field-size': [number, number];
  'field-unit': string;
}

let cached: Promise<FieldImage> | undefined;

/** Fetches the field image once. The jar is large, so this only runs when a Field2d is shown. */
export function loadField(): Promise<FieldImage> {
  cached ??= load().catch((error) => {
    cached = undefined;
    throw error;
  });
  return cached;
}

async function load(): Promise<FieldImage> {
  const response = await fetch(new URL(FIELD_JAR, document.baseURI));
  if (!response.ok) throw new Error(`Field images unavailable (${response.status}). Run pnpm fetch-deps.`);
  const { unzipSync } = await import('fflate');
  const files = unzipSync(new Uint8Array(await response.arrayBuffer()), {
    filter: (file) => file.name.endsWith(`/${FIELD_JSON}`) || file.name.endsWith(`/${FIELD_PNG}`),
  });
  const find = (suffix: string) => Object.entries(files).find(([name]) => name.endsWith(suffix))?.[1];
  const json = find(FIELD_JSON);
  const png = find(FIELD_PNG);
  if (!json || !png) throw new Error('Field images jar has no 2026 field');
  const config = JSON.parse(new TextDecoder().decode(json)) as FieldConfig;
  const unit = config['field-unit'] === 'foot' ? METERS_PER_FOOT : 1;
  const [left, top] = config['field-corners']['top-left'];
  const [right, bottom] = config['field-corners']['bottom-right'];
  return {
    image: await createImageBitmap(new Blob([png as Uint8Array<ArrayBuffer>], { type: 'image/png' })),
    originX: left,
    originY: bottom,
    pixelsPerMeter: (right - left) / (config['field-size'][0] * unit),
  };
}

const POSE_BYTES = 24;

/** Poses from a double[] topic, or from the little-endian raw encoding WPILib uses for long trajectories. */
function readPoses(value: NtValue | undefined): Pose[] | undefined {
  const poses: Pose[] = [];
  if (Array.isArray(value) && value.length % 3 === 0) {
    for (let i = 0; i < value.length; i += 3) poses.push({ x: Number(value[i]), y: Number(value[i + 1]), deg: Number(value[i + 2]) });
    return poses;
  }
  if (value instanceof Uint8Array && value.length % POSE_BYTES === 0) {
    const view = new DataView(value.buffer, value.byteOffset, value.byteLength);
    for (let i = 0; i < value.length; i += POSE_BYTES) {
      poses.push({ x: view.getFloat64(i, true), y: view.getFloat64(i + 8, true), deg: view.getFloat64(i + 16, true) });
    }
    return poses;
  }
  return undefined;
}

/** The pose topics directly under a Field2d table. */
export function fieldObjects(nt: NtModel, table: string): FieldObject[] {
  const objects: FieldObject[] = [];
  for (const topic of nt.topics.values()) {
    if (!topic.name.startsWith(`${table}/`)) continue;
    const name = topic.name.slice(table.length + 1);
    if (name.includes('/') || name.startsWith('.') || (topic.type !== 'double[]' && topic.type !== 'raw')) continue;
    const poses = readPoses(topic.value);
    if (poses) objects.push({ name, poses });
  }
  return objects.sort((a, b) => (a.name === 'Robot' ? -1 : b.name === 'Robot' ? 1 : a.name < b.name ? -1 : 1));
}

export interface Ligament {
  angle: number;
  length: number;
  color: string;
  weight: number;
  children: Ligament[];
}

export interface Mechanism {
  width: number;
  height: number;
  background: string;
  roots: { x: number; y: number; ligaments: Ligament[] }[];
}

function number(nt: NtModel, name: string, fallback: number): number {
  const value = nt.value(name);
  return typeof value === 'number' ? value : fallback;
}

function children(nt: NtModel, path: string, test: (segment: string) => boolean): string[] {
  const found = new Set<string>();
  for (const name of nt.topics.keys()) {
    if (!name.startsWith(`${path}/`)) continue;
    const segment = name.slice(path.length + 1).split('/')[0];
    if (!segment.startsWith('.') && test(segment)) found.add(segment);
  }
  return [...found].sort();
}

function ligaments(nt: NtModel, path: string): Ligament[] {
  return children(nt, path, (s) => nt.value(`${path}/${s}/.type`) === 'line').map((name) => {
    const own = `${path}/${name}`;
    const color = nt.value(`${own}/color`);
    return {
      angle: number(nt, `${own}/angle`, 0),
      length: number(nt, `${own}/length`, 0),
      color: typeof color === 'string' ? color : '#ffffff',
      weight: number(nt, `${own}/weight`, 6),
      children: ligaments(nt, own),
    };
  });
}

/** Reads a Mechanism2d table in the layout WPILib publishes. */
export function readMechanism(nt: NtModel, table: string): Mechanism {
  const dims = nt.value(`${table}/dims`);
  const background = nt.value(`${table}/backgroundColor`);
  const [width, height] = Array.isArray(dims) && dims.length === 2 ? [Number(dims[0]), Number(dims[1])] : [1, 1];
  return {
    width,
    height,
    background: typeof background === 'string' ? background : '#000020',
    roots: children(nt, table, (s) => nt.value(`${table}/${s}/x`) !== undefined).map((name) => ({
      x: number(nt, `${table}/${name}/x`, 0),
      y: number(nt, `${table}/${name}/y`, 0),
      ligaments: ligaments(nt, `${table}/${name}`),
    })),
  };
}
