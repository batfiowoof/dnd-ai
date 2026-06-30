package com.dungeon.master.exception;

/**
 * Thrown when a world cannot be found for the requesting owner. Lookups are scoped by owner, so this
 * also covers the "exists but not yours" case — we deliberately return 404 (not 403) so we never
 * reveal the existence of another user's world. Mirrors {@link CharacterNotFoundException}.
 */
public class WorldNotFoundException extends RuntimeException {

    public WorldNotFoundException(String message) {
        super(message);
    }
}
