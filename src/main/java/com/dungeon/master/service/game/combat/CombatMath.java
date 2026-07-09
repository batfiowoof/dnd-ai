package com.dungeon.master.service.game.combat;

import com.dungeon.master.model.dto.ActiveCondition;
import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.dto.GridState;
import com.dungeon.master.model.dto.InventoryItem;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.SpellEffect;
import com.dungeon.master.model.dto.Token;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.Enemy;
import com.dungeon.master.model.enums.EquipSlot;
import com.dungeon.master.model.enums.ItemKind;
import com.dungeon.master.model.enums.ItemSubtype;
import com.dungeon.master.model.enums.RollMode;
import com.dungeon.master.service.game.ConditionRules;
import com.dungeon.master.service.game.DiceService;
import com.dungeon.master.service.game.GridService;
import com.dungeon.master.service.game.SpellcastingRules;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure combat arithmetic shared across the combat collaborators: dice-notation building,
 * crit/scaling math, weapon tables, and stat-block derived modifiers. No Spring dependencies —
 * methods that need a {@link DiceService} or {@link GridService} take it as a parameter so this
 * stays a stateless, side-effect-free utility.
 */
public final class CombatMath {

    private CombatMath() {}

    public static final Pattern DICE = Pattern.compile("(\\d*)d(\\d+)([+-]\\d+)?",
            Pattern.CASE_INSENSITIVE);

    /** Keyword → normal weapon range (ft); mirrors {@code lib/combat.ts WEAPON_RANGE}. */
    public static final List<Map.Entry<String, Integer>> WEAPON_RANGE = List.of(
            Map.entry("longbow", 150), Map.entry("crossbow", 80), Map.entry("shortbow", 80),
            Map.entry("bow", 80), Map.entry("blowgun", 25), Map.entry("sling", 30),
            Map.entry("javelin", 30), Map.entry("dart", 20), Map.entry("handaxe", 20),
            Map.entry("spear", 20), Map.entry("trident", 20), Map.entry("halberd", 10),
            Map.entry("glaive", 10), Map.entry("pike", 10), Map.entry("lance", 10),
            Map.entry("whip", 10));

    /**
     * Keyword → base weapon damage die (SRD). Mirrors {@link #WEAPON_RANGE}: ordered
     * most-specific first so {@code contains} matching picks "greatsword" before "sword"
     * and "greataxe" before "axe".
     */
    public static final List<Map.Entry<String, String>> WEAPON_DAMAGE = List.of(
            Map.entry("greataxe", "1d12"), Map.entry("greatsword", "2d6"), Map.entry("maul", "2d6"),
            Map.entry("halberd", "1d10"), Map.entry("glaive", "1d10"), Map.entry("heavy crossbow", "1d10"),
            Map.entry("pike", "1d10"), Map.entry("lance", "1d12"),
            Map.entry("longsword", "1d8"), Map.entry("battleaxe", "1d8"), Map.entry("warhammer", "1d8"),
            Map.entry("rapier", "1d8"), Map.entry("longbow", "1d8"), Map.entry("light crossbow", "1d8"),
            Map.entry("morningstar", "1d8"), Map.entry("flail", "1d8"), Map.entry("war pick", "1d8"),
            Map.entry("trident", "1d6"), Map.entry("shortsword", "1d6"), Map.entry("scimitar", "1d6"),
            Map.entry("shortbow", "1d6"), Map.entry("hand crossbow", "1d6"), Map.entry("mace", "1d6"),
            Map.entry("spear", "1d6"), Map.entry("handaxe", "1d6"), Map.entry("quarterstaff", "1d6"),
            Map.entry("javelin", "1d6"), Map.entry("blowgun", "1d1"),
            Map.entry("dagger", "1d4"), Map.entry("dart", "1d4"), Map.entry("sling", "1d4"),
            Map.entry("club", "1d4"), Map.entry("sickle", "1d4"), Map.entry("whip", "1d4"));

