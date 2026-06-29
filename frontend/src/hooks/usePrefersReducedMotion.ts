import { useEffect, useState } from "react";

/** Reduced-motion: honour the OS setting AND the in-app preference flag. */
export function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(false);
  useEffect(() => {
    const mq = window.matchMedia?.("(prefers-reduced-motion: reduce)");
    const read = () =>
      setReduced(
        !!mq?.matches ||
          document.documentElement.dataset.reduceMotion === "true"
      );
    read();
    mq?.addEventListener?.("change", read);
    return () => mq?.removeEventListener?.("change", read);
  }, []);
  return reduced;
}
