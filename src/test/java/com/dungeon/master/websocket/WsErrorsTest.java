package com.dungeon.master.websocket;

import com.dungeon.master.exception.CharacterNotFoundException;
import com.dungeon.master.exception.NotYourTurnException;
import com.dungeon.master.model.dto.WsError;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WsErrorsTest {

    @Test
    void passesThroughDomainExceptionMessages() {
        WsError error = WsErrors.from(new IllegalStateException("Only the host can start an encounter"));
        assertThat(error.code()).isEqualTo(WsErrors.CODE_REJECTED);
        assertThat(error.message()).isEqualTo("Only the host can start an encounter");
    }

    @Test
    void passesThroughCustomDomainExceptions() {
        WsError error = WsErrors.from(new NotYourTurnException("It is not your turn."));
        assertThat(error.code()).isEqualTo(WsErrors.CODE_REJECTED);
        assertThat(error.message()).isEqualTo("It is not your turn.");
    }

    @Test
    void fillsBlankDomainMessageWithFallback() {
        WsError error = WsErrors.from(new CharacterNotFoundException(null));
        assertThat(error.code()).isEqualTo(WsErrors.CODE_REJECTED);
        assertThat(error.message()).isNotBlank();
    }

    @Test
    void hidesUnexpectedExceptionDetails() {
        WsError error = WsErrors.from(
                new NullPointerException("character.getName() is null at CombatService.java:1234"));
        assertThat(error.code()).isEqualTo(WsErrors.CODE_INTERNAL);
        assertThat(error.message()).isEqualTo("Something went wrong processing that action.");
        assertThat(error.message()).doesNotContain("CombatService");
    }
}
