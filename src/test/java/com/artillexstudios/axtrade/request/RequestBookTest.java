package com.artillexstudios.axtrade.request;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestBookTest {
    private static final long EXPIRY = 1_000L;
    private final UUID alice = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID bob = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID carol = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void expiresAtTheConfiguredBoundaryAndCanBeRequestedAgain() {
        RequestBook book = new RequestBook();

        assertEquals(RequestBook.Result.CREATED, book.request(alice, bob, 1_000L, EXPIRY));
        assertEquals(1, book.incoming(bob, 1_999L, EXPIRY).size());
        assertTrue(book.incoming(bob, 2_000L, EXPIRY).isEmpty());
        assertEquals(RequestBook.Result.CREATED, book.request(alice, bob, 2_000L, EXPIRY));
    }

    @Test
    void denialRemovesTheRequestInsteadOfLeavingAStaleCooldown() {
        RequestBook book = new RequestBook();

        assertEquals(RequestBook.Result.CREATED, book.request(alice, bob, 1_000L, EXPIRY));
        assertTrue(book.deny(bob, alice, 1_001L, EXPIRY));
        assertEquals(RequestBook.Result.CREATED, book.request(alice, bob, 1_002L, EXPIRY));
    }

    @Test
    void acceptanceOnlyConsumesAnIncomingRequestInTheCorrectDirection() {
        RequestBook book = new RequestBook();
        book.request(alice, bob, 1_000L, EXPIRY);

        assertFalse(book.accept(alice, bob, 1_001L, EXPIRY));
        assertTrue(book.accept(bob, alice, 1_001L, EXPIRY));
        assertTrue(book.isEmpty());
    }

    @Test
    void reciprocalRequestAcceptsOneIncomingRequestAndClearsOtherPendingRequests() {
        RequestBook book = new RequestBook();
        book.request(alice, bob, 1_000L, EXPIRY);
        book.request(carol, alice, 1_001L, EXPIRY);

        assertEquals(RequestBook.Result.ACCEPTED, book.request(bob, alice, 1_002L, EXPIRY));
        assertTrue(book.isEmpty());
    }

    @Test
    void disconnectRemovesEveryRequestInvolvingThePlayer() {
        RequestBook book = new RequestBook();
        book.request(alice, bob, 1_000L, EXPIRY);
        book.request(carol, alice, 1_001L, EXPIRY);

        List<RequestBook.Entry> removed = book.removeFor(alice);

        assertEquals(2, removed.size());
        assertTrue(book.isEmpty());
    }
}
