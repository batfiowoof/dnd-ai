import { cn } from "@/components/ui";
import type { Spell } from "@/lib/spells";

/* Spell picker (cantrips / leveled). */
export default function SpellPicker({
  title,
  picked,
  max,
  choices,
  selectedNames,
  onToggle,
}: {
  title: string;
  picked: number;
  max: number;
  choices: Spell[];
  selectedNames: string[];
  onToggle: (name: string) => void;
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
      {choices.length === 0 ? (
        <p className="text-xs text-text-muted">No options available.</p>
      ) : (
        <div className="grid gap-2 sm:grid-cols-2">
          {choices.map((spell) => {
            const selected = selectedNames.includes(spell.name);
            const disabled = !selected && atCap;
            return (
              <button
                key={spell.name}
                type="button"
                aria-pressed={selected}
                disabled={disabled}
                onClick={() => onToggle(spell.name)}
                data-spotlight={disabled ? undefined : ""}
                className={cn(
                  "spotlight rounded-lg border p-3 text-left transition focus:outline-none focus-visible:ring-2 focus-visible:ring-accent",
                  selected
                    ? "border-accent bg-accent-glow"
                    : disabled
                      ? "cursor-not-allowed border-border bg-bg-elevated opacity-40"
                      : "border-border bg-bg-elevated hover:border-accent/50"
                )}
              >
                <div className="mb-0.5 flex items-center justify-between gap-2">
                  <span className="text-sm font-semibold text-text">
                    {spell.name}
                  </span>
                  {spell.school && (
                    <span className="shrink-0 text-[10px] uppercase tracking-wider text-gold">
                      {spell.school}
                    </span>
                  )}
                </div>
                {spell.desc && (
                  <p className="text-xs text-text-muted">{spell.desc}</p>
                )}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
