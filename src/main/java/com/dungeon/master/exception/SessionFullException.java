package com.dungeon.master.exception;

public class SessionFullException extends RuntimeException {

    public SessionFullException(String message) {
        super(message);
    }
}
