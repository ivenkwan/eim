/**
 * Typed transport (AD-5): wire data is validated at the boundary before it
 * reaches application state — the UI never trusts unvalidated payloads.
 * Validators are spec-derived schemas from @eim/schemas (or any
 * `(value: unknown) => T` function), keeping this package decoupled.
 */
export type Validator<T> = (value: unknown) => T;

export interface FetchOptions {
  readonly signal?: AbortSignal;
  readonly headers?: Readonly<Record<string, string>>;
}

export async function fetchJson<T>(
  url: string,
  validate: Validator<T>,
  options: FetchOptions = {},
): Promise<T> {
  const response = await fetch(url, {
    ...(options.signal !== undefined ? { signal: options.signal } : {}),
    headers: { accept: 'application/json', ...options.headers },
  });
  if (!response.ok) {
    throw new Error(`HTTP ${String(response.status)} for ${url}`);
  }
  const body: unknown = await response.json();
  return validate(body);
}

export { type coreApi, type connectors, type calcEngine, type process, type planCompiler } from './generated/index';