    /**
     * Keyword → 2024 weapon-mastery property (SRD 5.2.1). Ordered most-specific first (mirrors
     * {@link #WEAPON_DAMAGE}) so {@code contains} matching picks "greatsword" before "sword" and
     * "light crossbow" before "crossbow". Weapons absent here (and ranged firearms) have no mastery.
     */
    public static final List<Map.Entry<String, String>> WEAPON_MASTERY = List.of(
            Map.entry("greataxe", "Cleave"), Map.entry("greatsword", "Graze"), Map.entry("maul", "Topple"),
            Map.entry("halberd", "Cleave"), Map.entry("glaive", "Graze"), Map.entry("heavy crossbow", "Push"),
            Map.entry("light crossbow", "Slow"), Map.entry("hand crossbow", "Vex"), Map.entry("pike", "Push"),
            Map.entry("lance", "Topple"), Map.entry("longsword", "Sap"), Map.entry("battleaxe", "Topple"),
            Map.entry("warhammer", "Push"), Map.entry("war pick", "Sap"), Map.entry("morningstar", "Sap"),
            Map.entry("rapier", "Vex"), Map.entry("longbow", "Slow"), Map.entry("flail", "Sap"),
            Map.entry("trident", "Topple"), Map.entry("shortsword", "Vex"), Map.entry("scimitar", "Nick"),
            Map.entry("shortbow", "Vex"), Map.entry("mace", "Sap"), Map.entry("spear", "Sap"),
            Map.entry("handaxe", "Vex"), Map.entry("quarterstaff", "Topple"), Map.entry("javelin", "Slow"),
            Map.entry("blowgun", "Vex"), Map.entry("greatclub", "Push"), Map.entry("light hammer", "Nick"),
            Map.entry("dagger", "Nick"), Map.entry("dart", "Vex"), Map.entry("sling", "Slow"),
            Map.entry("club", "Slow"), Map.entry("sickle", "Nick"), Map.entry("whip", "Slow"));

    /** The mastery of the inventory's best/equipped weapon (SRD string), or {@code null} when none. */
    public static String masteryFor(List<InventoryItem> inv) {
        if (inv == null) return null;
        InventoryItem chosen = null;
        for (InventoryItem item : inv) {
            if (item.name() == null || item.kind() != ItemKind.WEAPON) continue;
            if (chosen == null) chosen = item;               // first weapon as the default
            if (item.equipped()) { chosen = item; break; }   // an equipped weapon wins
        }
        return chosen == null ? null : masteryForName(chosen.name());
    }

