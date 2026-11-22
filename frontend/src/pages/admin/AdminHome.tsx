import { Link, Navigate } from 'react-router';
import { useAuth } from '../../auth/authContext';
import { LINK_CLASSES } from '../../components/SubmitButton';
import { staffItemsFor } from './staffNav';

/**
 * `/admin`: goes straight to the first staff screen the role may use. The route guard admits only
 * staff roles and each has a screen; anything else goes home rather than to an empty page.
 */
export function AdminHome() {
  const { hasRole } = useAuth();
  const [first] = staffItemsFor(hasRole);
  return <Navigate to={first?.to ?? '/'} replace />;
}

/** The staff screens the signed-in role may use, for the home page. */
export function StaffArea() {
  const { hasRole } = useAuth();
  const items = staffItemsFor(hasRole);
  return (
    <section aria-labelledby="staff-area-heading" className="space-y-2">
      <h2 id="staff-area-heading" className="text-lg font-medium">
        Staff area
      </h2>
      <ul className="list-disc space-y-1 pl-5">
        {items.map((item) => (
          <li key={item.to}>
            <Link to={item.to} className={LINK_CLASSES}>
              {item.label}
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
