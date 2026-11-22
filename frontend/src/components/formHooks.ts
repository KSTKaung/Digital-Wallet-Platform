import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';

/**
 * Runs one async action at a time. The ref guard refuses a second call before React has
 * re-rendered the disabled button, so a fast double click or a double Enter sends one request.
 */
export function useSubmitGuard() {
  const inFlight = useRef(false);
  const [busy, setBusy] = useState(false);

  const run = useCallback(async (action: () => Promise<void>) => {
    if (inFlight.current) {
      return;
    }
    inFlight.current = true;
    setBusy(true);
    try {
      await action();
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }, []);

  return { busy, run };
}

/**
 * After a failed client-side check, moves focus to the first invalid field in the form. Call the
 * returned function after setting the field errors; focus moves in the commit that renders them
 * (a layout effect, so the field is never shown unfocused and tests see the focus with the DOM).
 */
export function useFocusFirstInvalid(formRef: RefObject<HTMLFormElement | null>) {
  const [request, setRequest] = useState(0);

  useLayoutEffect(() => {
    if (request > 0) {
      formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();
    }
  }, [request, formRef]);

  return useCallback(() => setRequest((n) => n + 1), []);
}

/**
 * Moves focus to an element (e.g. a result heading) whenever `key` changes to a truthy value, in
 * the same commit that renders it (a layout effect).
 */
export function useFocusOn(ref: RefObject<HTMLElement | null>, key: unknown) {
  useLayoutEffect(() => {
    if (key) {
      ref.current?.focus();
    }
  }, [key, ref]);
}

/**
 * Seconds left before a resend is offered again (client UX only; the server enforces the real
 * cooldown and answers 202 either way). `start(seconds)` restarts the countdown.
 */
export function useCountdown(initialSeconds: number) {
  const [seconds, setSeconds] = useState(initialSeconds);

  useEffect(() => {
    if (seconds <= 0) {
      return;
    }
    const timer = setTimeout(() => setSeconds((s) => Math.max(0, s - 1)), 1000);
    return () => clearTimeout(timer);
  }, [seconds]);

  const start = useCallback((value: number) => setSeconds(Math.max(0, Math.ceil(value))), []);
  return { seconds, start };
}
