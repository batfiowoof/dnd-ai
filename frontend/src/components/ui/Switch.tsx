import { cn } from "./cn";
import { playSound } from "@/lib/sound";

interface SwitchProps {
  label: string;
  hint?: string;
  checked: boolean;
  onChange: (v: boolean) => void;
  className?: string;
}

/** Labelled on/off switch (full-width tap target). */
export default function Switch({
  label,
  hint,
  checked,
  onChange,
  className,
}: SwitchProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={() => {
        playSound("toggle");
        onChange(!checked);
      }}
      className={cn(
        "flex w-full cursor-pointer items-center justify-between gap-3 rounded-lg border border-border bg-bg-elevated px-3 py-2.5 text-left transition hover:border-accent/50",
        className
      )}
    >
      <span>
        <span className="block text-sm font-medium text-text">{label}</span>
        {hint && <span className="block text-xs text-text-muted">{hint}</span>}
      </span>
      <span
        aria-hidden
        className={cn(
          "relative h-6 w-11 flex-shrink-0 rounded-full border transition",
          checked ? "border-accent bg-accent/80" : "border-border bg-bg"
        )}
      >
        <span
          className={cn(
            "absolute top-0.5 h-4 w-4 rounded-full bg-white transition-all",
            checked ? "left-[22px]" : "left-0.5"
          )}
        />
      </span>
    </button>
  );
}
