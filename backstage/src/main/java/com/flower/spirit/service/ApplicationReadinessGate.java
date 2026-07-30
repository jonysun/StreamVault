package com.flower.spirit.service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import com.flower.spirit.database.sqlite.SqliteRuntimeVerifier;

@Service
public class ApplicationReadinessGate {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationReadinessGate.class);
    private final Optional<SqliteRuntimeVerifier> sqliteRuntimeVerifier;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(
            new Snapshot(State.STARTING, "Application initialization is incomplete", Instant.now()));

    public ApplicationReadinessGate(Optional<SqliteRuntimeVerifier> sqliteRuntimeVerifier) {
        this.sqliteRuntimeVerifier = sqliteRuntimeVerifier;
    }

    @Order(1000)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        transition(State.CHECKING_DATABASE, "Database readiness check is running");
        try {
            sqliteRuntimeVerifier.ifPresent(SqliteRuntimeVerifier::verify);
            transition(State.READY, "Application and database are ready");
            logger.info("[Readiness] state=READY");
        } catch (RuntimeException error) {
            String reason = rootMessage(error);
            transition(State.BLOCKED, reason);
            logger.error("[Readiness] state=BLOCKED reason={}", reason, error);
        }
    }

    public boolean isReady() {
        return snapshot.get().state() == State.READY;
    }

    public PauseDecision mayRun() {
        Snapshot current = snapshot.get();
        return current.state() == State.READY
                ? PauseDecision.permit()
                : PauseDecision.paused("application.readiness", current.reason());
    }

    public Snapshot snapshot() {
        return snapshot.get();
    }

    private void transition(State state, String reason) {
        snapshot.set(new Snapshot(state, reason, Instant.now()));
    }

    private String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() == null || root.getMessage().isBlank()
                ? root.getClass().getSimpleName() : root.getMessage();
    }

    public enum State {
        STARTING,
        CHECKING_DATABASE,
        READY,
        BLOCKED
    }

    public record Snapshot(State state, String reason, Instant changedAt) {
    }
}
