/** Shared SVG <defs> for the battle map: difficult-terrain hatch, board vignette, token shadow. */
export default function SvgDefs() {
  return (
    <defs>
      {/* Difficult terrain — teal hatch (distinct from the gold AoE template and red
          hazards), denser + tinted so it reads over a busy map background. */}
      <pattern
        id="bm-difficult"
        width="6"
        height="6"
        patternTransform="rotate(45)"
        patternUnits="userSpaceOnUse"
      >
        <rect width="6" height="6" fill="#2dd4bf" fillOpacity="0.22" />
        <line
          x1="0"
          y1="0"
          x2="0"
          y2="6"
          stroke="#5eead4"
          strokeWidth="2"
          strokeOpacity="0.8"
        />
      </pattern>

      {/* Subtle board vignette — darkens the edges for depth. */}
      <radialGradient id="bm-vignette" cx="50%" cy="50%" r="75%">
        <stop offset="55%" stopColor="#000000" stopOpacity={0} />
        <stop offset="100%" stopColor="#000000" stopOpacity={0.4} />
      </radialGradient>

      {/* Soft drop shadow lifting tokens off the board. */}
      <filter id="bm-token-shadow" x="-40%" y="-40%" width="180%" height="180%">
        <feDropShadow
          dx="0"
          dy="1.5"
          stdDeviation="1.6"
          floodColor="#000000"
          floodOpacity="0.55"
        />
      </filter>
    </defs>
  );
}
