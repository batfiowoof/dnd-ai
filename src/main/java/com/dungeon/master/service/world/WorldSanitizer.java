package com.dungeon.master.service.world;

import com.dungeon.master.model.dto.CustomMonster;
import com.dungeon.master.model.dto.Milestone;
import com.dungeon.master.model.dto.MonsterAttack;
import com.dungeon.master.model.dto.MonsterTemplate;
import com.dungeon.master.model.dto.WorldFaction;
import com.dungeon.master.model.dto.WorldNpc;
import com.dungeon.master.model.dto.WorldRegion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Server-side cleaning for authored world content, shared by {@code WorldService} (on save) and
 * {@code GameSessionService} (milestones on session create). Drops blank/incomplete entries, forces
 * invariants the client must never own (milestone {@code completed=false}), and namespaces + validates
 * custom monsters so every persisted stat block is combat-legal — mirroring the "enough to fight with"
 * gate in {@code MonsterCatalog}.
 */
@Component
public class WorldSanitizer {

    /** Prefix that namespaces homebrew monster keys so they can't collide with SRD catalogue keys. */
    public static final String CUSTOM_KEY_PREFIX = "CUSTOM_";

    /**
     * Keep only milestones with a non-blank key and title, force {@code completed=false}, trim, and
     * drop duplicate keys (case-insensitive). Empty list when none supplied. Single source of truth
     * for milestone normalization across worlds and sessions.
     */
    public List<Milestone> normalizeMilestones(List<Milestone> requested) {
        List<Milestone> out = new ArrayList<>();
        if (requested == null) {
            return out;
        }
        Set<String> seenKeys = new HashSet<>();
        for (Milestone m : requested) {
            if (m == null || isBlank(m.key()) || isBlank(m.title())) {
                continue;
            }
            String key = m.key().trim();
            if (seenKeys.add(key.toLowerCase(Locale.ROOT))) {
                out.add(new Milestone(key, m.title().trim(),
                        m.description() == null ? "" : m.description().trim(), false));
            }
        }
        return out;
    }

    /** Drop regions without a name; trim the rest. */
    public List<WorldRegion> cleanRegions(List<WorldRegion> regions) {
        List<WorldRegion> out = new ArrayList<>();
        if (regions == null) {
            return out;
        }
        for (WorldRegion r : regions) {
            if (r == null || isBlank(r.name())) {
                continue;
            }
            out.add(new WorldRegion(r.name().trim(), trimOrEmpty(r.type()), trimOrEmpty(r.description())));
        }
        return out;
    }

    /** Drop factions without a name; trim the rest. */
    public List<WorldFaction> cleanFactions(List<WorldFaction> factions) {
        List<WorldFaction> out = new ArrayList<>();
        if (factions == null) {
            return out;
        }
        for (WorldFaction f : factions) {
            if (f == null || isBlank(f.name())) {
                continue;
            }
            out.add(new WorldFaction(f.name().trim(), trimOrEmpty(f.goal()), trimOrEmpty(f.resource()),
                    trimOrEmpty(f.pressure()), trimOrEmpty(f.description())));
        }
        return out;
    }

    /** Drop NPCs without a name; trim the rest. */
    public List<WorldNpc> cleanNpcs(List<WorldNpc> npcs) {
        List<WorldNpc> out = new ArrayList<>();
        if (npcs == null) {
            return out;
        }
        for (WorldNpc n : npcs) {
            if (n == null || isBlank(n.name())) {
                continue;
            }
            out.add(new WorldNpc(n.name().trim(), trimOrEmpty(n.race()), trimOrEmpty(n.role()),
                    trimOrEmpty(n.location()), trimOrEmpty(n.bond()), trimOrEmpty(n.description())));
        }
        return out;
    }

    /**
     * Namespace, validate, and de-duplicate custom monsters. A stat block is kept only if it has a
     * name plus the minimum the combat engine needs — AC, HP, and at least one usable attack — so a
     * persisted custom monster is always playable. Keys are derived from the name when absent, then
     * upper-cased and {@code CUSTOM_}-prefixed; duplicate keys are dropped.
     */
    public List<CustomMonster> sanitizeMonsters(List<CustomMonster> monsters) {
        List<CustomMonster> out = new ArrayList<>();
        if (monsters == null) {
            return out;
        }
        Set<String> seenKeys = new HashSet<>();
        for (CustomMonster m : monsters) {
            if (m == null || isBlank(m.name())) {
                continue;
            }
            List<MonsterAttack> attacks = cleanAttacks(m.attacks());
            if (m.ac() == null || m.hp() == null || attacks.isEmpty()) {
                continue;
            }
            String key = namespaceKey(isBlank(m.key()) ? m.name() : m.key());
            if (!seenKeys.add(key)) {
                continue;
            }
            Map<String, Integer> abilities = m.abilities() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(m.abilities());
            MonsterTemplate.Multiattack multi = m.multiattack();
            if (multi != null && multi.count() <= 1) {
                multi = null;
            }
            out.add(new CustomMonster(key, m.name().trim(), trimOrNull(m.size()), trimOrNull(m.type()),
                    m.cr(), m.ac(), m.hp(), trimOrNull(m.hpDice()),
                    m.speed() == null ? 30 : m.speed(), m.dexMod(), abilities, attacks, multi));
        }
        return out;
    }

    private static List<MonsterAttack> cleanAttacks(List<MonsterAttack> attacks) {
        List<MonsterAttack> out = new ArrayList<>();
        if (attacks == null) {
            return out;
        }
        for (MonsterAttack a : attacks) {
            if (a == null || isBlank(a.name()) || isBlank(a.damageDice())) {
                continue;
            }
            String kind = "RANGED".equalsIgnoreCase(a.kind()) ? "RANGED" : "MELEE";
            out.add(new MonsterAttack(a.name().trim(), kind, a.toHit(), a.reach(), a.range(),
                    a.damageDice().trim(), trimOrEmpty(a.damageType())));
        }
        return out;
    }

    /** Upper-case, replace runs of non-alphanumerics with underscore, and ensure the CUSTOM_ prefix. */
    private static String namespaceKey(String raw) {
        String slug = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) {
            slug = "MONSTER";
        }
        return slug.startsWith(CUSTOM_KEY_PREFIX) ? slug : CUSTOM_KEY_PREFIX + slug;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimOrEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static String trimOrNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
