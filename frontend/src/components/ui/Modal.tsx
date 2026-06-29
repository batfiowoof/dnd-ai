"use client";

import { useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { cn } from "./cn";

type ModalSize = "sm" | "md" | "lg";

interface ModalProps {
  open: boolean;
  onClose: () => void;
  title?: React.ReactNode;
  /** Hide the default close (×) button — e.g. for transient, self-dismissing modals. */
  hideClose?: boolean;
  /** Disable backdrop-click / ESC dismissal (e.g. while an animation is mid-flight). */
  dismissible?: boolean;
  size?: ModalSize;
  className?: string;
  children: React.ReactNode;
}

const sizeClasses: Record<ModalSize, string> = {
  sm: "max-w-sm",
  md: "max-w-lg",
  lg: "max-w-2xl",
};

/**
 * Themed modal dialog rendered in a portal. Scrim + blur isolate the foreground;
 * ESC and backdrop-click dismiss (unless `dismissible={false}`). Focus moves into
 * the dialog on open and entrance animation respects `prefers-reduced-motion`
 * (handled globally in globals.css). Reused by the dice, cast, use-item and
 * combat surfaces.
 */
export default function Modal({
  open,
  onClose,
  title,
  hideClose = false,
  dismissible = true,
  size = "md",
  className,
  children,
}: ModalProps) {
  const panelRef = useRef<HTMLDivElement>(null);

  // ESC to close + lock body scroll while open.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && dismissible) onClose();
    };
    document.addEventListener("keydown", onKey);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    // Move focus into the dialog for keyboard / screen-reader users.
    panelRef.current?.focus();
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = prevOverflow;
    };
  }, [open, dismissible, onClose]);

  if (!open || typeof document === "undefined") return null;

  return createPortal(
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-label={typeof title === "string" ? title : undefined}
    >
      {/* Scrim */}
      <div
        className="absolute inset-0 bg-black/60 backdrop-blur-sm"
        onClick={() => dismissible && onClose()}
        aria-hidden="true"
      />

      {/* Panel */}
      <div
        ref={panelRef}
        tabIndex={-1}
        className={cn(
          "animate-rise relative flex max-h-[calc(100dvh-2rem)] w-full flex-col rounded-xl border border-border-accent bg-surface/95 shadow-[0_0_48px_var(--color-accent-glow)] outline-none backdrop-blur-sm panel-corners",
          sizeClasses[size],
          className
        )}
      >
        {(title || !hideClose) && (
          <div className="flex items-center justify-between border-b border-border px-5 py-3">
            <h2 className="font-display text-base font-bold text-accent">
              {title}
            </h2>
            {!hideClose && (
              <button
                onClick={onClose}
                aria-label="Close dialog"
                className="cursor-pointer rounded p-1 text-text-muted transition hover:text-accent focus-visible:outline-2 focus-visible:outline-offset-2"
              >
                <svg
                  width="18"
                  height="18"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  aria-hidden="true"
                >
                  <path d="M18 6 6 18M6 6l12 12" />
                </svg>
              </button>
            )}
          </div>
        )}
        <div className="min-h-0 flex-1 overflow-y-auto p-5">{children}</div>
      </div>
    </div>,
    document.body
  );
}
