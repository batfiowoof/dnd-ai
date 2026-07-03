package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.AvailableShopsDto;
import com.dungeon.master.model.dto.InventoryItem;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.Shop;
import com.dungeon.master.model.dto.ShopStockEntry;
import com.dungeon.master.model.dto.ShopView;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.repository.GameSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buying and selling at location-gated shops. A shop is only usable while the party is standing at its
 * {@code region}/{@code subregion} (the same current-location state {@code TravelService} maintains),
 * so leaving town closes it. Prices come from each shop's {@code economyFactor}: buy =
 * {@code round(basePriceCopper × factor)}, sell (buy-back) = half of that — one factor per shop, so the
 * same goods genuinely cost more in a wealthy city and less in a poor one.
 *
 * <p>Coin math is exact ({@link MoneyUtil}, copper). All mutations reuse {@link PlayerStateService}
 * (spend/add coins, add/remove items) and run in one transaction so a purchase can't debit coin without
 * delivering goods. Invalid actions throw {@link IllegalStateException} with a player-friendly message,
 * which the WebSocket layer turns into a {@code WsError}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShopService {

    private final GameSessionRepository sessionRepository;
    private final PlayerStateService playerStateService;
    private final Dnd5eReferenceService referenceService;

    /** Lazily-built SRD list-price lookup (lower-cased item name → copper), for pricing player sales. */
    private volatile Map<String, Long> srdPriceByName;

    /** Outcome of a buy/sell, for broadcasting the new state and a system-log line. */
    public record ShopTxnResult(
            String shopName,
            String itemName,
            int qty,
            long unitPriceCopper,
            long copperDelta,
            PlayerRuntimeStateDto state
    ) {
    }

    /* ── reads ───────────────────────────────────────────────────── */

    /** The player's purse plus every shop open at the party's current location, priced for that player. */
    public AvailableShopsDto availableShops(UUID sessionId, UUID playerId) {
        GameSession session = sessionRepository.findById(sessionId).orElse(null);
        PlayerRuntimeStateDto state = safeState(playerId);
        long purse = state == null ? 0 : state.copper();
        if (session == null) {
            return new AvailableShopsDto(purse, List.of());
        }
        List<InventoryItem> inventory = state == null ? List.of() : state.inventory();
        List<ShopView> views = new ArrayList<>();
        for (Shop shop : openShops(session)) {
            views.add(toView(shop, inventory));
        }
        return new AvailableShopsDto(purse, views);
    }

    /* ── mutations ───────────────────────────────────────────────── */

    /** Buy {@code qty} of a stocked item. Debits the purse, grants the item, decrements limited stock. */
    @Transactional
    public ShopTxnResult buy(UUID sessionId, UUID playerId, String shopKey, String itemRef, int qty) {
        GameSession session = requireSession(sessionId);
        Shop shop = openShop(session, shopKey);
        int want = Math.max(1, qty);
        int idx = indexOfStock(shop, itemRef);
        if (idx < 0) {
            throw new IllegalStateException("That isn't for sale here");
        }
        ShopStockEntry entry = shop.stock().get(idx);
        if (entry.quantity() >= 0 && entry.quantity() < want) {
            throw new IllegalStateException(entry.quantity() == 0
                    ? entry.name() + " is out of stock" : "Only " + entry.quantity() + " " + entry.name() + " left");
        }
        long unit = buyPrice(entry.basePriceCopper(), shop.economyFactor());
        long cost = unit * want;

        playerStateService.spendCoins(playerId, cost); // throws if the purse can't cover it
        PlayerRuntimeStateDto state =
                playerStateService.addItem(playerId, new InventoryItem(entry.name(), want, entry.kind()));

        if (entry.quantity() >= 0) {
            ShopStockEntry left = new ShopStockEntry(entry.srdIndex(), entry.name(), entry.kind(),
                    entry.basePriceCopper(), entry.quantity() - want);
            saveStock(session, shop, idx, left);
        }
        log.info("Shop buy: session={} player={} {}× {} for {} at '{}'",
                sessionId, playerId, want, entry.name(), MoneyUtil.format(cost), shop.name());
        return new ShopTxnResult(shop.name(), entry.name(), want, unit, -cost, state);
    }

    /** Sell {@code qty} of an item the player holds. Removes the items, credits the buy-back price. */
    @Transactional
    public ShopTxnResult sell(UUID sessionId, UUID playerId, String shopKey, String itemName, int qty) {
        GameSession session = requireSession(sessionId);
        Shop shop = openShop(session, shopKey);
        int want = Math.max(1, qty);

        PlayerRuntimeStateDto current = playerStateService.getState(playerId);
        InventoryItem held = current.inventory().stream()
                .filter(i -> !MoneyUtil.isCoinName(i.name()) && i.name().equalsIgnoreCase(itemName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("You don't have that to sell"));
        if (held.qty() < want) {
            throw new IllegalStateException("You only have " + held.qty() + " " + held.name());
        }
        long base = baseValue(shop, held.name());
        if (base < 0) {
            throw new IllegalStateException("This merchant won't buy " + held.name());
        }
        long unit = sellPrice(base, shop.economyFactor());
        long proceeds = unit * want;

        playerStateService.removeItem(playerId, held.name(), held.kind(), want); // throws if short
        PlayerRuntimeStateDto state = playerStateService.addCoins(playerId, proceeds);

        log.info("Shop sell: session={} player={} {}× {} for {} at '{}'",
                sessionId, playerId, want, held.name(), MoneyUtil.format(proceeds), shop.name());
        return new ShopTxnResult(shop.name(), held.name(), want, unit, proceeds, state);
    }

    /* ── location gating ─────────────────────────────────────────── */

    /** Shops open at the session's current location (region match, and subregion match when the shop pins one). */
    public List<Shop> openShops(GameSession session) {
        String region = session.getCurrentRegion();
        if (region == null || region.isBlank()) {
            return List.of();
        }
        String sub = session.getCurrentSubregion();
        List<Shop> open = new ArrayList<>();
        for (Shop shop : session.getShops()) {
            if (shop == null || !eq(shop.region(), region)) {
                continue;
            }
            // A shop with no subregion is a region-level merchant (open anywhere in the region); one
            // pinned to a subregion opens only when the party is in that exact subregion.
            if (shop.subregion() == null || shop.subregion().isBlank() || eq(shop.subregion(), sub)) {
                open.add(shop);
            }
        }
        return open;
    }

    /* ── pricing ─────────────────────────────────────────────────── */

    private static long buyPrice(long base, double factor) {
        return base <= 0 ? 0 : Math.max(1, Math.round(base * factor));
    }

    private static long sellPrice(long base, double factor) {
        return base <= 0 ? 0 : Math.max(1, Math.round(base * factor / 2.0));
    }

    /** The list value of an item at this shop: its own stock price if it carries it, else the SRD price. */
    private long baseValue(Shop shop, String name) {
        for (ShopStockEntry e : shop.stock()) {
            if (e != null && eq(e.name(), name) && e.basePriceCopper() > 0) {
                return e.basePriceCopper();
            }
        }
        Long srd = srdPrices().get(name.toLowerCase(Locale.ROOT));
        return srd == null ? -1 : srd;
    }

    /* ── view building ───────────────────────────────────────────── */

    private ShopView toView(Shop shop, List<InventoryItem> inventory) {
        List<ShopView.Stock> stock = new ArrayList<>();
        for (ShopStockEntry e : shop.stock()) {
            if (e == null) {
                continue;
            }
            stock.add(new ShopView.Stock(e.srdIndex(), e.name(), e.kind(),
                    buyPrice(e.basePriceCopper(), shop.economyFactor()), e.quantity()));
        }
        List<ShopView.SellOffer> offers = new ArrayList<>();
        for (InventoryItem it : inventory) {
            if (it == null || MoneyUtil.isCoinName(it.name())) {
                continue;
            }
            long base = baseValue(shop, it.name());
            if (base >= 0) {
                offers.add(new ShopView.SellOffer(it.name(), it.kind(), it.qty(),
                        sellPrice(base, shop.economyFactor())));
            }
        }
        return new ShopView(shop.key(), shop.name(), shop.type(), shop.description(),
                shop.region(), shop.subregion(), shop.economyFactor(), shop.ownerNpcName(), stock, offers);
    }

    /* ── internals ───────────────────────────────────────────────── */

    private GameSession requireSession(UUID sessionId) {
        GameSession s = sessionRepository.findById(sessionId).orElse(null);
        if (s == null) {
            throw new IllegalStateException("This game session no longer exists");
        }
        return s;
    }

    private Shop openShop(GameSession session, String shopKey) {
        return openShops(session).stream()
                .filter(s -> eq(s.key(), shopKey))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("There's no such shop open here"));
    }

    private static int indexOfStock(Shop shop, String ref) {
        List<ShopStockEntry> stock = shop.stock();
        for (int i = 0; i < stock.size(); i++) {
            ShopStockEntry e = stock.get(i);
            if (e != null && (eq(e.srdIndex(), ref) || eq(e.name(), ref))) {
                return i;
            }
        }
        return -1;
    }

    /** Replace one stock line on the session's copy of a shop and persist. */
    private void saveStock(GameSession session, Shop shop, int stockIdx, ShopStockEntry replacement) {
        List<ShopStockEntry> stock = new ArrayList<>(shop.stock());
        stock.set(stockIdx, replacement);
        Shop updated = new Shop(shop.key(), shop.name(), shop.type(), shop.description(),
                shop.region(), shop.subregion(), shop.economyFactor(), shop.ownerNpcName(), stock);
        List<Shop> shops = new ArrayList<>(session.getShops());
        for (int i = 0; i < shops.size(); i++) {
            if (eq(shops.get(i).key(), shop.key())) {
                shops.set(i, updated);
                break;
            }
        }
        session.setShops(shops);
        sessionRepository.save(session);
    }

    private PlayerRuntimeStateDto safeState(UUID playerId) {
        try {
            return playerStateService.getState(playerId);
        } catch (Exception e) {
            return null; // no runtime state (e.g. a spectator/host) — no purse, no sell offers
        }
    }

    private Map<String, Long> srdPrices() {
        Map<String, Long> cached = srdPriceByName;
        if (cached != null) {
            return cached;
        }
        Map<String, Long> map = new ConcurrentHashMap<>();
        for (Map<String, Object> rec : referenceService.listEquipment()) {
            Object name = rec.get("name");
            Object cost = rec.get("cost");
            if (name == null || cost == null) {
                continue; // e.g. SRD armor entries carry no cost
            }
            long copper = MoneyUtil.parseCoins(cost.toString());
            if (copper > 0) {
                map.put(name.toString().trim().toLowerCase(Locale.ROOT), copper);
            }
        }
        srdPriceByName = map;
        return map;
    }

    private static boolean eq(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }
}
