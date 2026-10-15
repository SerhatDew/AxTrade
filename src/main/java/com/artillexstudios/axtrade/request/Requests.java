package com.artillexstudios.axtrade.request;

import com.artillexstudios.axapi.nms.wrapper.ServerPlayerWrapper;
import com.artillexstudios.axapi.utils.StringUtils;
import com.artillexstudios.axtrade.AxTrade;
import com.artillexstudios.axtrade.trade.Trade;
import com.artillexstudios.axtrade.api.events.AxTradeRequestEvent;
import com.artillexstudios.axtrade.safety.SafetyManager;
import com.artillexstudios.axtrade.trade.Trades;
import com.artillexstudios.axtrade.utils.SoundUtils;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.artillexstudios.axtrade.AxTrade.CONFIG;
import static com.artillexstudios.axtrade.AxTrade.LANG;
import static com.artillexstudios.axtrade.AxTrade.MESSAGEUTILS;

public final class Requests {
    private static final Object LOCK = new Object();
    private static final RequestBook BOOK = new RequestBook();
    private static final Map<RequestBook.Key, Request> REQUESTS = new HashMap<>();
    private static final Set<RequestBook.Key> IN_FLIGHT = new HashSet<>();

    private Requests() {
    }

    public static void addRequest(@NotNull Player sender, @NotNull Player receiver) {
        if (!validatePair(sender, receiver)) {
            return;
        }

        RequestBook.Key key = new RequestBook.Key(sender.getUniqueId(), receiver.getUniqueId());
        synchronized (LOCK) {
            pruneMirror();
            Request incoming = REQUESTS.get(new RequestBook.Key(receiver.getUniqueId(), sender.getUniqueId()));
            if (incoming != null) {
                if (!validatePair(sender, receiver)) {
                    return;
                }
                List<RequestBook.Entry> consumed = BOOK.consumeIncoming(
                        sender.getUniqueId(), receiver.getUniqueId(), now(), expiryMillis());
                if (consumed.isEmpty()) {
                    pruneMirror();
                    sendNoRequest(sender, receiver);
                    return;
                }

                Trade trade = Trades.addTrade(sender, receiver);
                if (trade == null) {
                    BOOK.restore(consumed, now(), expiryMillis());
                    MESSAGEUTILS.sendLang(sender, "request.already-in-trade", replacements(receiver));
                    return;
                }

                removeMirrorFor(sender, receiver);
                return;
            }

            if (REQUESTS.containsKey(key)) {
                MESSAGEUTILS.sendLang(sender, "request.already-sent", replacements(receiver));
                return;
            }

            if (IN_FLIGHT.contains(key)
                    || IN_FLIGHT.contains(new RequestBook.Key(receiver.getUniqueId(), sender.getUniqueId()))) {
                return;
            }
            IN_FLIGHT.add(key);
            try {
                AxTradeRequestEvent apiEvent = new AxTradeRequestEvent(sender, receiver);
                Bukkit.getPluginManager().callEvent(apiEvent);
                if (apiEvent.isCancelled() || !validatePair(sender, receiver)) {
                    return;
                }

                long createdAt = now();
                RequestBook.Result result = BOOK.request(sender.getUniqueId(), receiver.getUniqueId(), createdAt, expiryMillis());
                if (result != RequestBook.Result.CREATED) {
                    return;
                }
                REQUESTS.put(key, new Request(sender, receiver, createdAt));
            } finally {
                IN_FLIGHT.remove(key);
            }
        }

        sendRequestMessages(sender, receiver);
    }

