import { cn } from "@/components/ui";

/** A selectable card used by the Class / Background / Species grids. */
export default function OptionCard({
  selected,
  onClick,
  title,
  badge,
  children,
}: {
  selected: boolean;
  onClick: () => void;
  title: string;
  badge?: string;
  children?: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onClick}
      className={cn(
        "min-h-[44px] rounded-lg border p-4 text-left transition focus:outline-none focus-visible:ring-2 focus-visible:ring-accent",
        selected
          ? "border-accent bg-accent-glow"
          : "border-border bg-bg-elevated hover:border-accent/50"
      )}
    >
      <div className="mb-1 flex items-center justify-between gap-2">
        <span className="font-semibold text-text">{title}</span>
        {badge && (
          <span className="shrink-0 text-xs text-accent tabular">{badge}</span>
        )}
      </div>
      {children}
    </button>
  );
}
