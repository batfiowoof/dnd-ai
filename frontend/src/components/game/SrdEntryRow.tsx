"use client";

import { useState } from "react";
import { cn, Spinner } from "@/components/ui";
import { useSrdSpell, useSrdEquipment } from "@/hooks/useSrdInfo";

interface SrdEntryRowProps {
  name: string;
  kind: "spell" | "equipment";
  /** Trailing badge/label (quantity, slot level, "equipped"…). */
  meta?: React.ReactNode;
  /** Accent the title (e.g. healing potions). */
  accent?: boolean;
}

/**
 * A name row in the character sheet that expands to reveal its SRD description.
 * Click-to-expand is the accessible disclosure path (not hover); the lookup is
 * lazy (only when opened) and degrades to a quiet "No description" line for
 * names the SRD doesn't carry.
 */
export default function SrdEntryRow({
  name,
  kind,
  meta,
  accent,
}: SrdEntryRowProps) {
  const [open, setOpen] = useState(false);

  // Both hooks always run (rules of hooks); only the matching one is enabled.
  const spell = useSrdSpell(name, kind === "spell" && open);
  const equip = useSrdEquipment(name, kind === "equipment" && open);
  const query = kind === "spell" ? spell : equip;
  const info = query.data;

  return (
    <div className="rounded-md border border-border bg-bg-elevated">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        className="flex w-full items-center gap-2 px-2.5 py-1.5 text-left text-xs transition hover:bg-accent-glow focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
      >
        <Chevron
          className={cn(
            "h-3 w-3 flex-shrink-0 text-text-muted transition-transform duration-200",
            open && "rotate-90"
          )}
        />
        <span
          className={cn(
            "min-w-0 flex-1 truncate font-medium",
            accent ? "text-accent-light" : "text-text"
          )}
        >
          {name}
        </span>
        {meta && <span className="flex-shrink-0 text-text-muted">{meta}</span>}
      </button>

      {open && (
        <div className="border-t border-border px-3 py-2">
          {query.isLoading ? (
            <span className="flex items-center gap-2 text-xs text-text-muted">
              <Spinner className="h-3 w-3" /> Consulting the tomes…
            </span>
          ) : info ? (
            <div className="space-y-1.5">
              {info.subtitle && (
                <p className="text-[10px] uppercase tracking-wider text-gold">
                  {info.subtitle}
                </p>
              )}
              {info.facts.length > 0 && (
                <ul className="space-y-0.5">
                  {info.facts.map((f) => (
                    <li key={f} className="text-xs text-text">
                      {f}
                    </li>
                  ))}
                </ul>
              )}
              {info.paragraphs.slice(0, 3).map((p, i) => (
                <p
                  key={i}
                  className="text-xs leading-relaxed text-text-muted"
                >
                  {p}
                </p>
              ))}
              {info.facts.length === 0 && info.paragraphs.length === 0 && (
                <p className="text-xs italic text-text-muted">
                  No further details.
                </p>
              )}
            </div>
          ) : (
            <p className="text-xs italic text-text-muted">
              No description available.
            </p>
          )}
        </div>
      )}
    </div>
  );
}

function Chevron({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className={className}
    >
      <path d="m9 18 6-6-6-6" />
    </svg>
  );
}