    public static boolean accept(@NotNull Player receiver, @Nullable Player sender) {
        if (sender == null) {
            List<Request> incoming = getIncomingRequests(receiver);
            if (incoming.isEmpty()) {
                MESSAGEUTILS.sendLang(receiver, "request.no-request-any", Map.of());
                return false;
            }
            if (incoming.size() > 1) {
                MESSAGEUTILS.sendLang(receiver, "request.multiple", Map.of());
                return false;
            }
            sender = incoming.get(0).getSender();
        }

        if (!validatePair(receiver, sender)) {
            return false;
        }

        synchronized (LOCK) {
            pruneMirror();
            RequestBook.Key key = new RequestBook.Key(sender.getUniqueId(), receiver.getUniqueId());
            Request request = REQUESTS.get(key);
            if (request == null) {
                sendNoRequest(receiver, sender);
                return false;
            }
            if (!validatePair(receiver, sender)) {
                return false;
            }
            RequestBook.Entry entry = BOOK.find(sender.getUniqueId(), receiver.getUniqueId(), now(), expiryMillis());
            if (entry == null) {
                pruneMirror();
                sendNoRequest(receiver, sender);
                return false;
            }

            List<RequestBook.Entry> consumed = BOOK.consumeIncoming(
                    receiver.getUniqueId(), sender.getUniqueId(), now(), expiryMillis());
            if (consumed.isEmpty()) {
                pruneMirror();
                sendNoRequest(receiver, sender);
                return false;
            }

            Trade trade = Trades.addTrade(receiver, sender);
            if (trade == null) {
                BOOK.restore(consumed, now(), expiryMillis());
                MESSAGEUTILS.sendLang(receiver, "request.already-in-trade", replacements(sender));
                return false;
            }
            removeMirrorFor(receiver, sender);
            return true;
        }
    }

    public static boolean deny(@NotNull Player receiver, @Nullable Player sender) {
        if (sender == null) {
            List<Request> incoming = getIncomingRequests(receiver);
            if (incoming.isEmpty()) {
                MESSAGEUTILS.sendLang(receiver, "request.no-request-any", Map.of());
                return false;
            }
            if (incoming.size() > 1) {
                MESSAGEUTILS.sendLang(receiver, "request.multiple", Map.of());
                return false;
            }
            sender = incoming.get(0).getSender();
        }

        Request request;
        synchronized (LOCK) {
            pruneMirror();
            request = REQUESTS.get(new RequestBook.Key(sender.getUniqueId(), receiver.getUniqueId()));
            if (request == null || !BOOK.deny(receiver.getUniqueId(), sender.getUniqueId(), now(), expiryMillis())) {
                sendNoRequest(receiver, sender);
                return false;
            }
            request.deactivate();
            REQUESTS.remove(new RequestBook.Key(sender.getUniqueId(), receiver.getUniqueId()));
        }

        Map<String, String> replacements = replacements(receiver);
        if (request.getSender().isOnline()) {
            MESSAGEUTILS.sendLang(request.getSender(), "request.deny-sender", replacements);
            SoundUtils.playSound(request.getSender(), "deny");
        }
        MESSAGEUTILS.sendLang(receiver, "request.deny-receiver", Map.of("%player%", request.getSender().getName()));
        SoundUtils.playSound(receiver, "deny");
        return true;
    }

    @Nullable
    public static Request getRequest(@NotNull Player p1, @NotNull Player p2) {
        synchronized (LOCK) {
            pruneMirror();
            Request request = REQUESTS.get(new RequestBook.Key(p1.getUniqueId(), p2.getUniqueId()));
            if (request != null) {
                return request;
            }
            return REQUESTS.get(new RequestBook.Key(p2.getUniqueId(), p1.getUniqueId()));
        }
    }

    @Nullable
    public static Request getIncomingRequest(@NotNull Player receiver, @NotNull Player sender) {
        synchronized (LOCK) {
            pruneMirror();
            return REQUESTS.get(new RequestBook.Key(sender.getUniqueId(), receiver.getUniqueId()));
        }
    }

    public static List<Request> getIncomingRequests(@NotNull Player receiver) {
        synchronized (LOCK) {
            pruneMirror();
            List<Request> incoming = new ArrayList<>();
            for (Request request : REQUESTS.values()) {
                if (request.getReceiver().getUniqueId().equals(receiver.getUniqueId())) {
                    incoming.add(request);
                }
            }
            return List.copyOf(incoming);
        }
    }

    public static boolean hasRequest(@NotNull Player p1, @NotNull Player p2) {
        return getRequest(p1, p2) != null;
    }

