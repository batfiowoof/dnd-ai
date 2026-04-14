"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";

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
      <main className="flex min-h-screen items-center justify-center p-4">
        <p className="text-text-muted">Loading...</p>
      </main>
    );
  }

  if (isAuthenticated) {
    return null;
  }

  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <div className="w-full max-w-md rounded-xl border border-border-accent bg-surface p-8 glow">
        <h1 className="mb-2 text-center text-4xl font-bold tracking-wider text-accent">
          D&D AI
        </h1>
        <p className="mb-8 text-center text-sm text-text-muted">
          AI Dungeon Master
        </p>

        <div className="space-y-3">
          <button
            onClick={() => login()}
            className="w-full rounded-lg bg-accent px-4 py-3 text-sm font-semibold text-white transition hover:bg-accent-dark"
          >
            Sign In
          </button>

          <button
            onClick={() => register()}
            className="w-full rounded-lg border border-accent px-4 py-3 text-sm font-semibold text-accent transition hover:bg-accent hover:text-white"
          >
            Create Account
          </button>
        </div>

        <p className="mt-6 text-center text-xs text-text-muted">
          Your characters and sessions will be saved to your account.
        </p>
      </div>
    </main>
  );
}
