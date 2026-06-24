import { cn } from "./cn";

/** Inline d20 (twenty-sided die) mark — vector, themeable, not an emoji. */
export function D20Mark({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      strokeLinejoin="round"
      strokeLinecap="round"
      aria-hidden="true"
      className={className}
    >
      <path d="M12 2 21 7.5v9L12 22 3 16.5v-9L12 2Z" />
      <path d="M12 2 7 9h10L12 2Z" />
      <path d="M7 9 3 7.5M17 9l4-1.5M7 9l-1 6m11-6 1 6M7 9h10M6 15l6 7m6-7-6 7m-6-7h12M12 9v6m-6 0 6-7 6 7" />
    </svg>
  );
}

interface BrandProps {
  size?: "sm" | "md" | "lg";
  showMark?: boolean;
  className?: string;
}

const titleSizes = {
  sm: "text-lg",
  md: "text-2xl",
  lg: "text-4xl",
};

const markSizes = {
  sm: "h-5 w-5",
  md: "h-7 w-7",
  lg: "h-10 w-10",
};

/** "D&D AI" wordmark in the display serif with the d20 mark. */
export default function Brand({
  size = "md",
  showMark = true,
  className,
}: BrandProps) {
  return (
    <span className={cn("inline-flex items-center gap-2.5", className)}>
      {showMark && (
        <D20Mark className={cn("text-accent text-glow", markSizes[size])} />
      )}
      <span
        className={cn(
          "font-display font-bold tracking-wider text-accent",
          titleSizes[size]
        )}
        style={{ fontFamily: "var(--font-display)" }}
      >
        D&D AI
      </span>
    </span>
  );
}
