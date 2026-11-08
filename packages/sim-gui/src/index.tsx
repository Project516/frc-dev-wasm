import { render } from 'preact';
import type { SIM_HOST_GLOBAL as RuntimeHostGlobal, SimHost } from '@frc-dev-wasm/runtime';
import { App } from './app.tsx';
import { StoreProvider } from './context.ts';
import { SimStore } from './store.ts';
import { syncTheme } from './theme.ts';
import './style.css';

// The type annotation fails to compile if the runtime renames the global.
const HOST_GLOBAL: typeof RuntimeHostGlobal = 'frcDevWasmSim';

/** The SimHost the IDE window offers to this page, or undefined when the page is not embedded. */
export function findSimHost(): SimHost | undefined {
  try {
    return (window.parent as unknown as Record<string, SimHost | undefined>)[HOST_GLOBAL];
  } catch {
    return undefined;
  }
}

/** Renders the Sim GUI into `root`. Returns a function that unmounts it. */
export function mountSimGui(root: HTMLElement, host: SimHost): () => void {
  const store = new SimStore(host);
  let stopTheme = () => {};
  try {
    if (window.parent !== window) stopTheme = syncTheme(window.parent.document, document);
  } catch {
    // parent is cross-origin: keep the fallback theme
  }
  render(
    <StoreProvider value={store}>
      <App />
    </StoreProvider>,
    root,
  );
  return () => {
    render(null, root);
    store.dispose();
    stopTheme();
  };
}
