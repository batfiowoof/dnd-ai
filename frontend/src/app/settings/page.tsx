"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import RequireAuth from "@/components/RequireAuth";
import { loadPrefs, savePrefs, type Prefs } from "@/lib/prefs";
import { playSound } from "@/lib/sound";
import { ACCOUNT_URL } from "@/lib/config";
import { Panel, Button, Brand, Divider, cn } from "@/components/ui";

export default function SettingsPage() {
  return (
    <RequireAuth>
      <SettingsContent />
    </RequireAuth>
  );
}

function SettingsContent() {
  const router = useRouter();
  const { username, logout } = useAuth();

  const [prefs, setPrefs] = useState<Prefs | null>(null);

  // Load device prefs after mount (localStorage is client-only).
  useEffect(() => {
    setPrefs(loadPrefs());
  }, []);

  function update(patch: Partial<Prefs>) {
    setPrefs((prev) => {
      if (!prev) return prev;
      const next = { ...prev, ...patch };
      savePrefs(next);
      return next;
    });
  }

  return (
    <main className="flex min-h-dvh flex-col items-center p-4 py-10">
      <div className="w-full max-w-lg">
        <button
          onClick={() => router.push("/")}
          className="mb-4 inline-flex cursor-pointer items-center gap-1.5 text-xs text-text-muted transition hover:text-accent"
        >
          <span aria-hidden>←</span> Menu
        </button>
      </div>

      <Panel glow corners className="w-full max-w-lg p-8 animate-rise">
        <div className="mb-1 flex justify-center">
          <Brand size="md" />
        </div>
        <p className="mb-6 text-center text-xs uppercase tracking-[0.25em] text-gold">
          Settings
        </p>

        {/* Display & audio */}
        <section>
          <h2 className="mb-3 font-display text-sm font-bold uppercase tracking-wider text-text-muted">
            Display &amp; Audio
          </h2>
          {prefs ? (
            <div className="space-y-3">
              <Toggle
                label="Reduce motion"
                hint="Minimise animations and transitions"
                checked={prefs.reduceMotion}
                onChange={(v) => update({ reduceMotion: v })}
              />
              <Toggle
                label="Sound effects"
                hint="Dice rolls and interface sounds"
                checked={prefs.sound}
                onChange={(v) => update({ sound: v })}
              />
              <div className="flex items-center justify-between rounded-lg border border-border bg-bg-elevated px-3 py-2.5">
                <div>
                  <p className="text-sm text-text">Text size</p>
                  <p className="text-[11px] text-text-muted">
                    Scale the interface text
                  </p>
                </div>
                <div className="flex gap-1">
                  {[
                    [0.9, "S"],
                    [1, "M"],
                    [1.1, "L"],
                    [1.25, "XL"],
                  ].map(([scale, label]) => (
                    <button
                      key={label}
                      onClick={() => update({ textScale: scale as number })}
                      className={cn(
                        "h-8 w-8 cursor-pointer rounded-md border text-xs font-semibold transition",
                        prefs.textScale === scale
                          ? "border-accent bg-accent/15 text-accent"
                          : "border-border text-text-muted hover:border-accent/50"
                      )}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>
            </div>
          ) : (
            <div className="h-24 animate-pulse rounded-lg bg-bg-elevated" />
          )}
        </section>

        <Divider className="my-6" />

        {/* Account */}
        <section>
          <h2 className="mb-3 font-display text-sm font-bold uppercase tracking-wider text-text-muted">
            Account
          </h2>
          <div className="rounded-lg border border-border bg-bg-elevated px-3 py-2.5">
            <p className="text-[11px] uppercase tracking-wider text-text-muted">
              Signed in as
            </p>
            <p className="text-sm text-text">{username ?? "—"}</p>
          </div>
          <a
            href={ACCOUNT_URL}
            target="_blank"
            rel="noopener noreferrer"
            className="mt-3 block"
          >
            <Button variant="outline" fullWidth>
              Manage account
            </Button>
          </a>
          <p className="mt-1.5 text-center text-[11px] text-text-muted">
            Email &amp; password are managed in your account console.
          </p>
        </section>

        <Divider className="my-6" />

        <Button variant="ghost" fullWidth onClick={logout}>
          Log out
        </Button>
      </Panel>
    </main>
  );
}

function Toggle({
  label,
  hint,
  checked,
  onChange,
}: {
  label: string;
  hint: string;
  checked: boolean;
  onChange: (value: boolean) => void;
}) {
  return (
    <div className="flex items-center justify-between rounded-lg border border-border bg-bg-elevated px-3 py-2.5">
      <div>
        <p className="text-sm text-text">{label}</p>
        <p className="text-[11px] text-text-muted">{hint}</p>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={label}
        onClick={() => {
          // Sound on the toggle press itself — so enabling it gives instant feedback.
          playSound("toggle");
          onChange(!checked);
        }}
        className={cn(
          "relative h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border transition",
          "focus:outline-none focus-visible:ring-2 focus-visible:ring-accent",
          checked ? "border-accent bg-accent/30" : "border-border bg-surface-light"
        )}
      >
        <span
          className={cn(
            "absolute top-0.5 h-4.5 w-4.5 rounded-full transition-all",
            checked
              ? "left-[22px] bg-accent shadow-[0_0_10px_var(--color-accent-glow)]"
              : "left-0.5 bg-text-muted"
          )}
          style={{ height: "1.125rem", width: "1.125rem" }}
        />
      </button>
    </div>
  );
}
