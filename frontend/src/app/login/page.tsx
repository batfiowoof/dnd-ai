"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import { Button, Panel, Brand, Divider, Spinner, D20Mark } from "@/components/ui";

export default function LoginPage() {
  const router = useRouter();
  const { login, register, isAuthenticated, isLoading } = useAuth();

  useEffect(() => {
    if (isAuthenticated) {
      router.replace("/");
    }
  }, [isAuthenticated, router]);

  if (isLoading) {
    return (
      <main className="flex min-h-dvh items-center justify-center p-4">
        <span className="inline-flex items-center gap-3 text-text-muted">
          <Spinner className="text-accent" />
          Loading...
        </span>
      </main>
    );
  }

  if (isAuthenticated) {
    return null;
  }

  return (
    <main className="flex min-h-dvh items-center justify-center p-4">
      <Panel glow corners className="w-full max-w-md p-8 animate-rise">
        <div className="mb-4 flex flex-col items-center">
          {/* Hero d20 medallion */}
          <div className="relative mb-4 grid h-20 w-20 place-items-center rounded-full border border-border-accent bg-accent-glow animate-float">
            <span
              aria-hidden="true"
              className="pointer-events-none absolute inset-0 rounded-full shadow-[0_0_36px_var(--color-accent-glow)]"
            />
            <D20Mark className="h-11 w-11 text-accent text-glow" />
          </div>
          <Brand size="lg" showMark={false} />
        </div>
        <p className="text-center text-sm uppercase tracking-[0.25em] text-gold">
          AI Dungeon Master
        </p>

        <Divider mark />

        <p className="mb-6 text-center text-sm leading-relaxed text-text-muted">
          Gather your party, choose your world, and let an AI weave the tale.
        </p>

        <div className="space-y-3">
          <Button onClick={() => login()} size="lg" fullWidth>
            Sign In
          </Button>
          <Button
            onClick={() => register()}
            variant="outline"
            size="lg"
            fullWidth
          >
            Create Account
          </Button>
        </div>

        <p className="mt-6 text-center text-xs text-text-muted">
          Your characters and sessions are saved to your account.
        </p>
      </Panel>
    </main>
  );
}
