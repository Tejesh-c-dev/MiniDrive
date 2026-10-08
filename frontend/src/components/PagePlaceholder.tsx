interface PagePlaceholderProps {
  /** Page title shown in the card heading. */
  title: string;
  /** Short description of what will live on this page. */
  message: string;
}

/**
 * Simple, consistent placeholder used across the four foundation routes.
 * Replaced by real page implementations in later phases.
 */
export default function PagePlaceholder({ title, message }: PagePlaceholderProps) {
  return (
    <div className="mx-auto w-full max-w-xl rounded-xl border border-gray-200 bg-white p-8 shadow-sm">
      <h1 className="text-2xl font-semibold text-gray-900">{title}</h1>
      <p className="mt-2 text-sm leading-relaxed text-gray-600">{message}</p>
      <p className="mt-6 text-xs font-medium uppercase tracking-wide text-gray-400">
        Phase 5.2 placeholder
      </p>
    </div>
  );
}
