package com.dungeon.master.exception;

public class NotYourTurnException extends RuntimeException {

    public NotYourTurnException(String message) {
        super(message);
    }
}
