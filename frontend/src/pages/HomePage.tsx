import { Link } from 'react-router-dom';
import { useAuth } from '@/services/authStore';

export default function HomePage() {
  const { authenticated } = useAuth();
  return <section className="w-full max-w-4xl py-16 text-center">
    <p className="mb-3 text-sm font-semibold uppercase tracking-widest text-blue-600">Your files, in one place</p>
    <h1 className="text-4xl font-bold tracking-tight text-gray-900 sm:text-5xl">A simpler home for your files.</h1>
    <p className="mx-auto mt-5 max-w-2xl text-lg text-gray-600">Store, organize, and share your files securely with MiniDrive. Pick up where you left off from any folder.</p>
    <div className="mt-8 flex justify-center gap-3">
      {authenticated ? <Link to="/drive" className="rounded-md bg-blue-600 px-5 py-3 font-semibold text-white hover:bg-blue-700">Open My Drive</Link> : <>
        <Link to="/login" className="rounded-md bg-blue-600 px-5 py-3 font-semibold text-white hover:bg-blue-700">Sign in</Link>
        <Link to="/register" className="rounded-md border border-gray-300 bg-white px-5 py-3 font-semibold text-gray-800 hover:bg-gray-50">Create account</Link>
      </>}
    </div>
  </section>;
}
