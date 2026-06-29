"use client";

import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { applyPrefs, loadPrefs } from "@/lib/prefs";
import { unlockAudio } from "@/lib/sound";
import { ToastProvider } from "@/components/ui";
import ErrorBoundary from "@/components/ErrorBoundary";
import SpotlightProvider from "@/components/ui/SpotlightProvider";

export default function Providers({ children }: { children: ReactNode }) {
  // Reflect saved display prefs (reduce motion, text scale, sound) on first paint.
  useEffect(() => {
    applyPrefs(loadPrefs());
    // Browsers gate audio until a user gesture — resume the context on the first one.
    const onGesture = () => unlockAudio();
    window.addEventListener("pointerdown", onGesture, { once: true });
    window.addEventListener("keydown", onGesture, { once: true });
    return () => {
      window.removeEventListener("pointerdown", onGesture);
      window.removeEventListener("keydown", onGesture);
    };
  }, []);

  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            retry: 1,
            refetchOnWindowFocus: false,
          },
        },
      })
  );

  return (
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <SpotlightProvider />
        <ToastProvider>{children}</ToastProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  );
}
