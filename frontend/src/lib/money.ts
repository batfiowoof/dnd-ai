/**
 * D&D 5e coinage display. Wealth is carried as copper (1 gp = 100 cp, 1 sp = 10 cp); this renders it
 * back as "12 gp 4 sp 2 cp", dropping zero denominations. Mirrors the backend MoneyUtil.format.
 */
export function formatCoins(copper: number): string {
  const c = Math.max(0, Math.floor(copper || 0));
  const gp = Math.floor(c / 100);
  const sp = Math.floor((c % 100) / 10);
  const cp = c % 10;
  const parts: string[] = [];
  if (gp) parts.push(`${gp} gp`);
  if (sp) parts.push(`${sp} sp`);
  if (cp) parts.push(`${cp} cp`);
  return parts.length ? parts.join(" ") : "0 cp";
}

/**
 * Parse an SRD cost string ("2 GP", "5 SP", "1 CP") into copper for prefilling stock prices. Returns
 * 0 when nothing parses (e.g. SRD armor entries have no cost — the author fills it in). Mirrors the
 * backend MoneyUtil.parseCoins for the units the SRD catalog actually uses.
 */
export function parseCostToCopper(cost: string | undefined | null): number {
  if (!cost) return 0;
  const m = /([\d,]+)\s*(pp|gp|ep|sp|cp)/i.exec(cost);
  if (!m) return 0;
  const amount = Number(m[1].replace(/,/g, ""));
  if (!Number.isFinite(amount)) return 0;
  const unit = m[2].toLowerCase();
  const mult = unit === "pp" ? 1000 : unit === "gp" ? 100 : unit === "ep" ? 50 : unit === "sp" ? 10 : 1;
  return Math.round(amount * mult);
}
