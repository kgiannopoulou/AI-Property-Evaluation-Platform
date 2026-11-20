"use client";

import { useSyncExternalStore } from "react";

/**
 * Tiny localStorage-backed store for the Phase 3 prototype.
 *
 * Phase 4 replaces these stores with calls to the Spring Boot API; the hooks
 * keep the same shape so pages do not need to change.
 */
export type LocalStore<T> = {
  get(): T;
  set(next: T | ((prev: T) => T)): void;
  subscribe(listener: () => void): () => void;
  reset(): void;
};

export class StorageFullError extends Error {
  constructor() {
    super("Browser storage is full. Remove some photos or saved properties and try again.");
  }
}

export function createLocalStore<T>(key: string, initial: () => T): LocalStore<T> {
  const listeners = new Set<() => void>();
  let cache: { raw: string | null; value: T } | undefined;

  const read = (): T => {
    if (typeof window === "undefined") return initial();
    let raw: string | null = null;
    try {
      raw = window.localStorage.getItem(key);
    } catch {
      raw = null;
    }
    if (cache && cache.raw === raw) return cache.value;
    let value: T;
    if (raw === null) {
      value = initial();
      try {
        raw = JSON.stringify(value);
        window.localStorage.setItem(key, raw);
      } catch {
        /* private mode — keep in memory only */
      }
    } else {
      try {
        value = JSON.parse(raw) as T;
      } catch {
        value = initial();
      }
    }
    cache = { raw, value };
    return value;
  };

  const emit = () => listeners.forEach((l) => l());

  if (typeof window !== "undefined") {
    window.addEventListener("storage", (e) => {
      if (e.key === key) emit();
    });
  }

  return {
    get: read,
    set(next) {
      const value = typeof next === "function" ? (next as (prev: T) => T)(read()) : next;
      const raw = JSON.stringify(value);
      try {
        window.localStorage.setItem(key, raw);
      } catch {
        throw new StorageFullError();
      }
      cache = { raw, value };
      emit();
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    reset() {
      try {
        window.localStorage.removeItem(key);
      } catch {
        /* ignore */
      }
      cache = undefined;
      emit();
    },
  };
}

/**
 * Subscribe a component to a store. On the server and during hydration the
 * `serverValue` is used, so pages render a stable shell first.
 */
export function useLocalStore<T>(store: LocalStore<T>, serverValue: T): T {
  return useSyncExternalStore(store.subscribe, store.get, () => serverValue);
}

/** True once the component is running in the browser (after hydration). */
export function useHydrated(): boolean {
  return useSyncExternalStore(
    noopSubscribe,
    () => true,
    () => false,
  );
}
const noopSubscribe = () => () => {};
