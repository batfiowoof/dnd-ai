"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { createPortal } from "react-dom";
import { cn } from "./cn";

export type ToastVariant = "error" | "success" | "info";

interface ToastItem {
  id: number;
  variant: ToastVariant;
  message: string;
}

interface ToastApi {
  /** Show a toast with an explicit variant (defaults to "info"). */
  show: (message: string, variant?: ToastVariant) => void;
  error: (message: string) => void;
  success: (message: string) => void;
  info: (message: string) => void;
  dismiss: (id: number) => void;
}

const ToastContext = createContext<ToastApi | null>(null);

/** Auto-dismiss windows per variant (ms). Errors linger a touch longer to read. */
const DURATION: Record<ToastVariant, number> = {
  error: 6000,
  success: 4000,
  info: 4000,
};

/**
 * App-wide toast surface. Themed to the dark-red tabletop palette and reused everywhere instead of
 * per-page `useState("")` + manual `setTimeout` error banners. Toasts announce via `aria-live`
 * without stealing focus, carry an icon (never color alone), auto-dismiss, and can be dismissed
 * manually. Entrance uses the shared `animate-rise` (respects reduced-motion globally).
 */
export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const idRef = useRef(0);
  const timers = useRef<Map<number, ReturnType<typeof setTimeout>>>(new Map());

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((t) => t.id !== id));
    const timer = timers.current.get(id);
    if (timer) {
      clearTimeout(timer);
      timers.current.delete(id);
    }
  }, []);

  const show = useCallback(
    (message: string, variant: ToastVariant = "info") => {
      const id = ++idRef.current;
      setToasts((current) => [...current, { id, variant, message }]);
      timers.current.set(
        id,
        setTimeout(() => dismiss(id), DURATION[variant])
      );
    },
    [dismiss]
  );

  // Clear any pending timers on unmount.
  useEffect(() => {
    const pending = timers.current;
    return () => {
      pending.forEach(clearTimeout);
      pending.clear();
    };
  }, []);

  const api = useMemo<ToastApi>(
    () => ({
      show,
      error: (m) => show(m, "error"),
      success: (m) => show(m, "success"),
      info: (m) => show(m, "info"),
      dismiss,
    }),
    [show, dismiss]
  );

  return (
    <ToastContext.Provider value={api}>
      {children}
      <ToastViewport toasts={toasts} onDismiss={dismiss} />
    </ToastContext.Provider>
  );
}

/** Access the toast API. Must be called under a `<ToastProvider>`. */
export function useToast(): ToastApi {
  const ctx = useContext(ToastContext);
  if (!ctx) {
    throw new Error("useToast must be used within a <ToastProvider>");
  }
  return ctx;
}

const VARIANT_STYLES: Record<ToastVariant, string> = {
  error: "border-accent-dark/50 bg-accent-dark/25 text-accent-light",
  success: "border-success/40 bg-success/15 text-success",
  info: "border-gold/40 bg-gold-muted text-gold",
};

function ToastViewport({
  toasts,
  onDismiss,
}: {
  toasts: ToastItem[];
  onDismiss: (id: number) => void;
}) {
  if (typeof document === "undefined") return null;

  return createPortal(
    <div
      // Above modals (z-1000). Bottom on mobile, bottom-right on larger screens.
      className="pointer-events-none fixed inset-x-4 bottom-4 z-[1100] flex flex-col items-stretch gap-2 sm:inset-x-auto sm:right-4 sm:items-end"
      aria-live="polite"
      aria-relevant="additions"
    >
      {toasts.map((toast) => (
        <Toast key={toast.id} toast={toast} onDismiss={onDismiss} />
      ))}
    </div>,
    document.body
  );
}

function Toast({
  toast,
  onDismiss,
}: {
  toast: ToastItem;
  onDismiss: (id: number) => void;
}) {
  return (
    <div
      // Errors are assertive; success/info are polite status updates.
      role={toast.variant === "error" ? "alert" : "status"}
      className={cn(
        "animate-rise pointer-events-auto flex w-full items-start gap-2.5 rounded-lg border px-3.5 py-2.5 text-sm shadow-[0_8px_24px_rgba(0,0,0,0.4)] backdrop-blur-sm sm:w-auto sm:max-w-sm",
        VARIANT_STYLES[toast.variant]
      )}
    >
      <ToastIcon variant={toast.variant} />
      <span className="min-w-0 flex-1 break-words pt-px text-text">
        {toast.message}
      </span>
      <button
        type="button"
        onClick={() => onDismiss(toast.id)}
        aria-label="Dismiss notification"
        className="-mr-1 -mt-0.5 shrink-0 cursor-pointer rounded p-1 text-current opacity-70 transition hover:opacity-100"
      >
        <svg
          width="14"
          height="14"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2.5"
          strokeLinecap="round"
          aria-hidden="true"
        >
          <path d="M18 6 6 18M6 6l12 12" />
        </svg>
      </button>
    </div>
  );
}

function ToastIcon({ variant }: { variant: ToastVariant }) {
  const common = {
    width: 18,
    height: 18,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 2,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
    className: "mt-px shrink-0",
    "aria-hidden": true,
  };
  if (variant === "success") {
    return (
      <svg {...common}>
        <path d="M20 6 9 17l-5-5" />
      </svg>
    );
  }
  if (variant === "info") {
    return (
      <svg {...common}>
        <circle cx="12" cy="12" r="10" />
        <path d="M12 16v-4M12 8h.01" />
      </svg>
    );
  }
  // error
  return (
    <svg {...common}>
      <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0Z" />
      <path d="M12 9v4M12 17h.01" />
    </svg>
  );
}
