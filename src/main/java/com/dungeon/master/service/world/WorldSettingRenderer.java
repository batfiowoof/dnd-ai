package com.dungeon.master.service.world;

import com.dungeon.master.model.dto.CustomMonster;
import com.dungeon.master.model.dto.WorldFaction;
import com.dungeon.master.model.dto.WorldNpc;
import com.dungeon.master.model.dto.WorldRegion;
import com.dungeon.master.model.dto.WorldSubregion;
import com.dungeon.master.model.entity.World;

/**
 * Renders a structured {@link World} into the single markdown {@code worldSetting} string a session
 * carries, so the existing DM prompt + RAG context path (which reads {@code session.worldSetting})
 * keeps working unchanged. Milestones and custom-monster stat blocks are handled separately (they
 * become leveling gates and combat data), so only their narrative presence is summarized here.
 */
public final class WorldSettingRenderer {

    private WorldSettingRenderer() {}

    public static String render(World w) {
        StringBuilder sb = new StringBuilder();

        sb.append("# ").append(safe(w.getName())).append("\n");
        if (notBlank(w.getTagline())) {
            sb.append("*").append(w.getTagline().trim()).append("*\n");
        }
        if (notBlank(w.getTone()) || notBlank(w.getMagicLevel())) {
            sb.append("\n");
            if (notBlank(w.getTone())) sb.append("**Tone:** ").append(w.getTone().trim()).append("  \n");
            if (notBlank(w.getMagicLevel())) {
                sb.append("**Magic:** ").append(w.getMagicLevel().trim()).append("  \n");
            }
        }
        if (notBlank(w.getOverview())) {
            sb.append("\n").append(w.getOverview().trim()).append("\n");
        }

        if (w.getRegions() != null && !w.getRegions().isEmpty()) {
            sb.append("\n## Regions\n");
            for (WorldRegion r : w.getRegions()) {
                sb.append("- **").append(safe(r.name())).append("**");
                if (notBlank(r.type())) sb.append(" (").append(r.type().trim()).append(")");
                if (notBlank(r.description())) sb.append(" — ").append(r.description().trim());
                sb.append("\n");
                if (r.subregions() != null) {
                    for (WorldSubregion s : r.subregions()) {
                        if (s == null || !notBlank(s.name())) continue;
                        sb.append("  - **").append(s.name().trim()).append("**");
                        if (notBlank(s.type())) sb.append(" (").append(s.type().trim()).append(")");
                        if (notBlank(s.description())) sb.append(" — ").append(s.description().trim());
                        sb.append("\n");
                    }
                }
            }
        }

        if (w.getFactions() != null && !w.getFactions().isEmpty()) {
            sb.append("\n## Factions\n");
            for (WorldFaction f : w.getFactions()) {
                sb.append("- **").append(safe(f.name())).append("**");
                if (notBlank(f.description())) sb.append(" — ").append(f.description().trim());
                sb.append("\n");
                appendLever(sb, "Goal", f.goal());
                appendLever(sb, "Resource", f.resource());
                appendLever(sb, "Pressure", f.pressure());
            }
        }

        if (w.getNpcs() != null && !w.getNpcs().isEmpty()) {
            sb.append("\n## Key NPCs\n");
            for (WorldNpc n : w.getNpcs()) {
                sb.append("- **").append(safe(n.name())).append("**");
                if (notBlank(n.role())) sb.append(", ").append(n.role().trim());
                if (notBlank(n.race())) sb.append(" (").append(n.race().trim()).append(")");
                String place = npcPlace(n);
                if (notBlank(place)) sb.append(" — at ").append(place);
                if (notBlank(n.bond())) sb.append("; ").append(n.bond().trim());
                if (notBlank(n.description())) sb.append(". ").append(n.description().trim());
                sb.append("\n");
            }
        }

        if (w.getCustomMonsters() != null && !w.getCustomMonsters().isEmpty()) {
            sb.append("\n## Notable Creatures\n");
            for (CustomMonster m : w.getCustomMonsters()) {
                sb.append("- **").append(safe(m.name())).append("**");
                if (notBlank(m.type())) sb.append(" (").append(m.type().trim()).append(")");
                sb.append("\n");
            }
        }

        return sb.toString().trim();
    }

    /**
     * A human-readable placement for an NPC, most-specific first: "Subregion, in Region (specific spot)".
     * Falls back to the free-text {@code location} when no structured region is set.
     */
    private static String npcPlace(WorldNpc n) {
        StringBuilder sb = new StringBuilder();
        if (notBlank(n.subregion())) {
            sb.append(n.subregion().trim());
            if (notBlank(n.region())) sb.append(", in ").append(n.region().trim());
        } else if (notBlank(n.region())) {
            sb.append(n.region().trim());
        }
        if (notBlank(n.location())) {
            sb.append(sb.length() > 0 ? " (" + n.location().trim() + ")" : n.location().trim());
        }
        return sb.toString();
    }

    private static void appendLever(StringBuilder sb, String label, String value) {
        if (notBlank(value)) {
            sb.append("  - ").append(label).append(": ").append(value.trim()).append("\n");
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
