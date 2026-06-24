import { cn } from "./cn";

/** Ornamental section rule: gradient line with a center diamond. */
export default function Divider({ className }: { className?: string }) {
  return <hr className={cn("ornament my-6", className)} aria-hidden="true" />;
}
