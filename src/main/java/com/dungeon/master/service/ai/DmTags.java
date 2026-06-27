package com.dungeon.master.service.ai;

import com.dungeon.master.model.enums.RollMode;

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
 *       request an ability check (the {@code player=} part is only used in collaborative rounds).
 *       An optional {@code mode=ADVANTAGE|DISADVANTAGE} lets the DM grant a situational edge or
 *       hindrance, applied authoritatively by the engine when the player rolls.</li>
 *   <li>{@code [[INSPIRATION: player=Name reason="great roleplay"]]} — award Inspiration to a
 *       player, which they may later spend on a roll for advantage.</li>
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
    private static final Pattern INSPIRATION =
            Pattern.compile("\\[\\[\\s*INSPIRATION\\s*:\\s*(.*?)\\s*\\]\\]",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern GROUP =
            Pattern.compile("\\[\\[\\s*GROUP\\s*:\\s*(.*?)\\s*\\]\\]",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CONTEST =
            Pattern.compile("\\[\\[\\s*CONTEST\\s*:\\s*(.*?)\\s*\\]\\]",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** Any complete directive tag, anywhere in the text — removed inline so prose is preserved. */
    private static final Pattern ANY_TAG =
            Pattern.compile("\\[\\[\\s*(?:ENCOUNTER|ROLL|INSPIRATION|GROUP|CONTEST)\\b.*?\\]\\]",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** A trailing, never-closed directive tag (e.g. {@code [[ENCOUNTER: GOBLIN} with no {@code ]]}). */
    private static final Pattern TRAILING_PARTIAL =
            Pattern.compile("\\[\\[\\s*(?:ENCOUNTER|ROLL|INSPIRATION|GROUP|CONTEST)\\b[^\\]]*$",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern KV =
            Pattern.compile("(\\w+)\\s*=\\s*(\"[^\"]*\"|'[^']*'|\\S+)");

    /**
     * An LLM-requested ability check. {@code player} may be null (single-player path). {@code mode}
     * is the DM's situational ADVANTAGE/DISADVANTAGE (NORMAL when none/unrecognized).
     */
    public record RollTag(String player, String ability, int dc, String skill, String reason,
                          RollMode mode) {}

    /** An LLM-awarded Inspiration. {@code player} may be null (single-player path). */
    public record InspirationTag(String player, String reason) {}

    /**
     * An LLM-requested GROUP check imposed on the whole party at once: every player rolls the same
     * {@code ability}/{@code dc}/{@code skill} and the party succeeds iff at least half succeed.
     */
    public record GroupTag(String ability, int dc, String skill, String reason) {}

    /**
     * An LLM-requested CONTEST: one player ({@code actor}) rolls {@code actorAbility}/{@code
     * actorSkill} opposed by an NPC. {@code targetMod} is the NPC's flat modifier (nullable — the
     * engine falls back to a difficulty-banded value); {@code targetLabel} names the opposed party.
     */
    public record ContestTag(String actor, String actorAbility, String actorSkill,
                             Integer targetMod, String targetLabel, String reason) {}

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
            RollMode mode = RollMode.NORMAL;
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
                    case "mode" -> mode = parseMode(v);
                    default -> { /* ignore unknown keys */ }
                }
            }
            if (ability != null) {
                rolls.add(new RollTag(player, ability, dc, skill, reason, mode));
            }
        }
        return rolls;
    }

    /** Parse every {@code [[INSPIRATION:…]]} tag. {@code player} may be null. */
    public static List<InspirationTag> parseInspiration(String text) {
        List<InspirationTag> awards = new ArrayList<>();
        if (text == null) return awards;
        Matcher m = INSPIRATION.matcher(text);
        while (m.find()) {
            String body = m.group(1);
            String player = null, reason = null;
            Matcher kv = KV.matcher(body);
            while (kv.find()) {
                String k = kv.group(1).toLowerCase(Locale.ROOT);
                String v = unquote(kv.group(2));
                switch (k) {
                    case "player" -> player = v;
                    case "reason" -> reason = v;
                    default -> { /* ignore unknown keys */ }
                }
            }
            awards.add(new InspirationTag(player, reason));
        }
        return awards;
    }

    /**
     * Parse the first valid {@code [[GROUP:…]]} tag (a party-wide check is requested at most once
     * per reply). Returns null when no tag is present or the ability is missing/garbled.
     */
    public static GroupTag parseGroup(String text) {
        if (text == null) return null;
        Matcher m = GROUP.matcher(text);
        while (m.find()) {
            String body = m.group(1);
            String ability = null, skill = null, reason = null;
            int dc = 0;
            Matcher kv = KV.matcher(body);
            while (kv.find()) {
                String k = kv.group(1).toLowerCase(Locale.ROOT);
                String v = unquote(kv.group(2));
                switch (k) {
                    case "ability" -> ability = v.toUpperCase(Locale.ROOT);
                    case "dc" -> dc = parseIntSafe(v);
                    case "skill" -> skill = v;
                    case "reason" -> reason = v;
                    default -> { /* ignore unknown keys */ }
                }
            }
            if (ability != null) {
                return new GroupTag(ability, dc, skill, reason);
            }
        }
        return null;
    }

    /** Parse every valid {@code [[CONTEST:…]]} tag. A tag missing actor or actorAbility is skipped. */
    public static List<ContestTag> parseContest(String text) {
        List<ContestTag> contests = new ArrayList<>();
        if (text == null) return contests;
        Matcher m = CONTEST.matcher(text);
        while (m.find()) {
            String body = m.group(1);
            String actor = null, actorAbility = null, actorSkill = null, targetLabel = null, reason = null;
            Integer targetMod = null;
            Matcher kv = KV.matcher(body);
            while (kv.find()) {
                String k = kv.group(1).toLowerCase(Locale.ROOT);
                String v = unquote(kv.group(2));
                switch (k) {
                    case "actor" -> actor = v;
                    case "actorability" -> actorAbility = v.toUpperCase(Locale.ROOT);
                    case "actorskill" -> actorSkill = v;
                    case "targetmod" -> targetMod = parseIntOrNull(v);
                    case "targetlabel" -> targetLabel = v;
                    case "reason" -> reason = v;
                    default -> { /* ignore unknown keys */ }
                }
            }
            if (actor != null && actorAbility != null) {
                contests.add(new ContestTag(actor, actorAbility, actorSkill, targetMod, targetLabel, reason));
            }
        }
        return contests;
    }

    /** Map a {@code mode=} value to a {@link RollMode}; anything unrecognized → NORMAL. */
    private static RollMode parseMode(String v) {
        if (v == null) return RollMode.NORMAL;
        try {
            return RollMode.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return RollMode.NORMAL;
        }
    }

    /**
     * Remove directive tags and tidy whitespace, leaving the surrounding prose intact. Every
     * COMPLETE tag occurrence of any of the five types is removed inline (so a tag emitted
     * mid-sentence no longer truncates the prose after it), then any trailing UNCLOSED tag the
     * model began but never finished (e.g. {@code [[ENCOUNTER: GOBLIN} with no {@code ]]}) is
     * stripped to end. Parsing ({@link #parseEncounter} etc.) still runs on the RAW assembled
     * text in the consumer, so removing tags here does not affect the engine's reaction to them.
     */
    public static String strip(String text) {
        if (text == null) return "";
        String cleaned = ANY_TAG.matcher(text).replaceAll("");
        cleaned = TRAILING_PARTIAL.matcher(cleaned).replaceAll("");
        // Collapse whitespace left behind by the removals and trim.
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

    /** Like {@link #parseIntSafe} but null (not 0) on a missing/garbled value, so a CONTEST's
     * absent {@code targetMod} can trigger the difficulty-banded fallback rather than rolling +0. */
    private static Integer parseIntOrNull(String s) {
        if (s == null) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
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
