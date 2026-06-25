import { cn } from "./cn";
import { D20Mark } from "./Brand";

/**
 * Loading indicator — a tumbling d20, reusing the same dice-roll animation
 * (`animate-dice-tumble`) the session dice use. Size and color come from
 * `className` via Tailwind + `currentColor` (e.g. `text-accent`, `h-3 w-3`),
 * so every call site keeps its existing sizing. Reduced-motion is handled
 * globally in `globals.css` (the die simply sits still).
 */
export default function Spinner({
  className,
  label,
}: {
  className?: string;
  label?: string;
}) {
  return (
    <span
      role="status"
      aria-label={label ?? "Loading"}
      className={cn("inline-block h-4 w-4 animate-dice-tumble", className)}
    >
      <D20Mark className="h-full w-full" />
    </span>
  );
}
