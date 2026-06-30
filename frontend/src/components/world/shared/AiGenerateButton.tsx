"use client";

import { Button } from "@/components/ui";

/** Small gold-accented "Generate with AI" affordance used on every wizard step. */
export default function AiGenerateButton({
  onClick,
  loading,
  label = "Generate with AI",
}: {
  onClick: () => void;
  loading: boolean;
  label?: string;
}) {
  return (
    <Button variant="ghost" size="sm" onClick={onClick} loading={loading}>
      <span className="inline-flex items-center gap-1.5 text-gold">
        <svg
          className="h-4 w-4"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth={1.7}
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden
        >
          <path d="M12 3v4M12 17v4M3 12h4M17 12h4M5.6 5.6l2.8 2.8M15.6 15.6l2.8 2.8M18.4 5.6l-2.8 2.8M8.4 15.6l-2.8 2.8" />
        </svg>
        {label}
      </span>
    </Button>
  );
}
