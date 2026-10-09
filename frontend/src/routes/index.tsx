import { createBrowserRouter } from 'react-router-dom';
import RootLayout from '@/layouts/RootLayout';
import HomePage from '@/pages/HomePage';
import LoginPage from '@/pages/LoginPage';
import RegisterPage from '@/pages/RegisterPage';
import DrivePage from '@/pages/DrivePage';
import SharedFilePage from '@/pages/SharedFilePage';
import ProtectedRoute from '@/components/ProtectedRoute';
import PublicRoute from '@/components/PublicRoute';

/**
 * App routes. Auth-only routes (/drive) are wrapped in ProtectedRoute;
 * /login and /register are wrapped in PublicRoute so authenticated users
 * are redirected to /drive. /share/:token is public and deliberately sits
 * outside both guards so anyone with a link can open it without logging in.
 */
export const router = createBrowserRouter([
  {
    path: '/',
    element: <RootLayout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'share/:token', element: <SharedFilePage /> },
      {
        element: <PublicRoute />,
        children: [
          { path: 'login', element: <LoginPage /> },
          { path: 'register', element: <RegisterPage /> },
        ],
      },
      {
        element: <ProtectedRoute />,
        children: [
          { path: 'drive', element: <DrivePage /> },
          { path: 'drive/folders/:folderId', element: <DrivePage /> },
        ],
      },
    ],
  },
]);
