import { cn } from "./cn";

interface PanelProps extends React.HTMLAttributes<HTMLDivElement> {
  glow?: boolean;
  corners?: boolean;
}

/** Framed surface card used across login / landing / lobby. */
export default function Panel({
  glow = false,
  corners = false,
  className,
  children,
  ...props
}: PanelProps) {
  return (
    <div
      className={cn(
        "rounded-xl border border-border-accent bg-surface/90 backdrop-blur-sm",
        glow && "glow",
        corners && "panel-corners",
        className
      )}
      {...props}
    >
      {children}
    </div>
  );
}
