import { cn } from "./cn";
import Spinner from "./Spinner";
import { playSound } from "@/lib/sound";

type Variant = "primary" | "outline" | "ghost" | "danger";
type Size = "sm" | "md" | "lg";

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
  fullWidth?: boolean;
  /** Suppress the click sound (e.g. where it would be noisy / repeated rapidly). */
  silent?: boolean;
}

const base =
  "inline-flex items-center justify-center gap-2 rounded-lg font-semibold tracking-wide transition duration-200 cursor-pointer active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2";

const variants: Record<Variant, string> = {
  primary:
    "bg-accent text-white hover:bg-accent-dark hover:shadow-[0_0_24px_var(--color-accent-glow)]",
  outline:
    "border border-accent text-accent hover:bg-accent hover:text-white",
  ghost:
    "border border-border text-text-muted hover:border-accent/50 hover:text-text",
  danger: "bg-accent-dark text-white hover:bg-accent",
};

const sizes: Record<Size, string> = {
  sm: "px-3 py-1.5 text-xs",
  md: "px-4 py-2.5 text-sm",
  lg: "px-6 py-3 text-sm",
};

export default function Button({
  variant = "primary",
  size = "md",
  loading = false,
  fullWidth = false,
  silent = false,
  className,
  children,
  disabled,
  onClick,
  ...props
}: ButtonProps) {
  return (
    <button
      className={cn(
        base,
        variants[variant],
        sizes[size],
        fullWidth && "w-full",
        className
      )}
      disabled={disabled || loading}
      onClick={(e) => {
        if (!silent) playSound("click");
        onClick?.(e);
      }}
      {...props}
    >
      {loading && <Spinner className="h-3.5 w-3.5" />}
      {children}
    </button>
  );
}
