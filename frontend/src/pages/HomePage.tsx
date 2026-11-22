import { useAuth } from '../auth/authContext';
import { isStaff } from '../auth/roles';
import { HealthStatus } from '../components/HealthStatus';
import { StaffArea } from './admin/AdminHome';
import { WalletDashboard } from './wallet/WalletDashboard';

export function HomePage() {
  const { status, user, hasRole } = useAuth();
  const signedIn = status === 'signedIn';
  const isCustomer = signedIn && hasRole('CUSTOMER');
  const staff = signedIn && user !== null && isStaff(user.roles);

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">Home</h1>
      {isCustomer && <WalletDashboard />}
      {staff && <StaffArea />}
      <HealthStatus />
    </div>
  );
}
