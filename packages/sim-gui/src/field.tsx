import { useEffect, useRef, useState } from 'preact/hooks';
import { useStore } from './context.ts';
import {
  ROBOT_LENGTH,
  ROBOT_WIDTH,
  fieldObjects,
  loadField,
  readMechanism,
  type FieldImage,
  type FieldObject,
  type Ligament,
  type Mechanism,
} from './field.ts';
import { Empty, Panel } from './ui.tsx';

const CHART_COLORS = ['blue', 'green', 'orange', 'purple', 'yellow'];
const FALLBACKS: Record<string, string> = {
  red: '#f14c4c',
  blue: '#3794ff',
  green: '#89d185',
  orange: '#d18616',
  purple: '#b180d7',
  yellow: '#e2c08d',
};

function chartColor(el: HTMLElement, name: string): string {
  return getComputedStyle(el).getPropertyValue(`--vscode-charts-${name}`).trim() || FALLBACKS[name];
}

function objectColors(el: HTMLElement, objects: FieldObject[]): string[] {
  let next = 0;
  return objects.map((o) => (o.name === 'Robot' ? chartColor(el, 'red') : chartColor(el, CHART_COLORS[next++ % CHART_COLORS.length])));
}

/** Runs `draw` on a canvas sized to its container, whenever NT values change or the view resizes. */
function useCanvas(aspect: number | undefined, draw: (ctx: CanvasRenderingContext2D, width: number, height: number, scale: number) => void) {
  const { nt } = useStore();
  const ref = useRef<HTMLCanvasElement>(null);
  const latest = useRef(draw);
  latest.current = draw;
  const request = useRef<() => void>(() => {});

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas || !aspect) return;
    let frame = 0;
    const paint = () => {
      frame = 0;
      const width = canvas.clientWidth;
      if (!width) return;
      const height = Math.round(width * aspect);
      const scale = window.devicePixelRatio || 1;
      canvas.style.height = `${height}px`;
      canvas.width = Math.round(width * scale);
      canvas.height = Math.round(height * scale);
      const ctx = canvas.getContext('2d');
      if (!ctx) return;
      ctx.scale(scale, scale);
      latest.current(ctx, width, height, scale);
    };
    request.current = () => {
      if (!frame) frame = requestAnimationFrame(paint);
    };
    const unsubscribe = nt.onChange(request.current);
    const observer = new ResizeObserver(request.current);
    observer.observe(canvas);
    request.current();
    return () => {
      unsubscribe();
      observer.disconnect();
      cancelAnimationFrame(frame);
    };
  }, [nt, aspect]);

  useEffect(() => request.current());
  return { ref, request };
}

function pathPose(ctx: CanvasRenderingContext2D, field: FieldImage, k: number, x: number, y: number): [number, number] {
  return [(field.originX + x * field.pixelsPerMeter) * k, (field.originY - y * field.pixelsPerMeter) * k];
}

function drawObjects(ctx: CanvasRenderingContext2D, field: FieldImage, k: number, objects: FieldObject[], colors: string[]): void {
  const m = field.pixelsPerMeter * k;
  objects.forEach((object, i) => {
    ctx.strokeStyle = colors[i];
    ctx.fillStyle = colors[i];
    ctx.lineWidth = 2;
    const isRobot = object.name === 'Robot';
    if (object.poses.length > 1) {
      ctx.beginPath();
      object.poses.forEach((p, j) => {
        const [px, py] = pathPose(ctx, field, k, p.x, p.y);
        if (j === 0) ctx.moveTo(px, py);
        else ctx.lineTo(px, py);
      });
      ctx.stroke();
      return;
    }
    for (const pose of object.poses) {
      const [cx, cy] = pathPose(ctx, field, k, pose.x, pose.y);
      const rad = (pose.deg * Math.PI) / 180;
      ctx.save();
      ctx.translate(cx, cy);
      ctx.rotate(-rad);
      if (isRobot) {
        const l = ROBOT_LENGTH * m;
        const w = ROBOT_WIDTH * m;
        ctx.globalAlpha = 0.35;
        ctx.fillRect(-l / 2, -w / 2, l, w);
        ctx.globalAlpha = 1;
        ctx.strokeRect(-l / 2, -w / 2, l, w);
        ctx.beginPath();
        ctx.moveTo(0, 0);
        ctx.lineTo(l / 2, 0);
        ctx.stroke();
      } else {
        const r = Math.max(0.12 * m, 4);
        ctx.beginPath();
        ctx.arc(0, 0, r, 0, Math.PI * 2);
        ctx.fill();
        ctx.beginPath();
        ctx.moveTo(0, 0);
        ctx.lineTo(r * 2, 0);
        ctx.stroke();
      }
      ctx.restore();
    }
  });
}

