import { Link, Outlet } from 'react-router-dom';

/**
 * App shell for the foundation phase. Provides top navigation between the
 * placeholder routes; auth-aware navigation arrives with Phase 5.2.
 */
export default function RootLayout() {
  return (
    <div className="flex min-h-screen flex-col bg-gray-50">
      <header className="border-b border-gray-200 bg-white">
        <div className="mx-auto flex w-full max-w-5xl items-center justify-between px-6 py-4">
          <Link to="/" className="text-lg font-bold text-gray-900">
            MiniDrive
          </Link>
          <nav className="flex items-center gap-6 text-sm font-medium text-gray-600">
            <Link to="/" className="transition-colors hover:text-gray-900">
              Home
            </Link>
            <Link to="/login" className="transition-colors hover:text-gray-900">
              Login
            </Link>
            <Link to="/register" className="transition-colors hover:text-gray-900">
              Register
            </Link>
            <Link to="/drive" className="transition-colors hover:text-gray-900">
              My Drive
            </Link>
          </nav>
        </div>
      </header>

      <main className="flex flex-1 items-center justify-center px-6 py-12">
        <Outlet />
      </main>

      <footer className="border-t border-gray-200 bg-white py-4">
        <p className="text-center text-xs text-gray-400">
          MiniDrive &copy; 2026 &mdash; foundation build
        </p>
      </footer>
    </div>
  );
}
