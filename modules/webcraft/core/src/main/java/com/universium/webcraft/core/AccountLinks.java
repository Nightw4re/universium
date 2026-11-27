package com.universium.webcraft.core;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

final class AccountLinks {
    static final Duration CODE_LIFETIME = Duration.ofMinutes(5);
    static final Duration RETRY_LIFETIME = Duration.ofMinutes(5);
    static final int ATTEMPTS_PER_MINUTE = 60;
    private static final long RATE_WINDOW = Duration.ofMinutes(1).toNanos();
    private static final int CAPACITY = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();

    record Player(UUID uuid, String name) {}

    enum Outcome { REDEEMED, INVALID_CODE, ATTEMPT_CONFLICT, RATE_LIMITED }

    record Redemption(Outcome outcome, Player player) {
        Redemption(Outcome outcome) {
            this(outcome, null);
        }
    }

    private record Code(Player player, long expiresAt, UUID attemptId) {}

    private final LongSupplier clock;
    private final int capacity;
    private final Map<String, Code> codes = new HashMap<>();
    private final Map<UUID, String> pendingByPlayer = new HashMap<>();
    private final Map<UUID, String> receiptsByAttempt = new HashMap<>();
    private long windowStart;
    private int attempts;

    AccountLinks() {
        this(System::nanoTime, CAPACITY);
    }

    AccountLinks(LongSupplier clock, int capacity) {
        this.clock = clock;
        this.capacity = capacity;
    }

    synchronized String issue(UUID uuid, String name) {
        long now = clock.getAsLong();
        discardExpired(now);
        String previous = pendingByPlayer.get(uuid);
        if (previous == null && codes.size() >= capacity) return null;

        String code;
        var random = new byte[12];
        do {
            RANDOM.nextBytes(random);
            code = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        } while (codes.containsKey(code));

        codes.remove(previous);
        codes.put(code, new Code(new Player(uuid, name), now + CODE_LIFETIME.toNanos(), null));
        pendingByPlayer.put(uuid, code);
        return code;
    }

    synchronized Redemption redeem(String code, UUID attemptId) {
        long now = clock.getAsLong();
        String previousCode = receiptsByAttempt.get(attemptId);
        Code entry = codes.get(code);
        if (code.equals(previousCode) && !expired(entry, now)) {
            return new Redemption(Outcome.REDEEMED, entry.player);
        }
        if (now - windowStart >= RATE_WINDOW || attempts == 0) {
            windowStart = now;
            attempts = 0;
        }
        if (attempts >= ATTEMPTS_PER_MINUTE) return new Redemption(Outcome.RATE_LIMITED);
        attempts++;

        discardExpired(now);
        if (receiptsByAttempt.containsKey(attemptId)) return new Redemption(Outcome.ATTEMPT_CONFLICT);
        if (expired(entry, now) || entry.attemptId != null) return new Redemption(Outcome.INVALID_CODE);

        codes.put(code, new Code(entry.player, now + RETRY_LIFETIME.toNanos(), attemptId));
        pendingByPlayer.remove(entry.player.uuid);
        receiptsByAttempt.put(attemptId, code);
        return new Redemption(Outcome.REDEEMED, entry.player);
    }

    private void discardExpired(long now) {
        codes.values().removeIf(entry -> {
            if (!expired(entry, now)) return false;
            if (entry.attemptId == null) pendingByPlayer.remove(entry.player.uuid);
            else receiptsByAttempt.remove(entry.attemptId);
            return true;
        });
    }

    private static boolean expired(Code entry, long now) {
        return entry == null || now - entry.expiresAt >= 0;
    }
}
