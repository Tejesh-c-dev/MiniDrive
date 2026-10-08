import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { registerAndLogin } from '@/services/authStore';
import { toApiError } from '@/api/errors';
import type { ApiError } from '@/types/api';
import type { FieldErrors } from '@/types/auth';

export default function RegisterPage() {
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    // Client-side validation matching the backend DTO constraints.
    const errors: FieldErrors = {};
    if (name.trim().length < 2 || name.trim().length > 100) {
      errors.name = 'Name must be between 2 and 100 characters.';
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
      errors.email = 'Please enter a valid email address.';
    }
    if (password.length < 8 || password.length > 100) {
      errors.password = 'Password must be between 8 and 100 characters.';
    }
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      setError(null);
      return;
    }
    setFieldErrors({});
    setError(null);
    setSubmitting(true);

    try {
      // The backend returns a JWT on register (auto-login), so the normal
      // authenticated flow continues without an extra login step.
      await registerAndLogin({ name, email, password });
      navigate('/drive', { replace: true });
    } catch (err: unknown) {
      const apiError = toApiError(err);
      setError(apiError);
      if (apiError.fieldErrors) {
        setFieldErrors(apiError.fieldErrors);
      }
    } finally {
      setSubmitting(false);
    }
  };

  const fieldClassName =
    'w-full rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-900 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500';

  const fieldError = (field: string) =>
    fieldErrors[field] ? (
      <p className="mt-1 text-xs text-red-600">{fieldErrors[field]}</p>
    ) : null;

  return (
    <section className="w-full max-w-sm">
      <h1 className="mb-2 text-2xl font-bold text-gray-900">Create account</h1>
      <p className="mb-6 text-sm text-gray-600">
        Sign up for MiniDrive to get started.
      </p>

      <form onSubmit={handleSubmit} className="space-y-4" noValidate>
        <div>
          <label
            htmlFor="register-name"
            className="mb-1 block text-sm font-medium text-gray-700"
          >
            Name
          </label>
          <input
            id="register-name"
            type="text"
            autoComplete="name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            className={fieldClassName}
            placeholder="Jane Doe"
          />
          {fieldError('name')}
        </div>

        <div>
          <label
            htmlFor="register-email"
            className="mb-1 block text-sm font-medium text-gray-700"
          >
            Email
          </label>
          <input
            id="register-email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            className={fieldClassName}
            placeholder="you@example.com"
          />
          {fieldError('email')}
        </div>

        <div>
          <label
            htmlFor="register-password"
            className="mb-1 block text-sm font-medium text-gray-700"
          >
            Password
          </label>
          <input
            id="register-password"
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className={fieldClassName}
            placeholder="At least 8 characters"
          />
          {fieldError('password')}
        </div>

        {error && !error.fieldErrors && (
          <p
            className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
            role="alert"
          >
            {error.message}
          </p>
        )}

        <button
          type="submit"
          disabled={submitting}
          className="w-full rounded-md bg-blue-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {submitting ? 'Creating account…' : 'Create account'}
        </button>
      </form>

      <p className="mt-6 text-center text-sm text-gray-600">
        Already have an account?{' '}
        <Link to="/login" className="font-medium text-blue-600 hover:underline">
          Log in
        </Link>
      </p>
    </section>
  );
}
