/* Inline icon (no icon-lib dependency in this codebase). */
export default function FeatIcon({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className={className}
    >
      <path d="M12 3l1.9 4.6L18.5 9.5 14 11.4 12 16l-2-4.6L5.5 9.5 10.1 7.6z" />
      <path d="M19 14l.7 1.7L21.5 16.5 20 17.2 19 19l-1-1.8L16.5 16.5 18.3 15.7z" />
    </svg>
  );
}
