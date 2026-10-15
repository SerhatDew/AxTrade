package com.artillexstudios.axtrade.request;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Thread-safe request state machine independent from Bukkit player objects.
 *
 * <p>All operations that consume a request use the exact sender/receiver
 * direction. A reciprocal request is the only operation that accepts an
 * incoming request.</p>
 */
public final class RequestBook {
    private final Map<Key, Entry> entries = new HashMap<>();

    public synchronized Result request(UUID sender, UUID receiver, long now, long expiryMillis) {
        purgeExpired(now, expiryMillis);

        if (!consumeIncoming(sender, receiver, now, expiryMillis).isEmpty()) {
            return Result.ACCEPTED;
        }

        if (entries.containsKey(new Key(sender, receiver))) {
            return Result.ALREADY_SENT;
        }

        entries.put(new Key(sender, receiver), new Entry(sender, receiver, now));
        return Result.CREATED;
    }

    public synchronized boolean accept(UUID receiver, UUID sender, long now, long expiryMillis) {
        return !consumeIncoming(receiver, sender, now, expiryMillis).isEmpty();
    }

    public synchronized List<Entry> consumeIncoming(UUID receiver, UUID sender, long now, long expiryMillis) {
        purgeExpired(now, expiryMillis);
        Key key = new Key(sender, receiver);
        if (!entries.containsKey(key)) {
            return List.of();
        }
        List<Entry> removed = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.sender().equals(sender) || entry.receiver().equals(sender)
                    || entry.sender().equals(receiver) || entry.receiver().equals(receiver)) {
                removed.add(entry);
            }
        }
        for (Entry entry : removed) {
            entries.remove(new Key(entry.sender(), entry.receiver()));
        }
        return List.copyOf(removed);
    }

    public synchronized void restore(List<Entry> restored, long now, long expiryMillis) {
        purgeExpired(now, expiryMillis);
        for (Entry entry : restored) {
            if (!isExpired(entry, now, expiryMillis)) {
                entries.putIfAbsent(new Key(entry.sender(), entry.receiver()), entry);
            }
        }
    }

    public synchronized boolean deny(UUID receiver, UUID sender, long now, long expiryMillis) {
        purgeExpired(now, expiryMillis);
        return entries.remove(new Key(sender, receiver)) != null;
    }

    public synchronized Entry find(UUID sender, UUID receiver, long now, long expiryMillis) {
        purgeExpired(now, expiryMillis);
        return entries.get(new Key(sender, receiver));
    }

    public synchronized List<Entry> incoming(UUID receiver, long now, long expiryMillis) {
        purgeExpired(now, expiryMillis);
        List<Entry> incoming = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.receiver().equals(receiver)) {
                incoming.add(entry);
            }
        }
        return List.copyOf(incoming);
    }

    public synchronized List<Entry> expire(long now, long expiryMillis) {
        List<Entry> expired = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (isExpired(entry, now, expiryMillis)) {
                expired.add(entry);
            }
        }
        for (Entry entry : expired) {
            entries.remove(new Key(entry.sender(), entry.receiver()));
        }
        return List.copyOf(expired);
    }

    public synchronized List<Entry> removeFor(UUID player) {
        List<Entry> removed = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.sender().equals(player) || entry.receiver().equals(player)) {
                removed.add(entry);
            }
        }
        for (Entry entry : removed) {
            entries.remove(new Key(entry.sender(), entry.receiver()));
        }
        return List.copyOf(removed);
    }

    public synchronized List<Entry> snapshot() {
        return List.copyOf(entries.values());
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized void clear() {
        entries.clear();
    }

    private void purgeExpired(long now, long expiryMillis) {
        expire(now, expiryMillis);
    }


    private static boolean isExpired(Entry entry, long now, long expiryMillis) {
        return expiryMillis >= 0 && now >= entry.createdAt() && now - entry.createdAt() >= expiryMillis;
    }

    public enum Result {
        CREATED,
        ALREADY_SENT,
        ACCEPTED
    }

    public record Key(UUID sender, UUID receiver) {
    }

    public record Entry(UUID sender, UUID receiver, long createdAt) {
    }
}
