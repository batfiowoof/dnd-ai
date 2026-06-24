import { cn } from "./cn";

/** Inline error/notice banner (unifies the repeated red error pattern). */
export default function Alert({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <p
      role="alert"
      className={cn(
        "rounded-lg border border-accent-dark/40 bg-accent-dark/20 px-3 py-2 text-center text-sm text-accent-light",
        className
      )}
    >
      {children}
    </p>
  );
}
