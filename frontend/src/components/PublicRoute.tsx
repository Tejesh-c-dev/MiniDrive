import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '@/services/authStore';

/**
 * Route guard for /login and /register: authenticated users are redirected
 * to /drive instead of seeing the auth pages again.
 */
export default function PublicRoute() {
  const { authenticated } = useAuth();

  if (authenticated) {
    return <Navigate to="/drive" replace />;
  }

  return <Outlet />;
}
