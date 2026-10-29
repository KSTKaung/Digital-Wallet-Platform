import { HealthStatus } from './components/HealthStatus';

export function App() {
  return (
    <div className="min-h-screen bg-slate-50 font-sans text-slate-900 dark:bg-slate-950 dark:text-slate-100">
      <header className="mx-auto max-w-5xl px-4 py-4">
        <h1 className="text-2xl font-semibold">Digital Wallet</h1>
        <p className="text-sm text-slate-600 dark:text-slate-400">
          Portfolio demo — simulated money only.
        </p>
      </header>
      <main className="mx-auto max-w-5xl px-4 pb-8">
        <HealthStatus />
      </main>
    </div>
  );
}
