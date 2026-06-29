"use client";

import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { applyPrefs, loadPrefs } from "@/lib/prefs";
import { ToastProvider } from "@/components/ui";
import ErrorBoundary from "@/components/ErrorBoundary";

export default function Providers({ children }: { children: ReactNode }) {
  // Reflect saved display prefs (reduce motion, text scale) on first paint.
  useEffect(() => {
    applyPrefs(loadPrefs());
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
        <ToastProvider>{children}</ToastProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  );
}
