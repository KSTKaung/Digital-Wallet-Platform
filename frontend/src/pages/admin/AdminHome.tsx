import { Link, Navigate } from 'react-router';
import { useAuth } from '../../auth/authContext';
import { LINK_CLASSES } from '../../components/SubmitButton';
import { NO_STAFF_TOOLS_MESSAGE, staffItemsFor } from './staffNav';

/**
 * `/admin`: goes straight to the first staff screen the role may use; a role with none yet gets a
 * plain message instead of links to screens that do not exist.
 */
export function AdminHome() {
  const { hasRole } = useAuth();
  const [first] = staffItemsFor(hasRole);
  if (first) {
    return <Navigate to={first.to} replace />;
  }
  return (
    <section aria-labelledby="admin-home-heading" className="space-y-2">
      <h1 id="admin-home-heading" className="text-xl font-semibold">
        Staff area
      </h1>
      <p>{NO_STAFF_TOOLS_MESSAGE}</p>
    </section>
  );
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
      {items.length === 0 ? (
        <p>{NO_STAFF_TOOLS_MESSAGE}</p>
      ) : (
        <ul className="list-disc space-y-1 pl-5">
          {items.map((item) => (
            <li key={item.to}>
              <Link to={item.to} className={LINK_CLASSES}>
                {item.label}
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
