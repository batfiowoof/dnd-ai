import { cn } from "./cn";

interface SegmentedControlProps<T extends string> {
  value: T;
  onChange: (v: T) => void;
  options: { value: T; label: string }[];
  className?: string;
}

/** Segmented single-choice control (a horizontal radiogroup of pill buttons). */
export default function SegmentedControl<T extends string>({
  value,
  onChange,
  options,
  className,
}: SegmentedControlProps<T>) {
  return (
    <div
      className={cn(
        "flex rounded-lg border border-border bg-bg-elevated p-1 text-xs",
        className
      )}
    >
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          onClick={() => onChange(o.value)}
          aria-pressed={value === o.value}
          className={cn(
            "flex-1 cursor-pointer rounded-md px-2 py-2 font-medium transition",
            value === o.value
              ? "bg-accent text-white shadow-[0_0_16px_var(--color-accent-glow)]"
              : "text-text-muted hover:text-text"
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}