    public static List<Request> getRequests() {
        synchronized (LOCK) {
            pruneMirror();
            return List.copyOf(REQUESTS.values());
        }
    }

    public static void expireRequests() {
        List<RequestBook.Entry> expired;
        synchronized (LOCK) {
            expired = BOOK.expire(now(), expiryMillis());
            for (RequestBook.Entry entry : expired) {
                Request request = REQUESTS.remove(new RequestBook.Key(entry.sender(), entry.receiver()));
                if (request == null || !request.isActive() || !request.getSender().isOnline()) {
                    continue;
                }
                request.deactivate();
                MESSAGEUTILS.sendLang(request.getSender(), "request.expired", replacements(request.getReceiver()));
            }
        }
    }

    public static void removeFor(@NotNull Player player) {
        List<RequestBook.Entry> removed;
        synchronized (LOCK) {
            removed = BOOK.removeFor(player.getUniqueId());
            for (RequestBook.Entry entry : removed) {
                Request request = REQUESTS.remove(new RequestBook.Key(entry.sender(), entry.receiver()));
                if (request == null) {
                    continue;
                }
                request.deactivate();
                if (request.getReceiver().getUniqueId().equals(player.getUniqueId()) && request.getSender().isOnline()) {
                    MESSAGEUTILS.sendLang(request.getSender(), "request.expired", replacements(request.getReceiver()));
                }
            }
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            BOOK.clear();
            REQUESTS.clear();
            IN_FLIGHT.clear();
        }
    }

    private static boolean validatePair(Player sender, Player receiver) {
        Map<String, String> replacements = replacements(receiver);
        if (sender.equals(receiver) || sender.getUniqueId().equals(receiver.getUniqueId())) {
            MESSAGEUTILS.sendLang(sender, "request.cant-trade-self", replacements);
            return false;
        }
        if (!sender.isOnline() || !receiver.isOnline() || !sender.canSee(receiver) || !receiver.canSee(sender)) {
            MESSAGEUTILS.sendLang(sender, "request.not-accepting", replacements);
            return false;
        }
        if (!SafetyManager.TRADING.get()) {
            MESSAGEUTILS.sendLang(sender, "safety");
            return false;
        }
        if (!preferencesReady(sender) || !preferencesReady(receiver)) {
            MESSAGEUTILS.sendLang(sender, "request.preferences-unavailable", replacements);
            return false;
        }
        if (preferencesDisabled(receiver)) {
            MESSAGEUTILS.sendLang(sender, "request.not-accepting", replacements);
            return false;
        }
        if (preferencesDisabled(sender)) {
            MESSAGEUTILS.sendLang(sender, "request.disabled-trading", replacements);
            return false;
        }
        if (sender.isDead() || receiver.isDead()) {
            MESSAGEUTILS.sendLang(sender, "request.not-accepting", replacements);
            return false;
        }

        List<String> disallowed = CONFIG.getStringList("disallowed-gamemodes");
        if (disallowed.contains(sender.getGameMode().name()) || disallowed.contains(receiver.getGameMode().name())) {
            MESSAGEUTILS.sendLang(sender, "request.disallowed-gamemode", replacements);
            return false;
        }
        List<String> blacklisted = CONFIG.getStringList("blacklisted-worlds");
        if (blacklisted.contains(sender.getWorld().getName()) || blacklisted.contains(receiver.getWorld().getName())) {
            MESSAGEUTILS.sendLang(sender, "request.blacklisted-world", replacements);
            return false;
        }
        boolean disallowSameIp = CONFIG.getBoolean("disallow-same-ip-trade", false);
        if (disallowSameIp && sender.getAddress() != null && receiver.getAddress() != null
                && sender.getAddress().getAddress().equals(receiver.getAddress().getAddress())) {
            MESSAGEUTILS.sendLang(sender, "request.same-ip", replacements);
            return false;
        }
        if (Trades.isTrading(sender) || Trades.isTrading(receiver)) {
            MESSAGEUTILS.sendLang(sender, "request.already-in-trade", replacements);
            return false;
        }
        int maxDistance = CONFIG.getInt("trade-max-distance", 10);
        if (maxDistance != -1 && (!sender.getWorld().equals(receiver.getWorld())
                || sender.getLocation().distanceSquared(receiver.getLocation()) > (double) maxDistance * maxDistance)) {
            MESSAGEUTILS.sendLang(sender, "request.too-far", replacements);
            return false;
        }
        return true;
    }

