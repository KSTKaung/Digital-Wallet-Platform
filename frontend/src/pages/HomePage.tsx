import { HealthStatus } from '../components/HealthStatus';

export function HomePage() {
  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">Home</h1>
      <HealthStatus />
    </div>
  );
}
