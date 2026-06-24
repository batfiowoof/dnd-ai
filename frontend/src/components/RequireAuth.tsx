"use client";

import { useAuth } from "@/context/AuthContext";
import { Button, Spinner } from "@/components/ui";

export default function RequireAuth({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, isLoading, login } = useAuth();

  if (isLoading) {
    return (
      <main className="flex min-h-dvh items-center justify-center">
        <span className="inline-flex items-center gap-3 text-text-muted">
          <Spinner className="text-accent" />
          Loading...
        </span>
      </main>
    );
  }

  if (!isAuthenticated) {
    return (
      <main className="flex min-h-dvh items-center justify-center p-4">
        <div className="text-center">
          <p className="mb-4 text-text-muted">
            You need to sign in to continue.
          </p>
          <Button onClick={() => login()} size="lg">
            Sign In
          </Button>
        </div>
      </main>
    );
  }

  return <>{children}</>;
}
