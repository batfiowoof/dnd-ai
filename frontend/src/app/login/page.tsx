"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import { Button, Panel, Brand, Divider, Spinner } from "@/components/ui";

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
        <div className="mb-1 flex justify-center">
          <Brand size="lg" />
        </div>
        <p className="text-center text-sm uppercase tracking-[0.25em] text-gold">
          AI Dungeon Master
        </p>

        <Divider />

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
