package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.InventoryItem;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D&D 5e coinage helpers. Wealth is stored as a single {@code long} of <b>copper</b> so gp/sp/cp all
 * do exact integer arithmetic (1 gp = 100 cp, 1 sp = 10 cp). SRD equipment prices arrive as free-text
 * strings ("2 GP", "5 SP", "1 CP") and legacy coin loot as inventory items named like "150 GP"; both
 * parse through here into copper, and balances render back out for display.
 *
 * <p>Platinum (1 pp = 1000 cp) and electrum (1 ep = 50 cp) parse for robustness even though the SRD
 * corpus only uses cp/sp/gp; formatting collapses to gp/sp/cp.
 */
public final class MoneyUtil {

    private MoneyUtil() {
    }

    public static final long CP = 1;
    public static final long SP = 10;
    public static final long EP = 50;
    public static final long GP = 100;
    public static final long PP = 1000;

    /** A number (optionally comma-grouped) followed by a coin unit, anywhere in the string. */
    private static final Pattern COIN = Pattern.compile(
            "([\\d,]+)\\s*(pp|gp|ep|sp|cp|platinum|gold(?:\\s+pieces?)?|silver|copper|coins?)",
            Pattern.CASE_INSENSITIVE);

    /** The whole string is <em>only</em> a coin amount (used to tell a coin stack from a real item). */
    private static final Pattern COIN_ONLY = Pattern.compile(
            "^\\s*[\\d,]+\\s*(pp|gp|ep|sp|cp|platinum|gold(?:\\s+pieces?)?|silver|copper|coins?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Parse a coin string ("150 GP", "5 sp", "1 CP") into copper. Returns {@code -1} when no
     * coin amount is present so callers can distinguish "unpriced" from a genuine zero.
     */
    public static long parseCoins(String text) {
        if (text == null) {
            return -1;
        }
        Matcher m = COIN.matcher(text);
        if (!m.find()) {
            return -1;
        }
        long amount;
        try {
            amount = Long.parseLong(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
        return amount * unitValue(m.group(2).toLowerCase(Locale.ROOT));
    }

    /** True when the name is purely a coin amount (e.g. "150 GP") rather than a real item. */
    public static boolean isCoinName(String name) {
        return name != null && COIN_ONLY.matcher(name).matches();
    }

    /**
     * Copper value of a stack that is purely coin (name × qty), or {@code -1} if the item is not coin.
     * Used to route coin loot into the numeric purse instead of the inventory list.
     */
    public static long coinValueOf(InventoryItem item) {
        if (item == null || !isCoinName(item.name())) {
            return -1;
        }
        long each = parseCoins(item.name());
        return each < 0 ? -1 : each * Math.max(1, item.qty());
    }

    /** Render copper as "12 gp 4 sp 2 cp", dropping zero denominations; "0 cp" when empty. */
    public static String format(long copper) {
        long c = Math.max(0, copper);
        long gp = c / GP;
        long sp = (c % GP) / SP;
        long cp = c % SP;
        StringBuilder b = new StringBuilder();
        if (gp > 0) {
            b.append(gp).append(" gp");
        }
        if (sp > 0) {
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(sp).append(" sp");
        }
        if (cp > 0) {
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(cp).append(" cp");
        }
        return b.length() == 0 ? "0 cp" : b.toString();
    }

    private static long unitValue(String unit) {
        return switch (unit) {
            case "pp", "platinum" -> PP;
            case "gp", "gold", "gold piece", "gold pieces", "coin", "coins" -> GP;
            case "ep" -> EP;
            case "sp", "silver" -> SP;
            default -> CP; // cp, copper
        };
    }
}
