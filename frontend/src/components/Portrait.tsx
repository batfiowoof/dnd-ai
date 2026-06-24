"use client";

import { useEffect, useState } from "react";
import { cn } from "@/components/ui";

type PortraitSize = "xs" | "sm" | "md" | "lg" | "xl";

interface PortraitProps {
  /** External image URL; falls back to initials when null/empty or it fails to load. */
  src?: string | null;
  /** Name used for initials fallback and the accessible label. */
  name?: string | null;
  size?: PortraitSize;
  /** Gold ring emphasis (e.g. the active-turn player). */
  active?: boolean;
  className?: string;
}

const sizeClasses: Record<PortraitSize, string> = {
  xs: "h-7 w-7 text-[0.65rem]",
  sm: "h-9 w-9 text-xs",
  md: "h-12 w-12 text-sm",
  lg: "h-20 w-20 text-lg",
  xl: "h-28 w-28 text-2xl",
};

/** Two-letter initials from a name (e.g. "Thorin Oakenshield" → "TO"). */
function initialsOf(name?: string | null): string {
  if (!name) return "?";
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/**
 * Character portrait avatar. Renders the image when available, otherwise a themed
 * initials medallion. Reserves a fixed square (no layout shift) and gracefully
 * recovers from broken image URLs.
 */
export default function Portrait({
  src,
  name,
  size = "md",
  active = false,
  className,
}: PortraitProps) {
  const [failed, setFailed] = useState(false);

  // Reset the error state if the URL changes (e.g. live portrait update).
  useEffect(() => setFailed(false), [src]);

  const showImage = !!src && !failed;
  const label = name ? `${name}'s portrait` : "Player portrait";

  return (
    <div
      className={cn(
        "relative grid shrink-0 place-items-center overflow-hidden rounded-full",
        "border bg-surface-light font-display font-semibold uppercase tracking-wide text-accent-light",
        "select-none",
        active
          ? "border-gold ring-2 ring-gold/60 shadow-[0_0_12px_var(--color-gold-muted)]"
          : "border-border-accent",
        sizeClasses[size],
        className,
      )}
      role="img"
      aria-label={label}
    >
      {showImage ? (
        // eslint-disable-next-line @next/next/no-img-element -- arbitrary user-supplied URLs, not a configured next/image domain
        <img
          src={src!}
          alt={label}
          className="h-full w-full object-cover"
          loading="lazy"
          onError={() => setFailed(true)}
        />
      ) : (
        <span aria-hidden="true">{initialsOf(name)}</span>
      )}
    </div>
  );
}
