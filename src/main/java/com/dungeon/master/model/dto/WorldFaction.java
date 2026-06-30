package com.dungeon.master.model.dto;

/**
 * A "living magnet" faction in an authored {@link com.dungeon.master.model.entity.World}. The 5E
 * worldbuilding guidance gives each faction a goal, a resource it wields, and a pressure pushing it
 * to act — those three levers are what make a faction pull the party toward the next development.
 * Persisted as JSON on the world and rendered into the session world-setting text.
 *
 * @param name        the faction's name (e.g. "The Tidewardens")
 * @param goal        what it is trying to achieve
 * @param resource    what gives it power (armies, gold, secrets, magic)
 * @param pressure    the tension forcing it to move now
 * @param description optional extra flavour
 */
public record WorldFaction(String name, String goal, String resource, String pressure,
                           String description) {
}
