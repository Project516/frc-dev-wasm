import { createContext } from 'preact';
import { useContext } from 'preact/hooks';
import type { SimStore } from './store.ts';

const StoreContext = createContext<SimStore | undefined>(undefined);

export const StoreProvider = StoreContext.Provider;

export function useStore(): SimStore {
  const store = useContext(StoreContext);
  if (!store) throw new Error('SimStore missing');
  return store;
}
