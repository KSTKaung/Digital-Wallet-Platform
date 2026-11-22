import { Link } from 'react-router';

export function NotFoundPage() {
  return (
    <section aria-labelledby="not-found-heading" className="space-y-3">
      <h1 id="not-found-heading" className="text-xl font-semibold">
        Page not found
      </h1>
      <p className="text-slate-700 dark:text-slate-300">This page does not exist.</p>
      <Link
        to="/"
        className="inline-block rounded text-blue-700 underline underline-offset-4 hover:text-blue-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600 dark:text-blue-400 dark:hover:text-blue-300"
      >
        Go to the home page
      </Link>
    </section>
  );
}
