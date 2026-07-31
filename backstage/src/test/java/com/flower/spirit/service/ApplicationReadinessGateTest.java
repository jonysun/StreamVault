package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.flower.spirit.database.sqlite.SqliteRuntimeVerifier;

class ApplicationReadinessGateTest {

    @Test
    void startsBlockedUntilReadyEventCompletesDatabaseCheck() {
        SqliteRuntimeVerifier verifier = mock(SqliteRuntimeVerifier.class);
        ApplicationReadinessGate gate = new ApplicationReadinessGate(Optional.of(verifier));

        assertThat(gate.isReady()).isFalse();
        assertThat(gate.mayRun().allowed()).isFalse();

        gate.onApplicationReady();

        assertThat(gate.isReady()).isTrue();
        assertThat(gate.snapshot().state()).isEqualTo(ApplicationReadinessGate.State.READY);
    }

    @Test
    void remainsBlockedWhenDatabaseCheckFails() {
        SqliteRuntimeVerifier verifier = mock(SqliteRuntimeVerifier.class);
        doThrow(new IllegalStateException("broken index")).when(verifier).verify();
        ApplicationReadinessGate gate = new ApplicationReadinessGate(Optional.of(verifier));

        gate.onApplicationReady();

        assertThat(gate.isReady()).isFalse();
        assertThat(gate.snapshot().state()).isEqualTo(ApplicationReadinessGate.State.BLOCKED);
        assertThat(gate.snapshot().reason()).contains("broken index");
    }
}
