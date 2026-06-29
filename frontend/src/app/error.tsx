"use client";

import { useEffect } from "react";
import { Button, Panel } from "@/components/ui";

/**
 * Route-segment error boundary (Next.js App Router). Renders when a page or layout throws during
 * render. Mirrors the themed fallback in `components/ErrorBoundary.tsx`; the raw error is logged,
 * never shown.
 */
export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("Route render error:", error);
  }, [error]);

  return (
    <div className="flex min-h-dvh items-center justify-center p-6">
      <Panel corners className="max-w-md p-8 text-center">
        <h1 className="font-display text-2xl font-bold text-accent text-glow">
          Something broke the spell
        </h1>
        <p className="mt-3 text-sm text-text-muted">
          An unexpected error interrupted this page. Your session is safe — try
          again to pick up where you left off.
        </p>
        <Button className="mt-6" onClick={reset}>
          Try again
        </Button>
      </Panel>
    </div>
  );
}
