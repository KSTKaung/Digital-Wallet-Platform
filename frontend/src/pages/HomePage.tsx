import { useAuth } from '../auth/authContext';
import { HealthStatus } from '../components/HealthStatus';
import { WalletDashboard } from './wallet/WalletDashboard';

export function HomePage() {
  const { status, hasRole } = useAuth();
  const isCustomer = status === 'signedIn' && hasRole('CUSTOMER');

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">Home</h1>
      {isCustomer && <WalletDashboard />}
      <HealthStatus />
    </div>
  );
}
