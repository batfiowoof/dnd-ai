import { cn } from "./cn";
import { hpColor, hpRatio } from "@/lib/health";

interface HpBarProps {
  current: number;
  max: number;
  /** Bar thickness — "sm" for compact rosters, "md" for the status panel. */
  size?: "sm" | "md";
  className?: string;
}

const HEIGHTS: Record<NonNullable<HpBarProps["size"]>, string> = {
  sm: "h-1.5",
  md: "h-2",
};

/**
 * Exact-HP bar for players/allies. Colour follows the shared 50%/25% threshold ladder in
 * `lib/health.ts`. Enemies hide their real HP, so they use band rings/bars (see `bandMeta`) — not
 * this component.
 */
export default function HpBar({ current, max, size = "md", className }: HpBarProps) {
  const ratio = hpRatio(current, max);
  return (
    <div
      className={cn(
        "w-full overflow-hidden rounded-full bg-surface-light",
        HEIGHTS[size],
        className
      )}
    >
      <div
        className={cn("h-full rounded-full transition-all", hpColor(ratio))}
        style={{ width: `${ratio * 100}%` }}
      />
    </div>
  );
}
