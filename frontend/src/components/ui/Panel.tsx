import { cn } from "./cn";

interface PanelProps extends React.HTMLAttributes<HTMLDivElement> {
  glow?: boolean;
  corners?: boolean;
  /** Cursor-following border glow on hover. On by default; opt out for static panels. */
  interactive?: boolean;
}

/** Framed surface card used across login / landing / lobby. */
export default function Panel({
  glow = false,
  corners = false,
  interactive = true,
  className,
  children,
  ...props
}: PanelProps) {
  return (
    <div
      data-spotlight={interactive ? "" : undefined}
      className={cn(
        "rounded-xl border border-border-accent bg-surface/90 backdrop-blur-sm",
        glow && "glow",
        corners && "panel-corners",
        interactive && "spotlight",
        className
      )}
      {...props}
    >
      {children}
    </div>
  );
}
