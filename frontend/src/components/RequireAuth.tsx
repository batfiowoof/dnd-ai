"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import { Spinner } from "@/components/ui";

export default function RequireAuth({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, isLoading } = useAuth();
  const router = useRouter();

  // Once auth has resolved and the user isn't signed in, send them to the
  // branded /login page (Sign In / Create Account) instead of showing a
  // bare fallback here.
  useEffect(() => {
    if (!isLoading && !isAuthenticated) {
      router.replace("/login");
    }
  }, [isLoading, isAuthenticated, router]);

  if (isLoading || !isAuthenticated) {
    return (
      <main className="flex min-h-dvh items-center justify-center">
        <span className="inline-flex items-center gap-3 text-text-muted">
          <Spinner className="text-accent" />
          Loading...
        </span>
      </main>
    );
  }

  return <>{children}</>;
}
