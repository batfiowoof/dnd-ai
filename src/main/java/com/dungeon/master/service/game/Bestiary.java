package com.dungeon.master.service.game;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Small seeded bestiary of enemy stat blocks (simplified D&D 5e). */
final class Bestiary {

    private Bestiary() {}

    record Template(String name, int hp, int armorClass, int attackBonus, String damageDice) {}

    private static final Map<String, Template> TEMPLATES = new LinkedHashMap<>();

    static {
        TEMPLATES.put("GOBLIN", new Template("Goblin", 7, 15, 4, "1d6+2"));
        TEMPLATES.put("WOLF", new Template("Wolf", 11, 13, 4, "2d4+2"));
        TEMPLATES.put("BANDIT", new Template("Bandit", 11, 12, 3, "1d6+1"));
        TEMPLATES.put("SKELETON", new Template("Skeleton", 13, 13, 4, "1d6+2"));
        TEMPLATES.put("ORC", new Template("Orc", 15, 13, 5, "1d12+3"));
        TEMPLATES.put("GIANT_RAT", new Template("Giant Rat", 7, 12, 4, "1d4+2"));
    }

    static Template get(String key) {
        Template t = TEMPLATES.get(key == null ? "" : key.toUpperCase(Locale.ROOT).trim());
        if (t == null) {
            throw new IllegalArgumentException("Unknown enemy: " + key);
        }
        return t;
    }
}
