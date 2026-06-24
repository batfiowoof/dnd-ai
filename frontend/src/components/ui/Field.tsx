import { cn } from "./cn";

/** Shared control styling for inputs / selects / textareas. */
export const controlClass =
  "w-full rounded-lg border border-border bg-bg-elevated px-4 py-2.5 text-sm text-text placeholder-text-muted outline-none transition focus:border-accent focus:ring-1 focus:ring-accent disabled:opacity-40";

interface FieldProps {
  label?: string;
  htmlFor?: string;
  hint?: string;
  error?: string;
  required?: boolean;
  className?: string;
  children: React.ReactNode;
}

/** Form field: visible label on top, helper/error below the control. */
export default function Field({
  label,
  htmlFor,
  hint,
  error,
  required,
  className,
  children,
}: FieldProps) {
  return (
    <div className={cn("space-y-1.5", className)}>
      {label && (
        <label
          htmlFor={htmlFor}
          className="block text-xs font-semibold uppercase tracking-wider text-text-muted"
        >
          {label}
          {required && <span className="ml-1 text-accent">*</span>}
        </label>
      )}
      {children}
      {error ? (
        <p className="text-xs text-accent" role="alert">
          {error}
        </p>
      ) : hint ? (
        <p className="text-xs text-text-muted">{hint}</p>
      ) : null}
    </div>
  );
}
