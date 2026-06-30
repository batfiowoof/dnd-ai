"use client";

import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import RequireAuth from "@/components/RequireAuth";
import { Panel, Button, Brand, Divider, D20Mark, cn } from "@/components/ui";

export default function MenuPage() {
  return (
    <RequireAuth>
      <MenuContent />
    </RequireAuth>
  );
}

interface MenuItem {
  label: string;
  description: string;
  icon: React.ReactNode;
  onClick: () => void;
  primary?: boolean;
}

function MenuContent() {
  const router = useRouter();
  const { username, logout } = useAuth();

  const items: MenuItem[] = [
    {
      label: "Play",
      description: "Start or rejoin an adventure",
      icon: <IconSword />,
      onClick: () => router.push("/play"),
      primary: true,
    },
    {
      label: "Create a Character",
      description: "Forge a new hero",
      icon: <IconQuill />,
      onClick: () => router.push("/characters/new"),
    },
    {
      label: "My Characters",
      description: "Manage your roster",
      icon: <IconScroll />,
      onClick: () => router.push("/characters"),
    },
    {
      label: "World Builder",
      description: "Craft a campaign world with AI",
      icon: <IconGlobe />,
      onClick: () => router.push("/worlds"),
    },
    {
      label: "Settings",
      description: "Display & audio preferences",
      icon: <IconGear />,
      onClick: () => router.push("/settings"),
    },
  ];

  return (
    <main className="flex min-h-dvh items-center justify-center p-4">
      <Panel glow corners className="w-full max-w-md p-8 animate-rise">
        {/* Hero */}
        <div className="mb-6 flex flex-col items-center text-center">
          <D20Mark className="mb-3 h-14 w-14 animate-float text-accent text-glow" />
          <Brand size="lg" showMark={false} />
          <p className="mt-2 text-xs uppercase tracking-[0.25em] text-gold">
            AI Dungeon Master
          </p>
          {username && (
            <p className="mt-3 text-xs text-text-muted">
              Welcome back, <span className="text-text">{username}</span>
            </p>
          )}
        </div>

        <Divider mark />

        {/* Menu items */}
        <nav className="mt-6 flex flex-col gap-2.5">
          {items.map((item) => (
            <MenuButton key={item.label} {...item} />
          ))}
        </nav>

        <Divider className="my-6" />

        {/* Log out — visually separated */}
        <Button variant="ghost" fullWidth onClick={logout}>
          <span className="inline-flex items-center justify-center gap-2">
            <IconLogout /> Log out
          </span>
        </Button>
      </Panel>
    </main>
  );
}

function MenuButton({ label, description, icon, onClick, primary }: MenuItem) {
  return (
    <button
      type="button"
      onClick={onClick}
      data-spotlight=""
      className={cn(
        "spotlight group flex w-full cursor-pointer items-center gap-3 rounded-lg border px-4 py-3 text-left transition duration-200",
        "focus:outline-none focus-visible:ring-2 focus-visible:ring-accent",
        primary
          ? "border-accent bg-accent/10 hover:bg-accent/20 hover:shadow-[0_0_20px_var(--color-accent-glow)]"
          : "border-border bg-bg-elevated hover:border-accent/50 hover:-translate-y-0.5"
      )}
    >
      <span
        className={cn(
          "flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-md transition",
          primary
            ? "bg-accent/20 text-accent"
            : "bg-surface-light text-text-muted group-hover:text-accent"
        )}
      >
        {icon}
      </span>
      <span className="min-w-0">
        <span
          className={cn(
            "block font-display text-base font-semibold",
            primary ? "text-accent" : "text-text"
          )}
        >
          {label}
        </span>
        <span className="block text-xs text-text-muted">{description}</span>
      </span>
    </button>
  );
}

/* ── Inline stroke icons (no emoji, single icon family) ───────────── */

const iconProps = {
  className: "h-5 w-5",
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.7,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
};

function IconSword() {
  return (
    <svg {...iconProps}>
      <path d="M14.5 17.5 3 6V3h3l11.5 11.5" />
      <path d="m13 19 6-6" />
      <path d="m16 16 4 4" />
      <path d="m19 21 2-2" />
    </svg>
  );
}

function IconQuill() {
  return (
    <svg {...iconProps}>
      <path d="M4 20s2-8 9-15c2 3 1 6-1 8s-5 3-8 7Z" />
      <path d="M9 13c2 0 3-1 4-2" />
    </svg>
  );
}

function IconScroll() {
  return (
    <svg {...iconProps}>
      <path d="M5 7a2 2 0 0 1 2-2h10v12a2 2 0 0 1-2 2H6" />
      <path d="M17 5a2 2 0 0 1 2 2v1h-2" />
      <path d="M8 9h6M8 13h6" />
    </svg>
  );
}

function IconGlobe() {
  return (
    <svg {...iconProps}>
      <circle cx="12" cy="12" r="9" />
      <path d="M3 12h18" />
      <path d="M12 3a14 14 0 0 1 0 18a14 14 0 0 1 0-18Z" />
    </svg>
  );
}

function IconGear() {
  return (
    <svg {...iconProps}>
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z" />
    </svg>
  );
}

function IconLogout() {
  return (
    <svg {...iconProps} className="h-4 w-4">
      <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
      <path d="m16 17 5-5-5-5" />
      <path d="M21 12H9" />
    </svg>
  );
}
