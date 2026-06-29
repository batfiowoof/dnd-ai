import { cn } from "@/components/ui";

/** A capped multi-select chip list (class skills). Mirrors SpellPicker's cap UX. */
export default function ChoicePicker({
  title,
  picked,
  max,
  options,
  selected,
  onToggle,
}: {
  title: string;
  picked: number;
  max: number;
  options: string[];
  selected: string[];
  onToggle: (v: string) => void;
}) {
  const atCap = picked >= max;
  return (
    <div>
      <div className="mb-2 flex items-baseline justify-between">
        <span className="text-sm font-semibold text-text">{title}</span>
        <span
          className={cn(
            "text-xs font-semibold tabular",
            atCap ? "text-success" : "text-text-muted"
          )}
        >
          {picked}/{max} chosen
        </span>
      </div>
      <div className="flex flex-wrap gap-2">
        {options.map((opt) => {
          const isSel = selected.includes(opt);
          const disabled = !isSel && atCap;
          return (
            <button
              key={opt}
              type="button"
              aria-pressed={isSel}
              disabled={disabled}
              onClick={() => onToggle(opt)}
              className={cn(
                "min-h-[44px] rounded-lg border px-3 py-2 text-sm transition focus:outline-none focus-visible:ring-2 focus-visible:ring-accent",
                isSel
                  ? "border-accent bg-accent-glow text-text"
                  : disabled
                    ? "cursor-not-allowed border-border bg-bg-elevated text-text-muted opacity-40"
                    : "border-border bg-bg-elevated text-text-muted hover:border-accent/50"
              )}
            >
              {opt}
            </button>
          );
        })}
      </div>
    </div>
  );
}
