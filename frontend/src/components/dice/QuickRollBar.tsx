"use client";

import { useState } from "react";
import { cn } from "@/components/ui";

interface QuickRollBarProps {
  onRoll: (notation: string, label: string) => void;
  disabled?: boolean;
}

const STANDARD = [20, 12, 10, 8, 6, 4];
const NOTATION_RE = /^\s*\d*\s*[dD]\s*\d+\s*([+-]\s*\d+)?\s*$/;

/**
 * Compact dice tray: one-tap standard dice plus a free-form notation field
 * ("2d6+3"). Resolution happens on the backend; this only emits the request.
 */
export default function QuickRollBar({ onRoll, disabled }: QuickRollBarProps) {
  const [custom, setCustom] = useState("");
  const customValid = NOTATION_RE.test(custom);

  function rollCustom() {
    const n = custom.trim();
    if (!customValid) return;
    onRoll(n, n);
    setCustom("");
  }

  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <span className="text-[10px] font-semibold uppercase tracking-wider text-text-muted">
        Roll
      </span>
      {STANDARD.map((s) => (
        <button
          key={s}
          type="button"
          disabled={disabled}
          onClick={() => onRoll(`1d${s}`, `d${s}`)}
          className={cn(
            "rounded-md border border-border bg-bg-elevated px-2 py-1 font-mono text-xs text-text-muted transition",
            "hover:border-accent/60 hover:text-accent disabled:cursor-not-allowed disabled:opacity-40"
          )}
        >
          d{s}
        </button>
      ))}
      <input
        type="text"
        value={custom}
        onChange={(e) => setCustom(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            rollCustom();
          }
        }}
        placeholder="1d20+5"
        disabled={disabled}
        className="w-24 rounded-md border border-border bg-bg-elevated px-2 py-1 font-mono text-xs text-text placeholder-text-muted outline-none transition focus:border-accent disabled:opacity-40"
      />
      <button
        type="button"
        disabled={disabled || !customValid}
        onClick={rollCustom}
        className="rounded-md border border-accent/60 px-2 py-1 text-xs font-semibold text-accent transition hover:bg-accent hover:text-white disabled:cursor-not-allowed disabled:opacity-40"
      >
        Roll
      </button>
    </div>
  );
}
