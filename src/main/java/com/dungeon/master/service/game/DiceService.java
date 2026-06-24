package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.enums.RollMode;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authoritative dice roller. Parses standard notation ("1d20+5", "2d6", "d8-1")
 * and resolves it with {@link SecureRandom}. This is the single source of truth
 * for every random number in the game — the LLM never rolls.
 */
@Service
public class DiceService {

    /** e.g. "2d6+3", "d20", "1d8 - 1" (whitespace tolerant, case-insensitive). */
    private static final Pattern NOTATION =
            Pattern.compile("^\\s*(\\d*)\\s*[dD]\\s*(\\d+)\\s*([+-]\\s*\\d+)?\\s*$");

    private static final int MAX_DICE = 100;
    private static final int MAX_SIDES = 1000;

    private final SecureRandom random = new SecureRandom();

    public DiceRollResult roll(String notation) {
        return roll(notation, RollMode.NORMAL);
    }

    /**
     * Roll a dice expression. For ADVANTAGE/DISADVANTAGE the whole set is rolled
     * twice and the higher/lower-summing set is kept (the other is reported as
     * {@code discarded}).
     */
    public DiceRollResult roll(String notation, RollMode mode) {
        if (notation == null) {
            throw new IllegalArgumentException("Dice notation is required");
        }
        Matcher m = NOTATION.matcher(notation);
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid dice notation: " + notation);
        }

        int count = m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
        int sides = Integer.parseInt(m.group(2));
        int modifier = m.group(3) == null ? 0
                : Integer.parseInt(m.group(3).replaceAll("\\s", ""));

        if (count < 1 || count > MAX_DICE) {
            throw new IllegalArgumentException("Dice count must be 1.." + MAX_DICE);
        }
        if (sides < 2 || sides > MAX_SIDES) {
            throw new IllegalArgumentException("Dice sides must be 2.." + MAX_SIDES);
        }

        List<Integer> first = rollSet(count, sides);
        List<Integer> faces = first;
        List<Integer> discarded = null;

        if (mode != RollMode.NORMAL) {
            List<Integer> second = rollSet(count, sides);
            int sumFirst = first.stream().mapToInt(Integer::intValue).sum();
            int sumSecond = second.stream().mapToInt(Integer::intValue).sum();
            boolean keepFirst = mode == RollMode.ADVANTAGE
                    ? sumFirst >= sumSecond
                    : sumFirst <= sumSecond;
            faces = keepFirst ? first : second;
            discarded = keepFirst ? second : first;
        }

        int total = faces.stream().mapToInt(Integer::intValue).sum() + modifier;

        boolean singleD20 = count == 1 && sides == 20;
        boolean crit = singleD20 && faces.get(0) == 20;
        boolean fumble = singleD20 && faces.get(0) == 1;

        return new DiceRollResult(
                normalize(count, sides, modifier),
                count, sides, modifier, mode,
                faces, discarded, total, crit, fumble);
    }

    private List<Integer> rollSet(int count, int sides) {
        List<Integer> faces = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            faces.add(random.nextInt(sides) + 1);
        }
        return faces;
    }

    private String normalize(int count, int sides, int modifier) {
        StringBuilder sb = new StringBuilder().append(count).append('d').append(sides);
        if (modifier > 0) sb.append('+').append(modifier);
        else if (modifier < 0) sb.append(modifier);
        return sb.toString();
    }
}
