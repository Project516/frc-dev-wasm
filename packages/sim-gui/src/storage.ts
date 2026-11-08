import { effect, signal, type Signal } from '@preact/signals';

const PREFIX = 'frc-dev-wasm.sim.';

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function load<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(PREFIX + key);
    if (raw === null) return fallback;
    const parsed: unknown = JSON.parse(raw);
    if (isRecord(fallback) && isRecord(parsed)) return { ...fallback, ...parsed };
    if (Array.isArray(fallback) === Array.isArray(parsed) && typeof parsed === typeof fallback) return parsed as T;
  } catch {
    // unreadable storage or corrupt JSON
  }
  return fallback;
}

/** A signal that is restored from and saved to localStorage. */
export function persisted<T>(key: string, fallback: T): Signal<T> {
  const state = signal(load(key, fallback));
  effect(() => {
    try {
      localStorage.setItem(PREFIX + key, JSON.stringify(state.value));
    } catch {
      // storage unavailable or full
    }
  });
  return state;
}
