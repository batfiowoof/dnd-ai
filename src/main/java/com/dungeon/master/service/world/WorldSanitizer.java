package com.dungeon.master.service.world;

import com.dungeon.master.model.dto.CustomMonster;
import com.dungeon.master.model.dto.InventoryItem;
import com.dungeon.master.model.dto.Milestone;
import com.dungeon.master.model.dto.MonsterAttack;
import com.dungeon.master.model.dto.MonsterTemplate;
import com.dungeon.master.model.dto.Quest;
import com.dungeon.master.model.dto.QuestDispositionShift;
import com.dungeon.master.model.dto.QuestObjective;
import com.dungeon.master.model.dto.QuestReward;
import com.dungeon.master.model.dto.WorldFaction;
import com.dungeon.master.model.dto.WorldNpc;
import com.dungeon.master.model.dto.WorldRegion;
import com.dungeon.master.model.dto.WorldSubregion;
import com.dungeon.master.model.enums.DispositionBand;
import com.dungeon.master.model.enums.ItemKind;
import com.dungeon.master.model.enums.QuestStatus;
import com.dungeon.master.model.enums.QuestType;
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

    /**
     * Keep only quests with a non-blank key and title, dedup keys (case-insensitive), and force every
     * engine-owned field back to its authored baseline — objectives {@code completed=false}, and the
     * initial {@code status} derived from whether the quest has prerequisites ({@code LOCKED} if so, else
     * {@code AVAILABLE}). Cleans nested objectives, reward, and disposition shifts. The single source of
     * truth for quest normalization across worlds and sessions, mirroring {@link #normalizeMilestones}.
     * Prerequisite / milestone / NPC references are not validated against the world set here — they are
     * resolved by name/key at runtime (same rationale as region connections).
     */
    public List<Quest> normalizeQuests(List<Quest> requested) {
        List<Quest> out = new ArrayList<>();
        if (requested == null) {
            return out;
        }
        Set<String> seenKeys = new HashSet<>();
        for (Quest q : requested) {
            if (q == null || isBlank(q.key()) || isBlank(q.title())) {
                continue;
            }
            String key = q.key().trim();
            if (!seenKeys.add(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            List<String> prereqs = cleanQuestKeyRefs(q.prerequisiteKeys(), key);
            QuestType type = q.type() == null ? QuestType.SIDE : q.type();
            QuestStatus status = prereqs.isEmpty() ? QuestStatus.AVAILABLE : QuestStatus.LOCKED;
            out.add(new Quest(key, q.title().trim(), trimOrEmpty(q.summary()), type, prereqs,
                    cleanObjectives(q.objectives()), trimOrEmpty(q.twist()), trimOrEmpty(q.twistTrigger()),
                    cleanReward(q.reward()), trimOrEmpty(q.completionImpact()), trimOrEmpty(q.failureImpact()),
                    cleanDispositionShifts(q.dispositionShifts()), status));
        }
        return out;
    }

    /** Trim, drop blanks, drop the quest's own key, and de-duplicate a list of quest-key references. */
    private static List<String> cleanQuestKeyRefs(List<String> keys, String selfKey) {
        List<String> out = new ArrayList<>();
        if (keys == null) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        String self = selfKey.toLowerCase(Locale.ROOT);
        for (String k : keys) {
            if (isBlank(k)) {
                continue;
            }
            String trimmed = k.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (!lower.equals(self) && seen.add(lower)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** Keep objectives with a description; force {@code completed=false}; backfill a stable kebab key. */
    private static List<QuestObjective> cleanObjectives(List<QuestObjective> objectives) {
        List<QuestObjective> out = new ArrayList<>();
        if (objectives == null) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (QuestObjective o : objectives) {
            if (o == null || isBlank(o.description())) {
                continue;
            }
            String key = isBlank(o.key()) ? kebab(o.description()) : kebab(o.key());
            if (isBlank(key)) {
                key = "objective-" + (out.size() + 1);
            }
            if (!seen.add(key.toLowerCase(Locale.ROOT))) {
                key = key + "-" + (out.size() + 1);
                seen.add(key.toLowerCase(Locale.ROOT));
            }
            out.add(new QuestObjective(key, o.description().trim(), false));
        }
        return out;
    }

    /** Clean a reward: trim its flavour text, keep grantable items, trim the milestone link. Never null. */
    private static QuestReward cleanReward(QuestReward reward) {
        if (reward == null) {
            return new QuestReward("", new ArrayList<>(), null);
        }
        List<InventoryItem> items = new ArrayList<>();
        if (reward.items() != null) {
            for (InventoryItem it : reward.items()) {
                if (it == null || isBlank(it.name())) {
                    continue;
                }
                ItemKind kind = it.kind() == null ? ItemKind.GEAR : it.kind();
                items.add(new InventoryItem(it.name().trim(), Math.max(1, it.qty()), kind, it.equipped()));
            }
        }
        return new QuestReward(trimOrEmpty(reward.description()), items, trimOrNull(reward.milestoneKey()));
    }

    /** Keep disposition shifts naming an NPC; trim the name and clamp the delta into the score range. */
    private static List<QuestDispositionShift> cleanDispositionShifts(List<QuestDispositionShift> shifts) {
        List<QuestDispositionShift> out = new ArrayList<>();
        if (shifts == null) {
            return out;
        }
        for (QuestDispositionShift s : shifts) {
            if (s == null || isBlank(s.npcName())) {
                continue;
            }
            out.add(new QuestDispositionShift(s.npcName().trim(), DispositionBand.clamp(s.delta())));
        }
        return out;
    }

    /** Lower-case kebab-case slug (letters/digits, dash-separated); empty when nothing usable remains. */
    private static String kebab(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    /**
     * Drop regions without a name; trim the rest. Preserves the travel-map overlay: coordinates are
     * clamped into the 0–100 canvas (null left as-is for auto-layout) and connection names are trimmed
     * and de-duplicated (case-insensitive). Connections are NOT validated against the region set here —
     * that happens when the map is built, since an edited region may reference one added in the same save.
     */
    public List<WorldRegion> cleanRegions(List<WorldRegion> regions) {
        List<WorldRegion> out = new ArrayList<>();
        if (regions == null) {
            return out;
        }
        for (WorldRegion r : regions) {
            if (r == null || isBlank(r.name())) {
                continue;
            }
            out.add(new WorldRegion(r.name().trim(), trimOrEmpty(r.type()), trimOrEmpty(r.description()),
                    clampCoord(r.x()), clampCoord(r.y()), cleanConnections(r.connections()),
                    cleanSubregions(r.subregions())));
        }
        return out;
    }

    /**
     * Drop subregions without a name; trim, clamp coords, and de-dup local route names — the same
     * treatment as regions, scoped within a parent. Local connections are likewise left un-validated
     * against the sibling set (resolved when the region's mini-map is built).
     */
    public List<WorldSubregion> cleanSubregions(List<WorldSubregion> subregions) {
        List<WorldSubregion> out = new ArrayList<>();
        if (subregions == null) {
            return out;
        }
        for (WorldSubregion s : subregions) {
            if (s == null || isBlank(s.name())) {
                continue;
            }
            // Subregion positions are never user-authored, so drop any coordinates (including clustered
            // ones the AI may have invented) — the travel map always auto-lays-out the local mini-map.
            out.add(new WorldSubregion(s.name().trim(), trimOrEmpty(s.type()), trimOrEmpty(s.description()),
                    null, null, cleanConnections(s.connections())));
        }
        return out;
    }

    /** Clamp an optional map coordinate into [0, 100]; null stays null (auto-layout). */
    private static Double clampCoord(Double v) {
        if (v == null) {
            return null;
        }
        return Math.max(0.0, Math.min(100.0, v));
    }

    /** Trim, drop blanks, and de-duplicate (case-insensitive) a region's route connection names. */
    private static List<String> cleanConnections(List<String> connections) {
        if (connections == null) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String c : connections) {
            if (isBlank(c)) {
                continue;
            }
            String trimmed = c.trim();
            if (seen.add(trimmed.toLowerCase(Locale.ROOT))) {
                out.add(trimmed);
            }
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

    /**
     * Drop NPCs without a name; trim the rest. The structured {@code region}/{@code subregion} tags are
     * trimmed but NOT validated against the world's region set here (same rationale as region
     * connections — an NPC may reference a region added in the same save).
     */
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
                    trimOrEmpty(n.region()), trimOrEmpty(n.subregion()), trimOrEmpty(n.location()),
                    trimOrEmpty(n.bond()), trimOrEmpty(n.description()),
                    DispositionBand.clamp(n.disposition() == null ? 0 : n.disposition())));
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
