"use client";

import { useRef, useState, type ReactNode } from "react";
import { useSessionStore } from "@/store/sessionStore";
import { loadPrefs, savePrefs } from "@/lib/prefs";

const MIN_W = 320;
/** Don't let the battlefield eat more than this fraction of the viewport. */
const MAX_FRACTION = 0.7;

function clampWidth(px: number): number {
  const max =
    typeof window !== "undefined" ? window.innerWidth * MAX_FRACTION : 900;
  return Math.max(MIN_W, Math.min(px, max));
}

/**
 * Desktop split for the in-combat game body: the battlefield (left) and the chat (right)
 * with a draggable divider between them. The battlefield column gets an explicit, persisted
 * width (`prefs.mapWidth`); chat fills the rest. Only shown when there's a combat grid —
 * otherwise the chat takes the full width (and the columns stack on narrow screens, where the
 * `--mapw` width is ignored). The divider uses pointer capture so the drag is robust.
 */
export default function BattlefieldChatSplit({
  map,
  chat,
  alsoShowMap = false,
}: {
  map: ReactNode;
  chat: ReactNode;
  /** Show the left pane even out of combat (e.g. the travel map). */
  alsoShowMap?: boolean;
}) {
  const hasCombatGrid = useSessionStore(
    (s) => s.combat?.status === "ACTIVE" && !!s.combat?.grid
  );
  const hasMap = hasCombatGrid || alsoShowMap;
  const [width, setWidth] = useState(() => loadPrefs().mapWidth);
  const drag = useRef<{ startX: number; startW: number } | null>(null);

  function onPointerDown(e: React.PointerEvent) {
    drag.current = { startX: e.clientX, startW: width };
    (e.target as Element).setPointerCapture(e.pointerId);
  }
  function onPointerMove(e: React.PointerEvent) {
    if (!drag.current) return;
    setWidth(clampWidth(drag.current.startW + (e.clientX - drag.current.startX)));
  }
  function onPointerUp(e: React.PointerEvent) {
    if (!drag.current) return;
    drag.current = null;
    (e.target as Element).releasePointerCapture(e.pointerId);
    savePrefs({ ...loadPrefs(), mapWidth: width });
  }

  return (
    <div
      className="flex min-h-0 flex-1 flex-col lg:flex-row"
      style={{ ["--mapw" as string]: `${width}px` }}
    >
      {hasMap && (
        <>
          <div className="min-h-0 w-full lg:w-[var(--mapw)] lg:flex-none">
            {map}
          </div>
          {/* Drag handle (desktop only) */}
          <div
            role="separator"
            aria-orientation="vertical"
            aria-label="Resize battlefield"
            onPointerDown={onPointerDown}
            onPointerMove={onPointerMove}
            onPointerUp={onPointerUp}
            className="hidden w-1.5 flex-none cursor-col-resize bg-transparent transition-colors hover:bg-border-accent lg:block"
          />
        </>
      )}
      <div className="flex min-h-0 min-w-0 flex-1 flex-col">{chat}</div>
    </div>
  );
}
