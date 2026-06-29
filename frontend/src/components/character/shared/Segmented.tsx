import { cn } from "@/components/ui";

/**
 * Single-select toggle (base ability method, ASI split). It's a radiogroup, not
 * ARIA tabs — there are no tabpanels/roving-tabindex, so radio semantics are the
 * correct fit and keep each option keyboard-reachable.
 */
export default function Segmented({
  options,
  value,
  onChange,
  className,
  ariaLabel,
}: {
  options: { value: string; label: string }[];
  value: string;
  onChange: (v: string) => void;
  className?: string;
  ariaLabel?: string;
}) {
  return (
    <div
      role="radiogroup"
      aria-label={ariaLabel}
      className={cn(
        "inline-flex rounded-lg border border-border bg-bg-elevated p-1",
        className
      )}
    >
      {options.map((o) => {
        const active = o.value === value;
        return (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => onChange(o.value)}
            className={cn(
              "min-h-[40px] rounded-md px-4 py-1.5 text-sm font-semibold transition",
              active ? "bg-accent text-white" : "text-text-muted hover:text-text"
            )}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}
