package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.InventoryItem;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.Shop;
import com.dungeon.master.model.dto.ShopStockEntry;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.enums.ItemKind;
import com.dungeon.master.model.enums.ShopType;
import com.dungeon.master.repository.GameSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Economy pricing, buy-back = half, location gating, stock limits, and insufficient funds. */
@ExtendWith(MockitoExtension.class)
class ShopServiceTest {

    @Mock GameSessionRepository sessionRepository;
    @Mock PlayerStateService playerStateService;
    @Mock Dnd5eReferenceService referenceService;
    @InjectMocks ShopService shopService;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID playerId = UUID.randomUUID();

    private static ShopStockEntry longsword(int qty) {
        // 15 gp = 1500 cp list price.
        return new ShopStockEntry("longsword", "Longsword", ItemKind.WEAPON, 1500, qty);
    }

    private GameSession sessionAt(String region, String subregion, Shop... shops) {
        GameSession s = GameSession.builder()
                .currentRegion(region)
                .currentSubregion(subregion)
                .shops(List.of(shops))
                .build();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(s));
        return s;
    }

    private static Shop shop(String region, String subregion, double economy, ShopStockEntry... stock) {
        return new Shop("anvil", "The Rusty Anvil", ShopType.BLACKSMITH, "", region, subregion,
                economy, null, List.of(stock));
    }

    private static PlayerRuntimeStateDto stateWith(long copper, List<InventoryItem> inventory) {
        return new PlayerRuntimeStateDto(UUID.randomUUID(), 10, 10, 0, 10, Map.of(),
                Map.of(), List.of(), List.of(), inventory, List.of(), List.of(), List.of(),
                false, 0, 0, false, false, null, 0, 1, 1, copper, List.of());
    }

    @Test
    void buyChargesListPriceTimesEconomyFactor() {
        sessionAt("Town", null, shop("Town", null, 1.2, longsword(-1)));
        when(playerStateService.spendCoins(any(), any(Long.class)))
                .thenReturn(stateWith(0, List.of()));
        when(playerStateService.addItem(any(), any())).thenReturn(stateWith(0, List.of()));

        ShopService.ShopTxnResult r = shopService.buy(sessionId, playerId, "anvil", "Longsword", 2);

        // 1500 × 1.2 = 1800 per unit, ×2 = 3600 copper.
        assertEquals(1800, r.unitPriceCopper());
        assertEquals(-3600, r.copperDelta());
        verify(playerStateService).spendCoins(playerId, 3600L);
        verify(playerStateService).addItem(eq(playerId), any(InventoryItem.class));
    }

    @Test
    void sellPaysHalfOfEconomyAdjustedValue() {
        sessionAt("Town", null, shop("Town", null, 1.2, longsword(-1)));
        when(playerStateService.getState(playerId))
                .thenReturn(stateWith(0, List.of(new InventoryItem("Longsword", 1, ItemKind.WEAPON))));
        when(playerStateService.removeItem(any(), any(), any(), eq(1)))
                .thenReturn(stateWith(0, List.of()));
        when(playerStateService.addCoins(any(), any(Long.class))).thenReturn(stateWith(900, List.of()));

        ShopService.ShopTxnResult r = shopService.sell(sessionId, playerId, "anvil", "Longsword", 1);

        // round(1500 × 1.2 / 2) = 900.
        assertEquals(900, r.unitPriceCopper());
        assertEquals(900, r.copperDelta());
        verify(playerStateService).removeItem(playerId, "Longsword", ItemKind.WEAPON, 1);
        verify(playerStateService).addCoins(playerId, 900L);
    }

    @Test
    void shopIsClosedWhenPartyIsElsewhere() {
        // Shop is pinned to the Market; the party stands at the Docks.
        sessionAt("Town", "Docks", shop("Town", "Market", 1.0, longsword(-1)));

        assertThrows(IllegalStateException.class,
                () -> shopService.buy(sessionId, playerId, "anvil", "Longsword", 1));
    }

    @Test
    void cannotBuyMoreThanLimitedStock() {
        sessionAt("Town", null, shop("Town", null, 1.0, longsword(1)));

        assertThrows(IllegalStateException.class,
                () -> shopService.buy(sessionId, playerId, "anvil", "Longsword", 2));
    }

    @Test
    void insufficientFundsRejectsPurchase() {
        sessionAt("Town", null, shop("Town", null, 1.0, longsword(-1)));
        lenient().when(playerStateService.spendCoins(any(), any(Long.class)))
                .thenThrow(new IllegalStateException("Not enough coin"));

        assertThrows(IllegalStateException.class,
                () -> shopService.buy(sessionId, playerId, "anvil", "Longsword", 1));
    }
}
