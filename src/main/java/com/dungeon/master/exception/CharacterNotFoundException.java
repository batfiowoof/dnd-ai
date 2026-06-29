package com.dungeon.master.exception;

/**
 * Thrown when a character cannot be found for the requesting owner. Because lookups are scoped by
 * owner, this also covers the "exists but not yours" case — we deliberately return 404 (not 403) so
 * we never reveal the existence of another user's character.
 */
public class CharacterNotFoundException extends RuntimeException {

    public CharacterNotFoundException(String message) {
        super(message);
    }
}
