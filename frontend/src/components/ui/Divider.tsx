import { cn } from "./cn";
import { D20Mark } from "./Brand";

/**
 * Ornamental section rule.
 * - default: gradient line with a center diamond.
 * - `mark`: gradient lines flanking a centered d20 mark (heavier, more intentional).
 */
export default function Divider({
  className,
  mark = false,
}: {
  className?: string;
  mark?: boolean;
}) {
  if (mark) {
    return (
      <div
        role="separator"
        aria-hidden="true"
        className={cn("my-6 flex items-center gap-3", className)}
      >
        <span className="h-px flex-1 bg-[linear-gradient(to_right,transparent,var(--color-border-accent))]" />
        <D20Mark className="h-4 w-4 shrink-0 text-accent text-glow" />
        <span className="h-px flex-1 bg-[linear-gradient(to_left,transparent,var(--color-border-accent))]" />
      </div>
    );
  }

  return <hr className={cn("ornament my-6", className)} aria-hidden="true" />;
}
