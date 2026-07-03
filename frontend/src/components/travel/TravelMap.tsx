"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import type { RegionNode } from "@/types";
import { cn } from "@/components/ui";

interface TravelMapProps {
  nodes: RegionNode[];
  /** The party's current location name (from the live store). */
  currentRegion: string | null;
  /** True while a journey is being narrated — pins are locked until arrival. */
  traveling: boolean;
  /** Fired when a route-connected pin is chosen (opens the travel confirm). */
  onPickDestination: (node: RegionNode) => void;
}

const norm = (s: string) => s.trim().toLowerCase();

/** How long the party token glides along a route when a journey resolves. */
const JOURNEY_MS = 700;

/** Honour both the OS setting and the in-app "reduce motion" toggle (globals.css uses both). */
function prefersReducedMotion(): boolean {
  if (typeof window === "undefined") return false;
  const optedOut = document.documentElement.dataset.reduceMotion === "true";
  const os = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
  return optedOut || os;
}

/** A single undirected route between two nodes. */
interface Edge {
  a: RegionNode;
  b: RegionNode;
  /** True when this route leads out of the party's current location. */
  fromCurrent: boolean;
}

/** An in-flight beacon journey between two pins (user-space coords). */
interface Journey {
  id: number;
  from: { x: number; y: number };
  to: { x: number; y: number };
}

/**
 * The campaign travel map: a node-graph of locations drawn as an SVG (0–100 user space), routes as
 * edges, and the party's position as a glowing beacon. Route-connected locations are interactive
 * (click or keyboard) and open the travel confirm; everything else is dimmed context. Purely
 * presentational — position, clock, and the confirm dialog are owned by {@link TravelPanel}.
 */
