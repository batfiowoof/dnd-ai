package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.InventoryItem;
import com.dungeon.master.model.dto.MagicItemEffect;
import com.dungeon.master.service.game.combat.CombatMath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the <em>live</em> mechanical effects of a player's magic items — the analogue of
 * {@code ConditionRules} for gear rather than conditions. An item's effect is live when it is
 * either <b>attuned</b> (for attunement-required items) or <b>equipped</b> (for mundane-magic
 * {@code +N} gear that needs no attunement); attunement is treated as implying the item is worn.
 * All bonuses are summed across live items so callers can fold them in alongside condition
 * modifiers. Backed by {@link MagicItemCatalog}.
 */
@Component
@RequiredArgsConstructor
public class MagicItemEffects {

    private final MagicItemCatalog catalog;

    /** Flat AC bonus from live items (Bracers of Defense only when wearing no armor/shield). */
    public int acBonus(List<InventoryItem> inv, List<String> attuned) {
        int total = 0;
        boolean armored = CombatMath.equippedBodyArmor(inv) != null || CombatMath.hasEquippedShield(inv);
        for (MagicItemEffect e : liveEffects(inv, attuned)) {
            if (e.acBonus() == 0) continue;
            if (e.requiresNoArmor() && armored) continue;
            total += e.acBonus();
        }
        return total;
    }

    /** Flat bonus to weapon attack rolls from live items (a magic weapon's +N). */
    public int attackBonus(List<InventoryItem> inv, List<String> attuned) {
        return liveEffects(inv, attuned).stream().mapToInt(MagicItemEffect::attackBonus).sum();
    }

    /** Flat bonus to weapon damage rolls from live items (a magic weapon's +N). */
    public int damageBonus(List<InventoryItem> inv, List<String> attuned) {
        return liveEffects(inv, attuned).stream().mapToInt(MagicItemEffect::damageBonus).sum();
    }

    /** Flat bonus to all saving throws from live items (Ring/Cloak of Protection). */
    public int saveBonus(List<InventoryItem> inv, List<String> attuned) {
        return liveEffects(inv, attuned).stream().mapToInt(MagicItemEffect::saveBonus).sum();
    }

    /** Damage types the player has Resistance to from live items (canonical casing). */
    public Set<String> resistances(List<InventoryItem> inv, List<String> attuned) {
        Set<String> out = new LinkedHashSet<>();
        for (MagicItemEffect e : liveEffects(inv, attuned)) {
            if (e.resistances() != null) out.addAll(e.resistances());
        }
        return out;
    }

    /** Advantage tags granted by live items (e.g. "initiative", "perception"); best-effort. */
    public Set<String> advantageOn(List<InventoryItem> inv, List<String> attuned) {
        Set<String> out = new LinkedHashSet<>();
        for (MagicItemEffect e : liveEffects(inv, attuned)) {
            if (e.advantageOn() != null) out.addAll(e.advantageOn());
        }
        return out;
    }

    /**
     * The player's effective ability scores after any set-ability items (Gauntlets of Ogre Power,
     * Headband of Intellect, Belt of Giant Strength). The higher of the base score and the item's
     * set value wins, per the SRD ("no effect if your score is already higher"). Returns a copy;
     * the stored abilities are never mutated. Ability keys are uppercased (STR/DEX/…).
     */
    public Map<String, Integer> effectiveAbilities(Map<String, Integer> base,
            List<InventoryItem> inv, List<String> attuned) {
        Map<String, Integer> eff = new LinkedHashMap<>(base == null ? Map.of() : base);
        for (MagicItemEffect e : liveEffects(inv, attuned)) {
            if (e.setAbility() == null) continue;
            e.setAbility().forEach((ability, value) -> {
                String key = ability.toUpperCase(Locale.ROOT);
                int current = eff.getOrDefault(key, 10);
                if (value > current) eff.put(key, value);
            });
        }
        return eff;
    }

    /* ── internals ───────────────────────────────────────────────────── */

    /** Every live (attuned-or-equipped, mechanically-resolvable) item effect in the inventory. */
    private List<MagicItemEffect> liveEffects(List<InventoryItem> inv, List<String> attuned) {
        List<MagicItemEffect> live = new ArrayList<>();
        if (inv == null) return live;
        for (InventoryItem item : inv) {
            catalog.forItemName(item.name())
                    .filter(MagicItemEffect::hasMechanicalEffect)
                    .filter(e -> isLive(item, e, attuned))
                    .ifPresent(live::add);
        }
        return live;
    }

    private static boolean isLive(InventoryItem item, MagicItemEffect effect, List<String> attuned) {
        if (effect.requiresAttunement()) {
            return contains(attuned, item.name());
        }
        // Non-attunement gear applies while equipped; slotless effects (rare) apply while carried.
        return item.slot() != null || effect.slot() == null;
    }

    private static boolean contains(List<String> attuned, String name) {
        if (attuned == null || name == null) return false;
        for (String a : attuned) {
            if (name.equalsIgnoreCase(a)) return true;
        }
        return false;
    }
}