    private static String masteryForName(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : WEAPON_MASTERY) {
            if (n.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    /* ── dice notation ───────────────────────────────────────────── */

    public static String notation(int bonus) {
        return "1d20" + (bonus > 0 ? "+" + bonus : bonus < 0 ? String.valueOf(bonus) : "");
    }

    public static DiceRollResult constant(int n) {
        return new DiceRollResult(String.valueOf(n), 0, 0, n, RollMode.NORMAL,
                List.of(n), null, n, false, false);
    }

    /**
     * Merge an extra flat modifier into a dice expression's single modifier term, preserving the
     * one-modifier invariant the dice parser requires ("1d8+3" + 2 -> "1d8+5"; "1d8" + 2 -> "1d8+2";
     * "1d8+3" + -3 -> "1d8"). A flat-constant expression becomes the summed constant; an
     * unparseable expression is returned unchanged. Used to fold a magic weapon's +N (and set-ability
     * modifier deltas) into weapon damage without producing chained "+a+b" terms.
     */
    public static String addFlat(String notation, int extra) {
        if (notation == null) return null;
        if (extra == 0) return notation;
        String n = notation.trim();
        if (n.matches("-?\\d+")) return String.valueOf(Integer.parseInt(n) + extra);
        Matcher m = DICE.matcher(n);
        if (!m.matches()) return notation;
        int mod = (m.group(3) == null ? 0 : Integer.parseInt(m.group(3))) + extra;
        return m.group(1) + "d" + m.group(2)
                + (mod > 0 ? "+" + mod : mod < 0 ? String.valueOf(mod) : "");
    }

    /** 5E crit: double the number of dice, keep the flat modifier ("1d8+3" -> "2d8+3"). */
    public static String critDouble(String notation) {
        if (notation == null) return null;
        Matcher m = DICE.matcher(notation.trim());
        if (!m.matches()) return notation;   // flat constants / unparseable left unchanged
        int count = m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
        return (count * 2) + "d" + m.group(2) + (m.group(3) == null ? "" : m.group(3));
    }

    /** Spread {@code n} darts/rays across {@code k} targets as evenly as possible. */
    public static int[] distribute(int n, int k) {
        if (k <= 0) return new int[0];
        int[] out = new int[k];
        for (int i = 0; i < k; i++) out[i] = n / k + (i < n % k ? 1 : 0);
        return out;
    }

    /**
     * Combine a base dice expression with the spell's slot/cantrip scaling and (when the
     * spell adds it) the caster's spellcasting modifier into a single rollable notation.
     * Scaling dice are folded into the count only when their faces match the base; an
     * unparseable expression is returned unchanged.
     */
    public static String scaledNotation(String baseDice, SpellEffect effect, int spellLevel, Character caster) {
        if (baseDice == null || baseDice.isBlank()) return "0";
        Matcher m = DICE.matcher(baseDice.trim());
        if (!m.matches()) return baseDice;
        int count = m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
        int sides = Integer.parseInt(m.group(2));
        int mod = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));

        if (effect.perSlotAbove() != null && spellLevel > effect.level()) {
            Matcher s = DICE.matcher(effect.perSlotAbove());
            if (s.matches() && Integer.parseInt(s.group(2)) == sides) {
                int per = s.group(1).isEmpty() ? 1 : Integer.parseInt(s.group(1));
                count += per * (spellLevel - effect.level());
            }
        }
        if (effect.cantripDie() != null && effect.level() == 0) {
            int lvl = caster == null ? 1 : caster.getLevel();
            int tier = (lvl >= 5 ? 1 : 0) + (lvl >= 11 ? 1 : 0) + (lvl >= 17 ? 1 : 0);
            Matcher cnt = DICE.matcher(effect.cantripDie());
            if (cnt.matches() && Integer.parseInt(cnt.group(2)) == sides) {
                int per = cnt.group(1).isEmpty() ? 1 : Integer.parseInt(cnt.group(1));
                count += per * tier;
            }
        }
        if (effect.addCastingMod()) mod += SpellcastingRules.castingMod(caster);
        return count + "d" + sides + (mod > 0 ? "+" + mod : mod < 0 ? String.valueOf(mod) : "");
    }

    /** Roll a damage/heal expression that may be standard dice ("8d6+3") or a flat number ("1"). */
    public static DiceRollResult rollExpr(DiceService diceService, String expr) {
        if (expr == null || expr.isBlank()) return constant(0);
        String e = expr.trim();
        if (e.matches("\\d+")) return constant(Integer.parseInt(e));
        if (DICE.matcher(e).matches()) return diceService.roll(e);
        return constant(0);
    }

    /**
     * Roll a 1d20 attack with the given bonus, applying disadvantage (e.g. against a
     * Dodging target). The NORMAL path uses the single-arg roll so existing callers and
     * tests see the same notation.
     */
    public static DiceRollResult rollAttack(DiceService diceService, int bonus, boolean disadvantage) {
        return rollAttack(diceService, bonus, disadvantage ? RollMode.DISADVANTAGE : RollMode.NORMAL);
    }

    /** Roll a 1d20 attack with the given bonus and an explicit advantage/disadvantage mode. */
    public static DiceRollResult rollAttack(DiceService diceService, int bonus, RollMode mode) {
        return mode == RollMode.NORMAL
                ? diceService.roll(notation(bonus))
                : diceService.roll(notation(bonus), mode);
    }

    /* ── ability / stat modifiers ────────────────────────────────── */