function useField() {
  const [state, setState] = useState<{ field?: FieldImage; error?: string }>({});
  useEffect(() => {
    let live = true;
    loadField().then(
      (field) => live && setState({ field }),
      (error: Error) => live && setState({ error: error.message }),
    );
    return () => {
      live = false;
    };
  }, []);
  return state;
}

const pose = (n: number) => n.toFixed(2);

function FieldView({ table }: { table: string }) {
  const { nt } = useStore();
  nt.values.value;
  const { field, error } = useField();
  const objects = fieldObjects(nt, table);
  const aspect = field ? field.image.height / field.image.width : undefined;
  const scaled = useRef<{ width: number; bitmap?: ImageBitmap }>(undefined);
  const { ref, request } = useCanvas(aspect, (ctx, width, _height, scale) => {
    if (!field) return;
    // Scaling the large image once keeps redraws cheap.
    const target = Math.round(width * scale);
    let source: ImageBitmap = field.image;
    if (scaled.current?.width === target) source = scaled.current.bitmap ?? source;
    else {
      const entry = (scaled.current = { width: target } as { width: number; bitmap?: ImageBitmap });
      createImageBitmap(field.image, { resizeWidth: target, resizeQuality: 'high' }).then((bitmap) => {
        entry.bitmap = bitmap;
        request.current();
      });
    }
    ctx.drawImage(source, 0, 0, width, width * (field.image.height / field.image.width));
    drawObjects(ctx, field, width / field.image.width, objects, objectColors(ref.current!, objects));
  });
  const colors = ref.current ? objectColors(ref.current, objects) : [];
  return (
    <div class="field">
      <h3>{table}</h3>
      {error ? <p class="error">{error}</p> : null}
      {field || error ? null : <Empty>Loading field image</Empty>}
      <canvas ref={ref} class="field-canvas" role="img" aria-label={`Field view of ${table}`} hidden={!field} />
      <ul class="legend">
        {objects.map((o, i) => (
          <li>
            <span class="swatch" style={{ background: colors[i] }} aria-hidden="true" />
            <span class="grow">{o.name}</span>
            <span class="value">
              {o.poses.length === 1
                ? `${pose(o.poses[0].x)} m, ${pose(o.poses[0].y)} m, ${o.poses[0].deg.toFixed(1)} deg`
                : `${o.poses.length} poses`}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function drawLigaments(ctx: CanvasRenderingContext2D, items: Ligament[], x: number, y: number, parentAngle: number, k: number): void {
  for (const item of items) {
    const angle = parentAngle + (item.angle * Math.PI) / 180;
    const ex = x + Math.cos(angle) * item.length * k;
    const ey = y - Math.sin(angle) * item.length * k;
    ctx.strokeStyle = item.color;
    ctx.lineWidth = item.weight;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(x, y);
    ctx.lineTo(ex, ey);
    ctx.stroke();
    drawLigaments(ctx, item.children, ex, ey, angle, k);
  }
}

function drawMechanism(ctx: CanvasRenderingContext2D, width: number, height: number, mechanism: Mechanism): void {
  const k = width / mechanism.width;
  ctx.fillStyle = mechanism.background;
  ctx.fillRect(0, 0, width, height);
  for (const root of mechanism.roots) drawLigaments(ctx, root.ligaments, root.x * k, height - root.y * k, 0, k);
}

function MechanismView({ table }: { table: string }) {
  const { nt } = useStore();
  nt.values.value;
  const mechanism = readMechanism(nt, table);
  const aspect = mechanism.width > 0 && mechanism.height > 0 ? mechanism.height / mechanism.width : 1;
  const { ref } = useCanvas(aspect, (ctx, width, height) => drawMechanism(ctx, width, height, mechanism));
  return (
    <div class="field">
      <h3>{table}</h3>
      <canvas ref={ref} class="field-canvas mechanism" role="img" aria-label={`Mechanism view of ${table}`} />
    </div>
  );
}

export function Field() {
  const { nt } = useStore();
  nt.structure.value;
  nt.values.value;
  const fields = nt.tablesOfType('Field2d');
  if (fields.length === 0) return null;
  return (
    <Panel id="field" title="Field" wide>
      {fields.map((table) => (
        <FieldView table={table} />
      ))}
    </Panel>
  );
}

export function Mechanisms() {
  const { nt } = useStore();
  nt.structure.value;
  nt.values.value;
  const tables = nt.tablesOfType('Mechanism2d');
  if (tables.length === 0) return null;
  return (
    <Panel id="mechanisms" title="Mechanisms">
      {tables.map((table) => (
        <MechanismView table={table} />
      ))}
    </Panel>
  );
}