export default function TravelMap({
  nodes,
  currentRegion,
  traveling,
  onPickDestination,
}: TravelMapProps) {
  const [focusName, setFocusName] = useState<string | null>(null);

  const byName = useMemo(() => {
    const m = new Map<string, RegionNode>();
    nodes.forEach((n) => m.set(norm(n.name), n));
    return m;
  }, [nodes]);

  const current = currentRegion ? byName.get(norm(currentRegion)) ?? null : null;

  // Animate the party token gliding along the route when the location changes, instead of the
  // beacon teleporting. The previous pin is remembered so a move tweens from where the party
  // actually was; the first placement (no prior pin) and reduced-motion users simply snap.
  const prevPointRef = useRef<{ name: string; x: number; y: number } | null>(null);
  const journeyIdRef = useRef(0);
  const [journey, setJourney] = useState<Journey | null>(null);

  useEffect(() => {
    if (!current) return;
    const next = { name: current.name, x: current.x, y: current.y };
    const prev = prevPointRef.current;
    prevPointRef.current = next;
    if (!prev || norm(prev.name) === norm(next.name) || prefersReducedMotion()) return;
    const id = ++journeyIdRef.current;
    setJourney({ id, from: { x: prev.x, y: prev.y }, to: { x: next.x, y: next.y } });
    const t = setTimeout(
      () => setJourney((j) => (j && j.id === id ? null : j)),
      JOURNEY_MS
    );
    return () => clearTimeout(t);
  }, [current]);

  const reachable = useMemo(() => {
    const set = new Set<string>();
    if (current) current.connections.forEach((c) => set.add(norm(c)));
    return set;
  }, [current]);

  // Deduplicate undirected edges (A↔B once), flagging routes out of the current location.
  const edges = useMemo(() => {
    const seen = new Set<string>();
    const out: Edge[] = [];
    for (const n of nodes) {
      for (const conn of n.connections) {
        const other = byName.get(norm(conn));
        if (!other) continue;
        const key = [norm(n.name), norm(conn)].sort().join("|");
        if (seen.has(key)) continue;
        seen.add(key);
        const fromCurrent =
          !!current &&
          (norm(n.name) === norm(current.name) || norm(other.name) === norm(current.name));
        out.push({ a: n, b: other, fromCurrent });
      }
    }
    return out;
  }, [nodes, byName, current]);

  function statusOf(n: RegionNode): "current" | "reachable" | "other" {
    if (current && norm(n.name) === norm(current.name)) return "current";
    // With no current location yet (unplaced party), any location is a valid first placement.
    if (!current || reachable.has(norm(n.name))) return "reachable";
    return "other";
  }

  return (
    <svg
      viewBox="0 0 100 100"
      preserveAspectRatio="xMidYMid meet"
      className="h-full w-full select-none"
      role="group"
      aria-label="Campaign travel map"
    >
      <defs>
        <radialGradient id="tm-vignette" cx="50%" cy="42%" r="75%">
          <stop offset="0%" stopColor="#171312" />
          <stop offset="100%" stopColor="#0b0a0a" />
        </radialGradient>
        <pattern id="tm-grid" width="10" height="10" patternUnits="userSpaceOnUse">
          <path
            d="M10 0H0V10"
            fill="none"
            stroke="#c9a227"
            strokeOpacity="0.05"
            strokeWidth="0.25"
          />
        </pattern>
      </defs>

      {/* Surface: dark parchment vignette + faint gold survey grid. */}
      <rect x="0" y="0" width="100" height="100" fill="url(#tm-vignette)" />
      <rect x="0" y="0" width="100" height="100" fill="url(#tm-grid)" />

      {/* Routes */}
      <g strokeLinecap="round">
        {edges.map((e, i) => (
          <line
            key={i}
            x1={e.a.x}
            y1={e.a.y}
            x2={e.b.x}
            y2={e.b.y}
            stroke={e.fromCurrent ? "#c9a227" : "#2c2522"}
            strokeOpacity={e.fromCurrent ? 0.55 : 0.7}
            strokeWidth={e.fromCurrent ? 0.7 : 0.5}
            strokeDasharray={e.fromCurrent ? undefined : "1.6 1.6"}
          />
        ))}
      </g>

      {/* Pins */}
      {nodes.map((n) => {
        const status = statusOf(n);
        const isCurrent = status === "current";
        const isReachable = status === "reachable";
        const interactive = isReachable && !traveling;

        return (
          <g
            key={n.name}
            transform={`translate(${n.x} ${n.y})`}
            className={cn(
              "transition-opacity",
              interactive && "cursor-pointer",
              status === "other" && "opacity-45"
            )}
            role={interactive ? "button" : undefined}
            tabIndex={interactive ? 0 : undefined}
            aria-label={
              interactive
                ? `Travel to ${n.name}${n.type ? `, ${n.type}` : ""}`
                : undefined
            }
            onClick={interactive ? () => onPickDestination(n) : undefined}
            onKeyDown={
              interactive
                ? (ev) => {
                    if (ev.key === "Enter" || ev.key === " ") {
                      ev.preventDefault();
                      onPickDestination(n);
                    }
                  }
                : undefined
            }
            onMouseEnter={() => setFocusName(n.name)}
            onMouseLeave={() => setFocusName((f) => (f === n.name ? null : f))}
            onFocus={() => setFocusName(n.name)}
            onBlur={() => setFocusName((f) => (f === n.name ? null : f))}
          >
            {/* Current-location beacon */}
            {isCurrent && (
              <circle
                r="3.4"
                fill="none"
                stroke="#dc2626"
                strokeWidth="0.8"
                className="animate-location-pulse"
              />
            )}

            {/* Focus / hover highlight ring (keyboard-visible) */}
            {focusName === n.name && (
              <circle
                r="3.6"
                fill="none"
                stroke="#c9a227"
                strokeWidth="0.6"
                strokeDasharray="1.4 1.2"
              />
            )}

            {/* Pin body */}
            <circle
              r={isCurrent ? 2.4 : 2}
              fill={isCurrent ? "#dc2626" : isReachable ? "#221c1a" : "#171312"}
              stroke={isCurrent ? "#f87171" : isReachable ? "#c9a227" : "#2c2522"}
              strokeWidth={isCurrent ? 0.8 : 0.6}
              className={isCurrent ? "drop-shadow-[0_0_4px_rgba(220,38,38,0.6)]" : undefined}
            />
            {/* Type dot in the center for a touch of iconography */}
            <circle r="0.6" fill={isCurrent ? "#fff" : "#a39a93"} />

            {/* Label */}
            <text
              x="0"
              y="5.4"
              textAnchor="middle"
              className="font-display"
              style={{ fontSize: "3px" }}
              fill={isCurrent ? "#f5f1ed" : isReachable ? "#f5f1ed" : "#a39a93"}
              fontWeight={isCurrent ? 700 : 500}
            >
              {n.name}
            </text>
          </g>
        );
      })}

      {/* Party-in-transit overlay: a glowing token glides along the route from the previous pin to
          the new one (ease-out), leaving a brief dashed trail. Keyed so each journey restarts the
          SMIL motion; SMIL is exempt from the CSS reduced-motion reset, so motion is gated in JS. */}
      {journey && (
        <g key={journey.id} aria-hidden="true">
          <line
            x1={journey.from.x}
            y1={journey.from.y}
            x2={journey.to.x}
            y2={journey.to.y}
            stroke="#dc2626"
            strokeOpacity="0.35"
            strokeWidth="0.6"
            strokeLinecap="round"
            strokeDasharray="1.2 1.2"
          />
          <circle
            r="1.6"
            fill="#dc2626"
            stroke="#f87171"
            strokeWidth="0.5"
            className="drop-shadow-[0_0_4px_rgba(220,38,38,0.7)]"
          >
            <animateMotion
              dur={`${JOURNEY_MS}ms`}
              fill="freeze"
              calcMode="spline"
              keyPoints="0;1"
              keyTimes="0;1"
              keySplines="0.22 1 0.36 1"
              path={`M ${journey.from.x} ${journey.from.y} L ${journey.to.x} ${journey.to.y}`}
            />
          </circle>
        </g>
      )}
    </svg>
  );
}
