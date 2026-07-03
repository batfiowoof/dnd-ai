"use client";

import { useEffect, useState } from "react";
import type { ShopView } from "@/types";
import { Modal, Button, cn } from "@/components/ui";
import { KIND_LABELS } from "@/lib/itemKinds";
import { SHOP_TYPE_LABELS } from "@/lib/shops";
import { formatCoins } from "@/lib/money";

interface ShopDialogProps {
  open: boolean;
  onClose: () => void;
  /** Shops open at the party's current location, priced for this player. */
  shops: ShopView[];
  /** The player's live purse in copper (from their runtime state). */
  purseCopper: number;
  connected: boolean;
  onBuy: (shopKey: string, itemRef: string, qty: number) => void;
  onSell: (shopKey: string, name: string, qty: number) => void;
}

type Tab = "buy" | "sell";

/**
 * In-session shop panel. Only meaningful when the party is at a shop's location (the parent shows the
 * "Shop" button only then). Lists the current shop's stock with economy-adjusted prices to buy, and the
 * player's sellable items with buy-back quotes. Buttons disable when disconnected or the purse is short.
 * Trades are emitted over WebSocket; the purse and stock refresh from the resulting broadcasts.
 */
export default function ShopDialog({
  open,
  onClose,
  shops,
  purseCopper,
  connected,
  onBuy,
  onSell,
}: ShopDialogProps) {
  const [shopKey, setShopKey] = useState<string>(shops[0]?.key ?? "");
  const [tab, setTab] = useState<Tab>("buy");

  // Keep the selection valid as availability changes (travel, restock).
  useEffect(() => {
    if (shops.length > 0 && !shops.some((s) => s.key === shopKey)) {
      setShopKey(shops[0].key);
    }
  }, [shops, shopKey]);

  const shop = shops.find((s) => s.key === shopKey) ?? shops[0];

  return (
    <Modal open={open} onClose={onClose} title="Shop" size="lg">
      {!shop ? (
        <p className="text-sm text-text-muted">There&rsquo;s nowhere to trade here.</p>
      ) : (
        <div className="space-y-4">
          {/* Purse */}
          <div className="flex items-center justify-between gap-3 rounded-lg border border-border bg-bg-elevated px-3 py-2">
            <span className="text-xs uppercase tracking-wider text-text-muted">Your purse</span>
            <span className="text-sm font-semibold tabular-nums text-gold">
              {formatCoins(purseCopper)}
            </span>
          </div>

          {/* Shop picker (only when more than one is open here) */}
          {shops.length > 1 && (
            <div className="flex flex-wrap gap-2">
              {shops.map((s) => (
                <button
                  key={s.key}
                  type="button"
                  onClick={() => setShopKey(s.key)}
                  className={cn(
                    "cursor-pointer rounded-md border px-2.5 py-1 text-xs font-semibold transition",
                    s.key === shop.key
                      ? "border-accent/60 bg-accent/10 text-accent"
                      : "border-border text-text-muted hover:border-accent/40 hover:text-text"
                  )}
                >
                  {s.name}
                </button>
              ))}
            </div>
          )}

          {/* Shop header */}
          <div>
            <div className="flex items-baseline justify-between gap-2">
              <span
                className="text-base font-semibold text-gold"
                style={{ fontFamily: "var(--font-display)" }}
              >
                {shop.name}
              </span>
              <span className="text-[11px] uppercase tracking-wider text-text-muted">
                {SHOP_TYPE_LABELS[shop.type]}
              </span>
            </div>
            {shop.description && (
              <p className="mt-1 text-xs text-text-muted">{shop.description}</p>
            )}
          </div>

          {/* Buy / Sell tabs */}
          <div
            role="tablist"
            aria-label="Buy or sell"
            className="flex gap-1 rounded-lg border border-border bg-bg-elevated p-1"
          >
            {(["buy", "sell"] as const).map((t) => (
              <button
                key={t}
                role="tab"
                aria-selected={tab === t}
                onClick={() => setTab(t)}
                className={cn(
                  "flex-1 cursor-pointer rounded-md px-3 py-1.5 text-sm font-semibold capitalize transition",
                  tab === t ? "bg-accent text-white" : "text-text-muted hover:text-text"
                )}
              >
                {t}
              </button>
            ))}
          </div>

          {tab === "buy" ? (
            <BuyList shop={shop} purseCopper={purseCopper} connected={connected} onBuy={onBuy} />
          ) : (
            <SellList shop={shop} connected={connected} onSell={onSell} />
          )}
        </div>
      )}
    </Modal>
  );
}

