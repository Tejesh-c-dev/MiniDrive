import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '@/services/authStore';

/**
 * Route guard for authenticated-only pages (e.g. /drive). Unauthenticated
 * users are redirected to /login; the route itself enforces auth.
 */
export default function ProtectedRoute() {
  const { authenticated, ready } = useAuth();

  if (!ready) return null;

  if (!authenticated) {
    return <Navigate to="/login" replace />;
  }

  return <Outlet />;
}