    /** Ability modifier from a player's runtime ability scores (defaults to 0 if absent). */
    public static int abilityMod(PlayerRuntimeStateDto state, String ability) {
        Integer score = state.abilities() == null ? null : state.abilities().get(ability);
        return score == null ? 0 : Math.floorDiv(score - 10, 2);
    }

    /** An enemy's saving-throw modifier for an ability (ability mod; DEX falls back to dexMod). */
    public static int enemySaveMod(Enemy e, String ability) {
        if (ability == null) return e.getDexMod();
        Integer score = e.getAbilities() == null ? null : e.getAbilities().get(ability.toUpperCase());
        if (score != null) return Math.floorDiv(score - 10, 2);
        return "DEX".equalsIgnoreCase(ability) ? e.getDexMod() : 0;
    }

    /** A character's DEX modifier (0 when absent). */
    public static int dexMod(Character c) {
        return c == null ? 0 : Math.floorDiv(c.getDexterity() - 10, 2);
    }

    /** Attack bonus = best of STR/DEX modifier + proficiency bonus (+2 when no character). */
    public static int attackBonus(Character c) {
        if (c == null) return 2;
        int str = Math.floorDiv(c.getStrength() - 10, 2);
        int dex = Math.floorDiv(c.getDexterity() - 10, 2);
        return Math.max(str, dex) + c.getProficiencyBonus();
    }

    /**
     * Armour class from the character + equipped gear, including live AC-buff conditions
     * (Mage Armor / Shield of Faith / Barkskin). Recognized body armor in the CHEST slot sets the
     * base AC (+ DEX capped by armor category), an equipped shield adds +2, and conditions apply on
     * top. Falls back to the character's stored AC when no recognized armor is equipped.
     */
    public static int armorClass(Character c, List<InventoryItem> inv, List<ActiveCondition> conds) {
        int dex = dexMod(c);
        int base = armorClassBase(c == null ? 10 : c.getArmorClass(), dex, inv);
        return ConditionRules.acAdjust(conds, base, dex);
    }

    /**
     * The base armour class from equipped gear, <em>before</em> live conditions. Recognized body
     * armor in the CHEST slot sets the base (+ DEX capped by category — full for light, +2 for
     * medium, none for heavy); an equipped shield adds +2. When no recognized body armor is
     * equipped, {@code fallbackAc} (the character's stored AC) is used as the base.
     */
    public static int armorClassBase(int fallbackAc, int dexMod, List<InventoryItem> inv) {
        InventoryItem body = equippedBodyArmor(inv);
        ArmorStat stat = body == null ? null : armorStatFor(body.name());
        int base;
        if (stat != null) {
            int dexPart = stat.dexCap() == null ? dexMod : Math.min(dexMod, stat.dexCap());
            base = stat.base() + dexPart;
        } else {
            base = fallbackAc;   // no (recognized) armor equipped — keep the entered AC
        }
        return base + (hasEquippedShield(inv) ? 2 : 0);
    }

    /** The body armor equipped in the CHEST slot, or {@code null} when none is. */
    public static InventoryItem equippedBodyArmor(List<InventoryItem> inv) {
        if (inv == null) return null;
        for (InventoryItem it : inv) {
            if (it.slot() == EquipSlot.CHEST && it.kind() == ItemKind.ARMOR) return it;
        }
        return null;
    }

    /** Whether a shield is equipped in the OFF_HAND slot. */
    public static boolean hasEquippedShield(List<InventoryItem> inv) {
        if (inv == null) return false;
        for (InventoryItem it : inv) {
            if (it.slot() == EquipSlot.OFF_HAND && it.subtype() == ItemSubtype.SHIELD) return true;
        }
        return false;
    }

