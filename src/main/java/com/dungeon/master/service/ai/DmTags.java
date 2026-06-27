package com.dungeon.master.service.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses (and strips) the structured directive tags the DM may append to a narration:
 * <ul>
 *   <li>{@code [[ENCOUNTER: GOBLIN x2, ORC]]} — start a fight with the listed bestiary keys.</li>
 *   <li>{@code [[ROLL: player=Name ability=DEX dc=15 skill=Acrobatics reason="leap the gap"]]} —
 *       request an ability check (the {@code player=} part is only used in collaborative rounds).</li>
 * </ul>
 * The tags are stripped from the narration before it is persisted/broadcast so players never
 * see the raw markers.
 */
public final class DmTags {

    private DmTags() {}

    private static final Pattern ENCOUNTER =
            Pattern.compile("\\[\\[\\s*ENCOUNTER\\s*:\\s*(.*?)\\s*\\]\\]",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ROLL =
            Pattern.compile("\\[\\[\\s*ROLL\\s*:\\s*(.*?)\\s*\\]\\]",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** First opening directive marker — strip from here to end (tags are emitted last). */
    private static final Pattern OPENING =
            Pattern.compile("\\[\\[\\s*(?:ENCOUNTER|ROLL)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern KV =
            Pattern.compile("(\\w+)\\s*=\\s*(\"[^\"]*\"|'[^']*'|\\S+)");

    /** An LLM-requested ability check. {@code player} may be null (single-player path). */
    public record RollTag(String player, String ability, int dc, String skill, String reason) {}

    /**
     * Expand the first {@code [[ENCOUNTER:…]]} tag into a list of valid bestiary keys (e.g.
     * "GOBLIN x2, ORC" → [GOBLIN, GOBLIN, ORC]). Unknown keys are skipped. Returns empty if no
     * tag or no valid keys.
     */
    public static List<String> parseEncounter(String text, Set<String> validKeys) {
        List<String> keys = new ArrayList<>();
        if (text == null) return keys;
        Matcher m = ENCOUNTER.matcher(text);
        if (!m.find()) return keys;
        for (String part : m.group(1).split(",")) {
            String token = part.trim();
            if (token.isEmpty()) continue;
            // "GOBLIN x2" or "GOBLIN" or "2x GOBLIN"
            int count = 1;
            String keyPart = token;
            Matcher xn = Pattern.compile("(?i)^(.*?)\\s*[x×]\\s*(\\d+)$").matcher(token);
            Matcher nx = Pattern.compile("(?i)^(\\d+)\\s*[x×]\\s*(.*?)$").matcher(token);
            if (xn.matches()) {
                keyPart = xn.group(1).trim();
                count = clampCount(xn.group(2));
            } else if (nx.matches()) {
                count = clampCount(nx.group(1));
                keyPart = nx.group(2).trim();
            }
            String key = keyPart.toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
            if (validKeys.contains(key)) {
                for (int i = 0; i < count; i++) keys.add(key);
            }
        }
        return keys;
    }

    /** Parse every {@code [[ROLL:…]]} tag (collaborative rounds may flag several players). */
    public static List<RollTag> parseRolls(String text) {
        List<RollTag> rolls = new ArrayList<>();
        if (text == null) return rolls;
        Matcher m = ROLL.matcher(text);
        while (m.find()) {
            String body = m.group(1);
            String player = null, ability = null, skill = null, reason = null;
            int dc = 0;
            Matcher kv = KV.matcher(body);
            while (kv.find()) {
                String k = kv.group(1).toLowerCase(Locale.ROOT);
                String v = unquote(kv.group(2));
                switch (k) {
                    case "player" -> player = v;
                    case "ability" -> ability = v.toUpperCase(Locale.ROOT);
                    case "dc" -> dc = parseIntSafe(v);
                    case "skill" -> skill = v;
                    case "reason" -> reason = v;
                    default -> { /* ignore unknown keys */ }
                }
            }
            if (ability != null) {
                rolls.add(new RollTag(player, ability, dc, skill, reason));
            }
        }
        return rolls;
    }

    /**
     * Remove directive tags and tidy whitespace. Cuts from the FIRST opening marker to the end
     * of the text rather than matching a closing {@code ]]} — so a {@code ]]} inside a quoted
     * {@code reason="…"} can't terminate the strip early and leak the tag remainder. Safe because
     * the DM is instructed to emit tags only as the final line(s).
     */
    public static String strip(String text) {
        if (text == null) return "";
        Matcher m = OPENING.matcher(text);
        String cleaned = m.find() ? text.substring(0, m.start()) : text;
        // Collapse trailing whitespace left behind and trim.
        cleaned = cleaned.replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
        return cleaned;
    }

    private static int clampCount(String s) {
        int n = parseIntSafe(s);
        return Math.max(1, Math.min(8, n));
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String unquote(String v) {
        if (v == null || v.length() < 2) return v;
        char c = v.charAt(0);
        if ((c == '"' || c == '\'') && v.charAt(v.length() - 1) == c) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
