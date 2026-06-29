import { cn } from "@/components/ui";

/** A radio row for an equipment option (text-heavy, full-width target). */
export default function EquipRadio({
  name,
  label,
  value,
  checked,
  onChange,
  text,
}: {
  name: string;
  label: string;
  value: string;
  checked: boolean;
  onChange: () => void;
  text: string;
}) {
  return (
    <label
      className={cn(
        "flex cursor-pointer items-start gap-3 rounded-lg border p-3 transition",
        checked
          ? "border-accent bg-accent-glow"
          : "border-border bg-surface hover:border-accent/50"
      )}
    >
      <input
        type="radio"
        name={name}
        value={value}
        checked={checked}
        onChange={onChange}
        className="mt-0.5 accent-[var(--color-accent)]"
      />
      <span>
        <span className="block text-xs font-semibold uppercase tracking-wider text-text-muted">
          {label}
        </span>
        <span className="text-sm text-text">{text}</span>
      </span>
    </label>
  );
}
