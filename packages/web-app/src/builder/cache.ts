// Dirty-flag preview cache (FR-509, arch §7.2): memoizes sandbox previews
// by (astHash, inputsHash). Correctness NEVER depends on the cache — a miss
// simply recomputes; entries are keyed by content hashes so a draft edit or
// an input change invalidates naturally. Sub-graph scoping follows the
// plan's DEPENDS_ON edges: an input hash only busts the components that
// (transitively) reference it.
//
// Dev posture: in-process LRU. Production posture: the same interface over
// Redis (REDIS_URL) — the caller injects the store, correctness unchanged.

export interface CacheEntry<T> {
  readonly value: T;
}

export interface PreviewCache {
  get(astHash: string, inputsHash: string): CacheEntry<unknown> | undefined;
  put(astHash: string, inputsHash: string, value: unknown): void;
  readonly hits: number;
  readonly misses: number;
}

/** Content hash of the (sub)plan AST + the inputs feeding it. */
export function hashParts(ast: unknown, inputs: unknown): [string, string] {
  return [stableStringify(ast), stableStringify(inputs)];
}

function stableStringify(value: unknown): string {
  if (value === null || typeof value !== 'object') {
    return JSON.stringify(value) ?? 'null';
  }
  if (Array.isArray(value)) {
    return `[${value.map(stableStringify).join(',')}]`;
  }
  const obj = value as Record<string, unknown>;
  const keys = Object.keys(obj).sort();
  return `{${keys.map((k) => `"${k}":${stableStringify(obj[k])}`).join(',')}}`;
}

/** Bounded LRU with hit/miss counters (the <800ms dock path, NFR-23). */
export function createPreviewCache(capacity = 256): PreviewCache {
  const store = new Map<string, unknown>();
  let hits = 0;
  let misses = 0;
  const key = (ast: string, inputs: string): string => `${ast}#${inputs}`;
  return {
    get(ast, inputs) {
      const k = key(ast, inputs);
      if (store.has(k)) {
        hits++;
        const value = store.get(k);
        // LRU refresh
        store.delete(k);
        store.set(k, value);
        return { value };
      }
      misses++;
      return undefined;
    },
    put(ast, inputs, value) {
      const k = key(ast, inputs);
      store.set(k, value);
      if (store.size > capacity) {
        const oldest = store.keys().next().value;
        if (oldest !== undefined) {
          store.delete(oldest);
        }
      }
    },
    get hits() {
      return hits;
    },
    get misses() {
      return misses;
    },
  };
}
