import { Field, controlClass, cn } from "@/components/ui";

interface BaseProps {
  label?: string;
  hint?: string;
  required?: boolean;
  placeholder?: string;
  className?: string;
}

interface TextProps extends BaseProps {
  value: string;
  onChange: (v: string) => void;
}

/** Single-line labeled text input wired to the shared control styling. */
export function LabeledInput({
  label,
  hint,
  required,
  placeholder,
  className,
  value,
  onChange,
}: TextProps) {
  return (
    <Field label={label} hint={hint} required={required} className={className}>
      <input
        type="text"
        value={value}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
        className={controlClass}
      />
    </Field>
  );
}

/** Multi-line labeled textarea. */
export function LabeledTextarea({
  label,
  hint,
  required,
  placeholder,
  className,
  value,
  onChange,
  rows = 3,
}: TextProps & { rows?: number }) {
  return (
    <Field label={label} hint={hint} required={required} className={className}>
      <textarea
        value={value}
        placeholder={placeholder}
        rows={rows}
        onChange={(e) => onChange(e.target.value)}
        className={cn(controlClass, "resize-none")}
      />
    </Field>
  );
}

interface SelectProps extends BaseProps {
  value: string;
  onChange: (v: string) => void;
  options: readonly string[];
}

/** Labeled native select wired to the shared control styling. */
export function LabeledSelect({
  label,
  hint,
  required,
  className,
  value,
  onChange,
  options,
}: SelectProps) {
  return (
    <Field label={label} hint={hint} required={required} className={className}>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className={controlClass}
      >
        {options.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    </Field>
  );
}

interface NumberProps extends BaseProps {
  value: number | null;
  onChange: (v: number | null) => void;
  min?: number;
  max?: number;
  step?: number;
}

/** Labeled numeric input; emits null when cleared so optional stats can be left blank. */
export function LabeledNumber({
  label,
  hint,
  required,
  placeholder,
  className,
  value,
  onChange,
  min,
  max,
  step,
}: NumberProps) {
  return (
    <Field label={label} hint={hint} required={required} className={className}>
      <input
        type="number"
        value={value ?? ""}
        placeholder={placeholder}
        min={min}
        max={max}
        step={step}
        onChange={(e) =>
          onChange(e.target.value === "" ? null : Number(e.target.value))
        }
        className={controlClass}
      />
    </Field>
  );
}