    private static ArmorStat armorStatFor(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, ArmorStat> e : ARMOR) {
            if (n.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    /** Base AC and DEX cap for a recognized armor ({@code dexCap} null = full DEX / light). */
    private record ArmorStat(int base, Integer dexCap) {}

    /**
     * Keyword → armor stats (SRD). Ordered most-specific first so {@code contains} matching picks
     * "studded leather" before "leather" and "breastplate"/"half plate" before "plate".
     * {@code dexCap}: null = light (full DEX), 2 = medium, 0 = heavy (no DEX).
     */
    private static final List<Map.Entry<String, ArmorStat>> ARMOR = List.of(
            Map.entry("studded leather", new ArmorStat(12, null)),
            Map.entry("leather", new ArmorStat(11, null)),
            Map.entry("padded", new ArmorStat(11, null)),
            Map.entry("hide", new ArmorStat(12, 2)),
            Map.entry("chain shirt", new ArmorStat(13, 2)),
            Map.entry("scale mail", new ArmorStat(14, 2)),
            Map.entry("breastplate", new ArmorStat(14, 2)),
            Map.entry("half plate", new ArmorStat(15, 2)),
            Map.entry("ring mail", new ArmorStat(14, 0)),
            Map.entry("chain mail", new ArmorStat(16, 0)),
            Map.entry("splint", new ArmorStat(17, 0)),
            Map.entry("plate", new ArmorStat(18, 0)));

    /* ── weapon inference (from an inventory list) ───────────────── */

    /** Base damage die of the inventory's best/equipped weapon, or "1d4" when unarmed/unknown. */
    public static String weaponDie(List<InventoryItem> inv) {
        if (inv == null) return "1d4";
        InventoryItem chosen = null;
        for (InventoryItem item : inv) {
            if (item.name() == null || item.kind() != ItemKind.WEAPON) continue;
            if (chosen == null) chosen = item;        // first weapon as the default
            if (item.equipped()) { chosen = item; break; }  // an equipped weapon wins
        }
        return chosen == null ? "1d4" : dieForName(chosen.name());
    }

    /** Map a weapon name to its SRD damage die ("1d4" when unknown/unarmed). */
    private static String dieForName(String name) {
        if (name == null) return "1d4";
        String n = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : WEAPON_DAMAGE) {
            if (n.contains(e.getKey())) return e.getValue();
        }
        return "1d4";
    }

    /** The weapon equipped in the OFF_HAND slot, or {@code null} when none is. */
    public static InventoryItem offHandWeapon(List<InventoryItem> inv) {
        if (inv == null) return null;
        for (InventoryItem item : inv) {
            if (item.kind() == ItemKind.WEAPON && item.slot() == EquipSlot.OFF_HAND) {
                return item;
            }
        }
        return null;
    }

    /**
     * Two-weapon off-hand damage: the off-hand weapon's die with <em>no</em> ability modifier
     * (5e default unless a feature grants it). Falls back to 1d4 when the weapon is unknown.
     */
    public static String offHandDamageDice(InventoryItem offHand) {
        return dieForName(offHand == null ? null : offHand.name());
    }

    /**
     * Weapon damage = the equipped weapon's die (by name) + best STR/DEX modifier. Falls back
     * to 1d4 (improvised / unarmed) when no weapon is found. The ability modifier keeps the
     * finesse simplification already used for the attack bonus (best of STR/DEX).
     */
    public static String damageDice(Character c, List<InventoryItem> inv) {
        int mod = 0;
        if (c != null) {
            mod = Math.max(
                    Math.floorDiv(c.getStrength() - 10, 2),
                    Math.floorDiv(c.getDexterity() - 10, 2));
        }
        return weaponDie(inv) + (mod > 0 ? "+" + mod : mod < 0 ? String.valueOf(mod) : "");
    }

    /**
     * The basic-attack range in feet, inferred from weapon items by name: ranged/thrown weapons
     * get their normal range, reach weapons 10 ft, otherwise 5 ft melee (the floor, used for
     * unarmed / no weapons). Uses the longest reach available.
     */
    public static int attackRangeFeet(List<InventoryItem> inv) {
        int range = GridService.FEET_PER_SQUARE; // 5 ft melee floor
        if (inv == null) return range;
        for (InventoryItem item : inv) {
            // Only weapons set the range — avoids gear false matches ("Iron Spike" → "pike").
            if (item.name() == null || item.kind() != ItemKind.WEAPON) continue;
            String name = item.name().toLowerCase(Locale.ROOT);
            for (Map.Entry<String, Integer> e : WEAPON_RANGE) {
                if (name.contains(e.getKey())) range = Math.max(range, e.getValue());
            }
        }
        return range;
    }

    /**
     * A player's melee reach in feet for opportunity-attack purposes: 10 ft when a reach weapon is
     * equipped, otherwise the 5 ft floor. Mirrors {@link #attackRangeFeet} but caps at melee reach
     * (ranged weapons don't extend the square from which you threaten an opportunity attack).
     */
    public static int playerReachFeet(List<InventoryItem> inv) {
        int reach = GridService.FEET_PER_SQUARE; // 5 ft melee floor
        if (inv == null) return reach;
        for (InventoryItem item : inv) {
            if (item.name() == null || item.kind() != ItemKind.WEAPON) continue;
            String name = item.name().toLowerCase(Locale.ROOT);
            if (name.contains("pike") || name.contains("glaive") || name.contains("halberd")
                    || name.contains("lance") || name.contains("whip")) {
                reach = Math.max(reach, 10);
            }
        }
        return reach;
    }

    /* ── grid-aware combat math (gridService passed in) ──────────── */

    /** True when two tokens are within 5 ft (one square); assumes melee when there is no grid. */
    public static boolean isMelee(GridService gridService, Token a, Token b, GridState grid) {
        if (grid == null || a == null || b == null) {
            return true;
        }
        return gridService.distanceFeet(a.getX(), a.getY(), b.getX(), b.getY())
                <= GridService.FEET_PER_SQUARE;
    }

    /**
     * Defender AC including cover from intervening walls (base + 0/+2/+5). Returns the
     * base AC unchanged when either token or the grid is absent (legacy encounters).
     */
    public static int effectiveAc(GridService gridService, int baseAc, Token attacker, Token defender, GridState grid) {
        if (attacker == null || defender == null || grid == null) {
            return baseAc;
        }
        return baseAc + gridService.coverBonus(grid,
                attacker.getX(), attacker.getY(), defender.getX(), defender.getY());
    }

    /** Net attack roll mode for a player attacking an enemy (conditions + the target Dodging). */
    public static RollMode playerVsEnemyMode(List<ActiveCondition> attackerConds, Enemy defender,
                                             Token defenderTok, boolean melee) {
        RollMode cond = ConditionRules.attackMode(attackerConds, defender.getConditions(), melee);
        RollMode dodge = defenderTok != null && defenderTok.isDodging()
                ? RollMode.DISADVANTAGE : RollMode.NORMAL;
        return RollMode.combine(cond, dodge);
    }

    /** Net attack roll mode for an enemy attacking a player (conditions + the target Dodging). */
    public static RollMode enemyVsPlayerMode(Enemy attacker, List<ActiveCondition> victimConds,
                                             Token victimTok, boolean melee) {
        RollMode cond = ConditionRules.attackMode(attacker.getConditions(), victimConds, melee);
        RollMode dodge = victimTok != null && victimTok.isDodging()
                ? RollMode.DISADVANTAGE : RollMode.NORMAL;
        return RollMode.combine(cond, dodge);
    }

    /** A combatant's token by refId (player/enemy UUID string), or {@code null} on a legacy/no-grid grid. */
    public static Token tokenFor(GridState grid, String refId) {
        if (grid == null || grid.getTokens() == null) {
            return null;
        }
        return grid.getTokens().get(refId);
    }

    /** Max melee reach (ft) across the enemy's attacks, default 5. */
    public static int enemyReachFeet(Enemy e) {
        int reach = GridService.FEET_PER_SQUARE; // 5 ft default
        for (com.dungeon.master.model.dto.MonsterAttack a : e.getAttacks()) {
            if ("MELEE".equalsIgnoreCase(a.kind()) && a.reach() != null) {
                reach = Math.max(reach, a.reach());
            }
        }
        return reach;
    }
}