    private static boolean preferencesReady(Player player) {
        return AxTrade.getPreferences().isReady(player.getUniqueId());
    }

    private static boolean preferencesDisabled(Player player) {
        return AxTrade.getPreferences().isDisabled(player.getUniqueId());
    }

    private static void sendRequestMessages(Player sender, Player receiver) {
        MESSAGEUTILS.sendLang(sender, "request.sent-sender", replacements(receiver));
        Map<String, String> replacements = Map.of("%player%", sender.getName());
        String commandRoot = commandRoot();
        if (LANG.getSection("request.sent-receiver") == null) {
            MESSAGEUTILS.sendLang(receiver, "request.sent-receiver", replacements);
        } else {
            ServerPlayerWrapper receiverWrap = ServerPlayerWrapper.wrap(receiver);
            receiverWrap.message(StringUtils.format(CONFIG.getString("prefix") + LANG.getString("request.sent-receiver.info"), replacements));
            receiverWrap.message(StringUtils.format(LANG.getString("request.sent-receiver.accept.message"), replacements)
                    .hoverEvent(HoverEvent.showText(StringUtils.format(LANG.getString("request.sent-receiver.accept.hover"), replacements)))
                    .clickEvent(ClickEvent.runCommand("/" + commandRoot + " accept " + sender.getName())));
            receiverWrap.message(StringUtils.format(LANG.getString("request.sent-receiver.deny.message"), replacements)
                    .hoverEvent(HoverEvent.showText(StringUtils.format(LANG.getString("request.sent-receiver.deny.hover"), replacements)))
                    .clickEvent(ClickEvent.runCommand("/" + commandRoot + " deny " + sender.getName())));
        }
        SoundUtils.playSound(sender, "requested");
        SoundUtils.playSound(receiver, "requested");
    }

    private static void sendNoRequest(Player receiver, Player sender) {
        MESSAGEUTILS.sendLang(receiver, "request.no-request", Map.of("%player%", sender.getName()));
    }

    private static Map<String, String> replacements(Player player) {
        return Map.of("%player%", player.getName());
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static long expiryMillis() {
        return Math.max(0L, CONFIG.getInt("trade-request-expire-seconds", 60)) * 1_000L;
    }
    private static String commandRoot() {
        List<String> aliases = CONFIG.getStringList("command-aliases");
        return aliases.isEmpty() ? "trade" : aliases.get(0);
    }

    private static void pruneMirror() {
        List<RequestBook.Entry> expired = BOOK.expire(now(), expiryMillis());
        for (RequestBook.Entry entry : expired) {
            Request request = REQUESTS.remove(new RequestBook.Key(entry.sender(), entry.receiver()));
            if (request != null) {
                request.deactivate();
                if (request.getSender().isOnline()) {
                    MESSAGEUTILS.sendLang(request.getSender(), "request.expired", replacements(request.getReceiver()));
                }
            }
        }

        Set<RequestBook.Key> keys = new HashSet<>();
        for (RequestBook.Entry entry : BOOK.snapshot()) {
            keys.add(new RequestBook.Key(entry.sender(), entry.receiver()));
        }
        REQUESTS.keySet().removeIf(key -> !keys.contains(key));
    }

    private static void removeMirrorFor(Player first, Player second) {
        REQUESTS.entrySet().removeIf(entry -> {
            Request request = entry.getValue();
            return request.getSender().getUniqueId().equals(first.getUniqueId())
                    || request.getReceiver().getUniqueId().equals(first.getUniqueId())
                    || request.getSender().getUniqueId().equals(second.getUniqueId())
                    || request.getReceiver().getUniqueId().equals(second.getUniqueId());
        });
    }
}