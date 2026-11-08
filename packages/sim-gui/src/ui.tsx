import type { ComponentChildren } from 'preact';
import { useState } from 'preact/hooks';
import { persisted } from './storage.ts';

const collapsed = persisted<Record<string, boolean>>('panels.collapsed', {});

function isCollapsed(id: string): boolean {
  return collapsed.value[id] ?? false;
}

interface PanelProps {
  id: string;
  title: string;
  /** Spans two grid columns when there is room. */
  wide?: boolean;
  /** Spans the whole row. */
  full?: boolean;
  /** Short status text shown in the header. */
  badge?: string;
  children: ComponentChildren;
}

export function Panel({ id, title, wide, full, badge, children }: PanelProps) {
  const closed = isCollapsed(id);
  const toggle = () => (collapsed.value = { ...collapsed.value, [id]: !closed });
  return (
    <section class={`panel${wide ? ' wide' : ''}${full ? ' full' : ''}`} aria-labelledby={`${id}-title`}>
      <h2 class="panel-head">
        <button type="button" id={`${id}-title`} aria-expanded={!closed} aria-controls={`${id}-body`} onClick={toggle}>
          <span class="chevron" aria-hidden="true" />
          {title}
        </button>
        {badge ? <span class="badge">{badge}</span> : null}
      </h2>
      <div class="panel-body" id={`${id}-body`} hidden={closed}>
        {closed ? null : children}
      </div>
    </section>
  );
}

const trim = (n: number) => String(Number(n.toPrecision(7)));

interface NumInputProps {
  value: number;
  onCommit: (value: number) => void;
  label: string;
  min?: number;
  max?: number;
  step?: number | 'any';
  disabled?: boolean;
  class?: string;
}

/** Number field that keeps what the user is typing until it is committed. */
export function NumInput({ value, onCommit, label, min, max, step = 'any', disabled, class: cls }: NumInputProps) {
  const [draft, setDraft] = useState<string | null>(null);
  return (
    <input
      class={`num ${cls ?? ''}`}
      type="number"
      aria-label={label}
      value={draft ?? (Number.isFinite(value) ? trim(value) : '')}
      min={min}
      max={max}
      step={step}
      disabled={disabled}
      onInput={(e) => setDraft(e.currentTarget.value)}
      onChange={(e) => {
        const n = e.currentTarget.valueAsNumber;
        if (!Number.isNaN(n)) onCommit(n);
        setDraft(null);
      }}
      onBlur={() => setDraft(null)}
    />
  );
}

interface TextInputProps {
  value: string;
  onCommit: (value: string) => void;
  label: string;
  invalid?: boolean;
  class?: string;
}

export function TextInput({ value, onCommit, label, invalid, class: cls }: TextInputProps) {
  const [draft, setDraft] = useState<string | null>(null);
  return (
    <input
      class={`text ${cls ?? ''}`}
      type="text"
      aria-label={label}
      aria-invalid={invalid ? 'true' : undefined}
      value={draft ?? value}
      spellcheck={false}
      onInput={(e) => setDraft(e.currentTarget.value)}
      onChange={(e) => {
        onCommit(e.currentTarget.value);
        setDraft(null);
      }}
      onBlur={() => setDraft(null)}
    />
  );
}

export function Check({ checked, onChange, label, disabled }: { checked: boolean; onChange: (v: boolean) => void; label: string; disabled?: boolean }) {
  return (
    <label class="check">
      <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.currentTarget.checked)} />
      {label}
    </label>
  );
}

/** A lamp that differs in shape fill, not only color, between on and off. */
export function Led({ on, label }: { on: boolean; label: string }) {
  return <span class={`led${on ? ' on' : ''}`} role="img" aria-label={`${label}: ${on ? 'on' : 'off'}`} />;
}

interface BarProps {
  value: number;
  min: number;
  max: number;
  label: string;
}

/** Horizontal meter. A range that includes zero fills from the center. */
export function Bar({ value, min, max, label }: BarProps) {
  const span = max - min;
  const zero = Math.min(Math.max((0 - min) / span, 0), 1);
  const at = Math.min(Math.max((value - min) / span, 0), 1);
  const left = Math.min(zero, at) * 100;
  const width = Math.abs(at - zero) * 100;
  return (
    <span class="bar" role="meter" aria-label={label} aria-valuemin={min} aria-valuemax={max} aria-valuenow={value}>
      <span class="bar-fill" style={{ left: `${left}%`, width: `${width}%` }} />
    </span>
  );
}

export function Empty({ children }: { children: ComponentChildren }) {
  return <p class="empty">{children}</p>;
}
