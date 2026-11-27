package com.universium.webcraft.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static com.universium.webcraft.core.AccountLinks.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

final class AccountLinksTest {
    private final AtomicLong now = new AtomicLong();
    private final UUID player = UUID.randomUUID();
    private final UUID attempt = UUID.randomUUID();
    private final AccountLinks links = new AccountLinks(now::get, 3);

    @Test
    void replacesUnusedCodesAndRetainsTheOriginalReceipt() {
        String oldCode = links.issue(player, "OldName");
        String code = links.issue(player, "NewName");
        assertNotEquals(oldCode, code);
        assertEquals(INVALID_CODE, links.redeem(oldCode, attempt).outcome());
        tick(Duration.ofMinutes(4));
        var result = links.redeem(code, attempt);
        assertEquals(REDEEMED, result.outcome());
        assertEquals(new AccountLinks.Player(player, "NewName"), result.player());

        String next = links.issue(player, "LatestName");
        tick(Duration.ofMinutes(1));
        assertEquals(result, links.redeem(code, attempt));
        assertEquals(INVALID_CODE, links.redeem(code, UUID.randomUUID()).outcome());
        assertEquals(ATTEMPT_CONFLICT, links.redeem(next, attempt).outcome());
        assertEquals(REDEEMED, links.redeem(next, UUID.randomUUID()).outcome());

        tick(Duration.ofMinutes(4));
        assertEquals(INVALID_CODE, links.redeem(code, attempt).outcome());
    }

    @Test
    void expiresUnusedCodesAndKeepsReceiptsAtCapacity() {
        var limited = new AccountLinks(now::get, 2);
        String used = limited.issue(player, "Player");
        assertEquals(REDEEMED, limited.redeem(used, attempt).outcome());
        UUID second = UUID.randomUUID();
        limited.issue(second, "Second");
        assertNull(limited.issue(player, "Player"));
        String replacement = limited.issue(second, "Second");
        assertNotNull(replacement);
        assertEquals(REDEEMED, limited.redeem(used, attempt).outcome());

        tick(AccountLinks.CODE_LIFETIME);
        assertEquals(INVALID_CODE, limited.redeem(replacement, UUID.randomUUID()).outcome());
        assertNotNull(limited.issue(player, "Player"));
        assertEquals(INVALID_CODE, limited.redeem(used, attempt).outcome());
    }

    @Test
    void concurrentAttemptsCannotBothConsumeTheCode() throws Exception {
        String code = links.issue(player, "Player");
        UUID otherAttempt = UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> {
                start.await();
                return links.redeem(code, attempt).outcome();
            });
            var second = workers.submit(() -> {
                start.await();
                return links.redeem(code, otherAttempt).outcome();
            });
            start.countDown();
            var outcomes = List.of(first.get(), second.get());
            assertTrue(outcomes.contains(REDEEMED));
            assertTrue(outcomes.contains(INVALID_CODE));
        }
    }

    @Test
    void capsNewAttemptsAcrossCodesAndStillAllowsSuccessfulRetries() {
        String used = links.issue(player, "Player");
        var result = links.redeem(used, attempt);
        String next = links.issue(player, "Player");
        for (int index = 1; index < AccountLinks.ATTEMPTS_PER_MINUTE; index++) {
            assertEquals(INVALID_CODE, links.redeem("unknown", UUID.randomUUID()).outcome());
        }
        UUID nextAttempt = UUID.randomUUID();
        assertEquals(RATE_LIMITED, links.redeem(next, nextAttempt).outcome());
        assertEquals(result, links.redeem(used, attempt));
        tick(Duration.ofMinutes(1));
        assertEquals(REDEEMED, links.redeem(next, nextAttempt).outcome());
    }

    private void tick(Duration elapsed) {
        now.addAndGet(elapsed.toNanos());
    }
}
