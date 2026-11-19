/**
 * Shown when a route fails to render. It deliberately shows no error details (no stack traces or
 * server data in the UI) and logs nothing; a full page load is the recovery path.
 */
export function RouteErrorPage() {
  return (
    <main className="mx-auto max-w-5xl space-y-3 px-4 py-6 font-sans text-slate-900 dark:text-slate-100">
      <h1 className="text-xl font-semibold">Something went wrong</h1>
      <p className="text-slate-700 dark:text-slate-300">
        This page could not be shown. Please try again.
      </p>
      <p className="text-sm text-slate-600 dark:text-slate-400">
        Portfolio demo — simulated money only.
      </p>
      <a
        href="/"
        className="inline-block rounded text-blue-700 underline underline-offset-4 hover:text-blue-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600 dark:text-blue-400 dark:hover:text-blue-300"
      >
        Reload the home page
      </a>
    </main>
  );
}