function BuyList({
  shop,
  purseCopper,
  connected,
  onBuy,
}: {
  shop: ShopView;
  purseCopper: number;
  connected: boolean;
  onBuy: (shopKey: string, itemRef: string, qty: number) => void;
}) {
  if (shop.stock.length === 0) {
    return <p className="text-sm text-text-muted">This shop has nothing for sale.</p>;
  }
  return (
    <ul className="space-y-1.5">
      {shop.stock.map((item) => {
        const soldOut = item.quantity === 0;
        const tooPoor = purseCopper < item.unitPriceCopper;
        const disabled = !connected || soldOut || tooPoor;
        return (
          <li
            key={`${item.srdIndex ?? item.name}-${item.kind}`}
            className="flex items-center gap-2 rounded-lg border border-border bg-bg-elevated px-3 py-2"
          >
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2">
                <span className="truncate text-sm text-text">{item.name}</span>
                {item.quantity >= 0 && (
                  <span className="text-xs tabular-nums text-text-muted">×{item.quantity}</span>
                )}
              </div>
              <span className="text-[10px] uppercase tracking-wider text-text-muted">
                {KIND_LABELS[item.kind]}
              </span>
            </div>
            <span
              className={cn(
                "w-24 flex-shrink-0 text-right text-sm tabular-nums",
                tooPoor ? "text-danger" : "text-gold"
              )}
            >
              {formatCoins(item.unitPriceCopper)}
            </span>
            <Button
              size="sm"
              disabled={disabled}
              onClick={() => onBuy(shop.key, item.srdIndex ?? item.name, 1)}
              title={soldOut ? "Out of stock" : tooPoor ? "Not enough coin" : undefined}
            >
              {soldOut ? "Sold out" : "Buy"}
            </Button>
          </li>
        );
      })}
    </ul>
  );
}

function SellList({
  shop,
  connected,
  onSell,
}: {
  shop: ShopView;
  connected: boolean;
  onSell: (shopKey: string, name: string, qty: number) => void;
}) {
  if (shop.sellOffers.length === 0) {
    return (
      <p className="text-sm text-text-muted">
        You have nothing this merchant will buy.
      </p>
    );
  }
  return (
    <ul className="space-y-1.5">
      {shop.sellOffers.map((offer) => (
        <li
          key={`${offer.name}-${offer.kind}`}
          className="flex items-center gap-2 rounded-lg border border-border bg-bg-elevated px-3 py-2"
        >
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <span className="truncate text-sm text-text">{offer.name}</span>
              {offer.held > 1 && (
                <span className="text-xs tabular-nums text-text-muted">×{offer.held}</span>
              )}
            </div>
            <span className="text-[10px] uppercase tracking-wider text-text-muted">
              {KIND_LABELS[offer.kind]}
            </span>
          </div>
          <span className="w-24 flex-shrink-0 text-right text-sm tabular-nums text-gold">
            {formatCoins(offer.unitPriceCopper)}
          </span>
          <Button
            size="sm"
            variant="outline"
            disabled={!connected}
            onClick={() => onSell(shop.key, offer.name, 1)}
          >
            Sell
          </Button>
        </li>
      ))}
    </ul>
  );
}
