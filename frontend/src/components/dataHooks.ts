import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';

/**
 * The 1-based page number of a list from `?page=` (not sensitive, so it may live in the URL); 1
 * when it is missing or not a positive whole number. Page 1 has no parameter.
 */
export function usePageParam(): [number, (page: number) => void] {
  const [params, setParams] = useSearchParams();
  const raw = Number(params.get('page'));
  const page = Number.isSafeInteger(raw) && raw >= 1 ? raw : 1;
  const setPage = useCallback(
    (next: number) => setParams(next <= 1 ? {} : { page: String(next) }),
    [setParams],
  );
  return [page, setPage];
}

/**
 * Calls `refetch` when the window regains focus (documentations/08 §4D D7: a refresh button and a
 * refetch on focus, no background polling). The latest callback is used without re-subscribing.
 */
export function useRefetchOnFocus(refetch: () => void, enabled = true) {
  const latest = useRef(refetch);

  useEffect(() => {
    latest.current = refetch;
  }, [refetch]);

  useEffect(() => {
    if (!enabled) {
      return;
    }
    const onFocus = () => latest.current();
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [enabled]);
}

/** A read's state. `error` is the raw error, so a page can tell a `404` from anything else. */
export type Load<T> =
  { kind: 'loading' } | { kind: 'error'; error: unknown } | { kind: 'loaded'; data: T };

/**
 * Loads `fetcher` once, again on `reload` (a refresh button) and when the window regains focus
 * (D7; no polling). A failed refresh keeps the last data and reports the error in `refreshError`.
 * Pass a stable fetcher (useCallback); a page that shows another resource remounts with a key.
 */
export function useLoad<T>(fetcher: (signal: AbortSignal) => Promise<T>) {
  const [load, setLoad] = useState<Load<T>>({ kind: 'loading' });
  const [version, setVersion] = useState(0);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshError, setRefreshError] = useState<{ error: unknown } | null>(null);
  // Whether data is on screen, read by the async callbacks (state updaters stay pure).
  const hasData = useRef(false);
  // The load in flight, so setData can cancel it (its answer may predate the data being set).
  const inFlight = useRef<AbortController | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    inFlight.current = controller;
    fetcher(controller.signal)
      .then((data) => {
        if (controller.signal.aborted) {
          return;
        }
        hasData.current = true;
        setLoad({ kind: 'loaded', data });
        setRefreshError(null);
        setRefreshing(false);
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return;
        }
        setRefreshing(false);
        if (hasData.current) {
          setRefreshError({ error });
        } else {
          setLoad({ kind: 'error', error });
        }
      });
    return () => controller.abort();
  }, [fetcher, version]);

  const reload = useCallback(() => {
    setRefreshing(true);
    setVersion((n) => n + 1);
  }, []);

  /** Back to the loading state, then load again (the "Try again" of a failed first load). */
  const retry = useCallback(() => {
    hasData.current = false;
    setLoad({ kind: 'loading' });
    reload();
  }, [reload]);

  /**
   * Shows data the page already has (e.g. the cancel answer) until the next load replaces it. A
   * load still in flight (focus refetch, Refresh) is aborted first: it may have been answered
   * before the change, and must not put the older state back on screen.
   */
  const setData = useCallback((data: T) => {
    inFlight.current?.abort();
    setRefreshing(false);
    hasData.current = true;
    setLoad({ kind: 'loaded', data });
    setRefreshError(null);
  }, []);

  useRefetchOnFocus(reload, load.kind === 'loaded');

  return { load, refreshing, refreshError, reload, retry, setData };
}
