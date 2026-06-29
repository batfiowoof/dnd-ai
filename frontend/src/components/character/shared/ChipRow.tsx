import { cn } from "@/components/ui";

export default function ChipRow({
  items,
  accent,
}: {
  items: string[];
  accent?: boolean;
}) {
  return (
    <div className="flex flex-wrap gap-1">
      {items.filter(Boolean).map((it) => (
        <span
          key={it}
          className={cn(
            "rounded px-2 py-0.5 text-xs",
            accent
              ? "bg-accent-dark/30 text-accent-light"
              : "bg-surface-light text-text-muted"
          )}
        >
          {it}
        </span>
      ))}
    </div>
  );
}
