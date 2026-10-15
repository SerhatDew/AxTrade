package com.artillexstudios.axtrade.network;

import com.artillexstudios.axapi.nms.wrapper.ServerPlayerWrapper;
import com.artillexstudios.axapi.scheduler.Scheduler;
import com.artillexstudios.axapi.utils.StringUtils;
import com.artillexstudios.axtrade.AxTrade;
import com.artillexstudios.axtrade.trade.Trades;
import com.artillexstudios.axtrade.utils.SoundUtils;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.artillexstudios.axtrade.AxTrade.CONFIG;
import static com.artillexstudios.axtrade.AxTrade.LANG;
import static com.artillexstudios.axtrade.AxTrade.MESSAGEUTILS;

public class CrossServerManager implements AutoCloseable {
    private static final long PLAYER_STALE_AFTER_MILLIS = 30_000L;
    private static final long REQUEST_TIMEOUT_TICKS = 1_200L;
    private static final long REQUEST_TIMEOUT_MILLIS = 60_000L;
    private static final long PACKET_MAX_AGE_MILLIS = 120_000L;
    private static final String UNREGISTER_SCRIPT = """
            local current = redis.call('hget', KEYS[1], ARGV[1])
            if current and string.sub(current, 1, string.len(ARGV[2])) == ARGV[2] then
                redis.call('hdel', KEYS[1], ARGV[1])
                if redis.call('hget', KEYS[2], ARGV[3]) == ARGV[1] then
                    redis.call('hdel', KEYS[2], ARGV[3])
                end
            end
            return 1
            """;

    private static volatile CrossServerManager instance;

    private final AxTrade plugin;
    private final String serverId;
    private final String namespace;
    private final String channel;
    private final JedisPool pool;
    private final ExecutorService publishExecutor;
    private final ConcurrentHashMap<String, CrossServerTradeSession> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CrossServerPacket> incomingRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CrossServerPacket> outgoingRequests = new ConcurrentHashMap<>();
    private volatile boolean closed = false;
    private Thread subscriberThread;
    private volatile Jedis subscriberJedis;
    private volatile JedisPubSub subscriber;

    public CrossServerManager(AxTrade plugin, String serverId, String namespace, JedisPool pool) {
        this.plugin = plugin;
        this.serverId = serverId;
        this.namespace = namespace;
        this.channel = namespace + ":cross-server";
        this.pool = pool;
        this.publishExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AxTrade-cross-publish");
            t.setDaemon(true);
            return t;
        });
    }

    public static void init(AxTrade plugin) {
        if (!CONFIG.getBoolean("storage.redis.enabled", false)) {
            return;
        }
        try {
            String serverId = CONFIG.getString("storage.redis.server-id", "server");
            String namespace = CONFIG.getString("storage.redis.namespace", "axtrade");
            String host = CONFIG.getString("storage.redis.host", "localhost");
            int port = CONFIG.getInt("storage.redis.port", 6379);
            int db = CONFIG.getInt("storage.redis.database", 0);
            boolean tls = CONFIG.getBoolean("storage.redis.tls", false);
            String user = CONFIG.getString("storage.redis.username", "");
            String pass = CONFIG.getString("storage.redis.password", "");
            int timeout = Math.max(250, CONFIG.getInt("storage.redis.connect-timeout-ms", 3000));

            DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                    .connectionTimeoutMillis(timeout)
                    .socketTimeoutMillis(timeout)
                    .database(db)
                    .ssl(tls);
            if (!user.isBlank()) builder.user(user);
            if (!pass.isBlank()) builder.password(pass);

            JedisPool jedisPool = new JedisPool(new HostAndPort(host, port), builder.build());
            // Test connection
            try (Jedis test = jedisPool.getResource()) {
                test.ping();
            }

            CrossServerManager mgr = new CrossServerManager(plugin, serverId, namespace, jedisPool);
            mgr.start();
            instance = mgr;
            plugin.getLogger().info("AxTrade cross-server trading enabled via Redis server-id: " + serverId);
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not start AxTrade cross-server manager: " + ex.getMessage());
        }
    }

    public static boolean isEnabled() {
        return instance != null && !instance.closed;
    }

    public static CrossServerManager getInstance() {
        return instance;
    }

    public String getServerId() {
        return serverId;
    }
    void logSettlementFailure(String tradeId, Throwable cause) {
        if (cause == null) {
            plugin.getLogger().warning("Cross-server trade settlement is unresolved: " + tradeId);
        } else {
            plugin.getLogger().warning("Cross-server trade settlement is unresolved: " + tradeId
                    + " (" + cause.getMessage() + ")");
        }
    }

    public void start() {
        // Register all current online players
        for (Player p : Bukkit.getOnlinePlayers()) {
            registerPlayer(p);
        }

        // Start Redis subscriber
        subscriberThread = new Thread(this::subscribeLoop, "AxTrade-cross-subscribe");
        subscriberThread.setDaemon(true);
        subscriberThread.start();

        // Heartbeat timer every 10 seconds
        Scheduler.get().runTimer(task -> {
            if (closed) return;
            for (Player p : Bukkit.getOnlinePlayers()) {
                registerPlayer(p);
            }
        }, 200, 200);
    }

    public void registerPlayer(Player player) {
        if (closed || player == null) return;
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String nameKey = name.toLowerCase(Locale.ROOT);
        publishExecutor.execute(() -> {
            try (Jedis jedis = pool.getResource()) {
                String keyPlayers = namespace + ":players";
                String keyNames = namespace + ":player_names";
                String value = serverId + "\t" + name + "\t" + System.currentTimeMillis();
                jedis.hset(keyPlayers, uuid.toString(), value);
                jedis.hset(keyNames, nameKey, uuid.toString());
            } catch (Exception ignored) {
            }
        });
    }

    public void unregisterPlayer(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        incomingRequests.entrySet().removeIf(entry -> {
            CrossServerPacket packet = entry.getValue();
            return uuid.equals(packet.getSenderUuid()) || uuid.equals(packet.getReceiverUuid());
        });
        outgoingRequests.entrySet().removeIf(entry -> uuid.equals(entry.getValue().getSenderUuid())
                || uuid.equals(entry.getValue().getReceiverUuid()));
        unregisterPlayer(uuid, player.getName());
    }

    private void unregisterPlayer(UUID uuid, String name) {
        if (closed) return;
        String uuidKey = uuid.toString();
        String nameKey = name.toLowerCase(Locale.ROOT);
        publishExecutor.execute(() -> unregisterPlayerNow(uuidKey, nameKey));
    }

    private void unregisterPlayerNow(String uuid, String nameKey) {
        try (Jedis jedis = pool.getResource()) {
            jedis.eval(UNREGISTER_SCRIPT,
                    List.of(namespace + ":players", namespace + ":player_names"),
                    List.of(uuid, serverId + "\t", nameKey));
        } catch (Exception ignored) {
        }
    }

    public NetworkPlayer findPlayer(String name) {
        if (name == null || name.isBlank() || closed) return null;
        try (Jedis jedis = pool.getResource()) {
            String uuidStr = jedis.hget(namespace + ":player_names", name.toLowerCase(Locale.ROOT));
            if (uuidStr == null) return null;
            UUID uuid = UUID.fromString(uuidStr);
            return readNetworkPlayer(jedis, uuid, name);
        } catch (Exception ex) {
            return null;
        }
    }

    public List<String> getNetworkPlayerNames(Player viewer) {
        if (closed) return List.of();
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> names = jedis.hgetAll(namespace + ":player_names");
            List<String> result = new ArrayList<>();
            for (Map.Entry<String, String> entry : names.entrySet()) {
                String name = entry.getKey();
                if (viewer != null && name.equalsIgnoreCase(viewer.getName())) continue;
                if (Bukkit.getPlayerExact(name) != null) continue;
                try {
                    NetworkPlayer player = readNetworkPlayer(jedis, UUID.fromString(entry.getValue()), name);
                    if (player != null) result.add(name);
                } catch (IllegalArgumentException ignored) {
                }
            }
            return result;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private NetworkPlayer readNetworkPlayer(Jedis jedis, UUID uuid, String fallbackName) {
        String info = jedis.hget(namespace + ":players", uuid.toString());
        if (info == null) return null;

        String targetServer;
        String realName;
        long lastSeen;
        String[] fields = info.split("\t", 3);
        if (fields.length == 3) {
            targetServer = fields[0];
            realName = fields[1];
            try {
                lastSeen = Long.parseLong(fields[2]);
            } catch (NumberFormatException ignored) {
                return null;
            }
            if (lastSeen <= 0 || System.currentTimeMillis() - lastSeen > PLAYER_STALE_AFTER_MILLIS) {
                return null;
            }
        } else {
            return null;
        }
        if (targetServer.isBlank() || realName.isBlank()) return null;
        return new NetworkPlayer(uuid, realName, targetServer, lastSeen);
    }

    public void publish(CrossServerPacket packet) {
        if (closed) return;
        publishExecutor.execute(() -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.publish(channel, packet.toJson());
            } catch (Exception ignored) {
            }
        });
    }

    public boolean sendRequest(Player sender, NetworkPlayer target) {
        if (closed || sender == null || target == null || target.uuid() == null
                || target.name() == null || target.name().isBlank()
                || target.serverId() == null || target.serverId().isBlank()
                || target.serverId().equalsIgnoreCase(serverId)
                || target.uuid().equals(sender.getUniqueId())) {
            return false;
        }
        if (Trades.isTrading(sender)) {
            MESSAGEUTILS.sendLang(sender, "request.already-in-trade", Map.of("%player%", target.name()));
            return false;
        }
        if (AxTrade.getPreferences().isDisabled(sender.getUniqueId())) {
            MESSAGEUTILS.sendLang(sender, "request.disabled-trading", Map.of("%player%", target.name()));
            return false;
        }

        CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.REQUEST);
        packet.setSenderUuid(sender.getUniqueId());
        packet.setSenderName(sender.getName());
        packet.setSenderServer(serverId);
        packet.setReceiverUuid(target.uuid());
        packet.setReceiverName(target.name());
        packet.setTargetServer(target.serverId());

        String key = requestKey(packet);
        if (outgoingRequests.putIfAbsent(key, packet) != null) {
            MESSAGEUTILS.sendLang(sender, "request.already-sent", Map.of("%player%", target.name()));
            return false;
        }
        publish(packet);

        MESSAGEUTILS.sendLang(sender, "request.sent-sender", Map.of("%player%", target.name()));
        SoundUtils.playSound(sender, "requested");

        Scheduler.get().runLater(task -> {
            if (outgoingRequests.remove(key, packet) && sender.isOnline()) {
                MESSAGEUTILS.sendLang(sender, "request.expired", Map.of("%player%", target.name()));
            }
        }, REQUEST_TIMEOUT_TICKS);

        return true;
    }

    public boolean acceptRequest(Player receiver, String senderName) {
        if (closed) return false;
        List<CrossServerPacket> requests = incomingRequests.values().stream()
                .filter(request -> request.getReceiverUuid().equals(receiver.getUniqueId()))
                .filter(this::isRequestActive)
                .toList();
        CrossServerPacket req = null;
        if (senderName == null) {
            if (requests.isEmpty()) {
                return false;
            }
            if (requests.size() > 1) {
                MESSAGEUTILS.sendLang(receiver, "request.multiple", Map.of());
                return true;
            }
            req = requests.get(0);
        } else {
            for (CrossServerPacket request : requests) {
                if (request.getSenderName().equalsIgnoreCase(senderName)) {
                    req = request;
                    break;
                }
            }
        }

        if (req == null) {
            return false;
        }

        if (Trades.isTrading(receiver)) {
            MESSAGEUTILS.sendLang(receiver, "request.already-in-trade", Map.of("%player%", req.getSenderName()));
            return true;
        }

        String requestKey = requestKey(req);
        if (!incomingRequests.remove(requestKey, req)) {
            return false;
        }

        String tradeId = UUID.randomUUID().toString();
        CrossServerPacket acceptPacket = new CrossServerPacket(CrossServerPacket.Type.ACCEPT);
        acceptPacket.setTradeId(tradeId);
        acceptPacket.setSenderUuid(req.getSenderUuid());
        acceptPacket.setSenderServer(serverId);
        acceptPacket.setReceiverUuid(receiver.getUniqueId());
        acceptPacket.setReceiverName(receiver.getName());
        acceptPacket.setReceiverServer(serverId);
        acceptPacket.setTargetServer(req.getSenderServer());

        publish(acceptPacket);

        // Start session on receiver
        startSession(tradeId, receiver, req.getSenderUuid(), req.getSenderName(), req.getSenderServer());
        return true;
    }

    public boolean denyRequest(Player receiver, String senderName) {
        if (closed) return false;
        List<CrossServerPacket> requests = incomingRequests.values().stream()
                .filter(request -> request.getReceiverUuid().equals(receiver.getUniqueId()))
                .filter(this::isRequestActive)
                .toList();
        CrossServerPacket req = null;
        if (senderName == null) {
            if (requests.isEmpty()) return false;
            req = requests.get(0);
        } else {
            for (CrossServerPacket request : requests) {
                if (request.getSenderName().equalsIgnoreCase(senderName)) {
                    req = request;
                    break;
                }
            }
        }
        if (req == null) return false;

        String requestKey = requestKey(req);
        if (!incomingRequests.remove(requestKey, req)) {
            return false;
        }

        CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.DENY);
        packet.setSenderUuid(req.getSenderUuid());
        packet.setSenderServer(serverId);
        packet.setTargetServer(req.getSenderServer());
        packet.setReceiverUuid(receiver.getUniqueId());
        packet.setReceiverName(receiver.getName());
        publish(packet);

        MESSAGEUTILS.sendLang(receiver, "request.deny-receiver", Map.of("%player%", req.getSenderName()));
        SoundUtils.playSound(receiver, "deny");
        return true;
    }

    public void removeSession(String tradeId) {
        activeSessions.remove(tradeId);
    }

    public List<String> getIncomingSenderNames(Player receiver) {
        List<String> list = new ArrayList<>();
        for (CrossServerPacket packet : incomingRequests.values()) {
            if (packet.getReceiverUuid().equals(receiver.getUniqueId()) && isRequestActive(packet)) {
                list.add(packet.getSenderName());
            }
        }
        return list;
    }

    private boolean isRequestActive(CrossServerPacket packet) {
        long age = System.currentTimeMillis() - packet.getTimestamp();
        return age >= 0 && age <= REQUEST_TIMEOUT_MILLIS;
    }

    private void startSession(String tradeId, Player localPlayer, UUID remoteUuid, String remoteName, String remoteServer) {
        Scheduler.get().run(localPlayer, task -> {
            try {
                CrossServerTradeSession session = new CrossServerTradeSession(
                        tradeId, localPlayer, remoteUuid, remoteName, remoteServer, this);
                if (activeSessions.putIfAbsent(tradeId, session) != null) {
                    session.abort(true);
                    return;
                }
                if (!Trades.addCrossServerTrade(session)) {
                    activeSessions.remove(tradeId, session);
                    session.abort(true);
                    return;
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not start cross-server trade session: " + ex.getMessage());
            }
        }, () -> {});
    }

    private void subscribeLoop() {
        while (!closed) {
            try (Jedis jedis = pool.getResource()) {
                if (closed) return;
                subscriberJedis = jedis;
                JedisPubSub listener = new JedisPubSub() {
                    @Override
                    public void onMessage(String channelName, String message) {
                        try {
                            CrossServerPacket packet = CrossServerPacket.fromJson(message);
                            if (packet == null || !packet.isWellFormed()) return;
                            long age = System.currentTimeMillis() - packet.getTimestamp();
                            if (age < -5_000L || age > PACKET_MAX_AGE_MILLIS) return;
                            handlePacket(packet);
                        } catch (Exception ignored) {
                        }
                    }
                };
                subscriber = listener;
                try {
                    jedis.subscribe(listener, channel);
                } finally {
                    if (subscriber == listener) subscriber = null;
                    subscriberJedis = null;
                }
            } catch (Exception ex) {
                if (!closed) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void handlePacket(CrossServerPacket packet) {
        if (closed || !packet.isWellFormed()
                || !packet.getTargetServer().equalsIgnoreCase(serverId)
                || packet.getSenderServer().equalsIgnoreCase(serverId)) {
            return;
        }

        switch (packet.getType()) {
            case REQUEST -> {
                Player receiver = Bukkit.getPlayer(packet.getReceiverUuid());
                if (receiver == null || !receiver.isOnline()) {
                    publishRequestDeny(packet);
                } else {
                    dispatch(receiver, () -> handleRequest(packet, receiver));
                }
            }
            case ACCEPT -> {
                Player sender = Bukkit.getPlayer(packet.getSenderUuid());
                dispatch(sender, () -> handleAccept(packet, sender));
            }
            case DENY -> {
                Player sender = Bukkit.getPlayer(packet.getSenderUuid());
                dispatch(sender, () -> handleDeny(packet, sender));
            }
            case TRADE_ITEMS -> {
                CrossServerTradeSession session = findSession(packet);
                if (session != null) {
                    dispatch(session.getLocalPlayer(), () -> {
                        List<ItemStack> items = ItemSerialization.deserializeListStrict(packet.getItems());
                        if (items != null) session.onRemoteItemsReceived(items);
                    });
                }
            }
            case TRADE_CURRENCY -> {
                CrossServerTradeSession session = findSession(packet);
                if (session != null) {
                    dispatch(session.getLocalPlayer(), () ->
                            session.onRemoteCurrency(packet.getCurrencyName(), packet.getCurrencyAmount()));
                }
            }
            case TRADE_CONFIRM -> {
                CrossServerTradeSession session = findSession(packet);
                if (session != null) {
                    dispatch(session.getLocalPlayer(), () -> session.onRemoteConfirm(packet.getConfirmSeconds()));
                }
            }
            case TRADE_UNCONFIRM -> {
                CrossServerTradeSession session = findSession(packet);
                if (session != null) dispatch(session.getLocalPlayer(), session::onRemoteUnconfirm);
            }
            case TRADE_ABORT -> {
                CrossServerTradeSession session = findSession(packet);
                if (session != null) dispatch(session.getLocalPlayer(), session::onRemoteAbortReceived);
            }
            case COMMIT_PREPARE -> {
                CrossServerTradeSession session = findSession(packet);
                if (session != null) {
                    dispatch(session.getLocalPlayer(),
                            () -> session.onRemoteCommitPrepare(packet.getOfferDigest()));
                }
            }
            case COMMIT_FINALIZE -> {
                CrossServerTradeSession session = findSession(packet);
                if (session != null) {
                    dispatch(session.getLocalPlayer(),
                            () -> session.onRemoteCommitFinalize(packet.getOfferDigest()));
                }
            }
        }
    }

    private void handleRequest(CrossServerPacket packet, Player receiver) {
        if (!receiver.isOnline() || Trades.isTrading(receiver)
                || AxTrade.getPreferences().isDisabled(receiver.getUniqueId())) {
            publishRequestDeny(packet);
            return;
        }

        String key = requestKey(packet);
        if (incomingRequests.putIfAbsent(key, packet) != null) return;
        Scheduler.get().runLater(task -> incomingRequests.remove(key, packet), REQUEST_TIMEOUT_TICKS);

        Map<String, String> replacements = Map.of("%player%", packet.getSenderName());
        String commandRoot = "trade";
        if (LANG.getSection("request.sent-receiver") == null) {
            MESSAGEUTILS.sendLang(receiver, "request.sent-receiver", replacements);
        } else {
            ServerPlayerWrapper wrap = ServerPlayerWrapper.wrap(receiver);
            wrap.message(StringUtils.format(CONFIG.getString("prefix") + LANG.getString("request.sent-receiver.info"), replacements));
            wrap.message(StringUtils.format(LANG.getString("request.sent-receiver.accept.message"), replacements)
                    .hoverEvent(HoverEvent.showText(StringUtils.format(LANG.getString("request.sent-receiver.accept.hover"), replacements)))
                    .clickEvent(ClickEvent.runCommand("/" + commandRoot + " accept " + packet.getSenderName())));
            wrap.message(StringUtils.format(LANG.getString("request.sent-receiver.deny.message"), replacements)
                    .hoverEvent(HoverEvent.showText(StringUtils.format(LANG.getString("request.sent-receiver.deny.hover"), replacements)))
                    .clickEvent(ClickEvent.runCommand("/" + commandRoot + " deny " + packet.getSenderName())));
        }
        SoundUtils.playSound(receiver, "requested");
    }

    private void publishRequestDeny(CrossServerPacket packet) {
        CrossServerPacket deny = new CrossServerPacket(CrossServerPacket.Type.DENY);
        deny.setSenderUuid(packet.getSenderUuid());
        deny.setSenderServer(serverId);
        deny.setTargetServer(packet.getSenderServer());
        deny.setReceiverUuid(packet.getReceiverUuid());
        deny.setReceiverName(packet.getReceiverName());
        publish(deny);
    }


    private void handleAccept(CrossServerPacket packet, Player sender) {
        if (sender == null || !sender.isOnline()) return;
        String key = requestKey(packet);
        CrossServerPacket request = outgoingRequests.get(key);
        if (request == null || !Objects.equals(request.getSenderUuid(), packet.getSenderUuid())
                || !Objects.equals(request.getReceiverUuid(), packet.getReceiverUuid())
                || !Objects.equals(request.getTargetServer(), packet.getSenderServer())
                || !Objects.equals(request.getReceiverName(), packet.getReceiverName())) {
            return;
        }
        if (!outgoingRequests.remove(key, request)) return;
        startSession(packet.getTradeId(), sender, packet.getReceiverUuid(), packet.getReceiverName(),
                packet.getReceiverServer());
    }

    private void handleDeny(CrossServerPacket packet, Player sender) {
        if (sender == null || !sender.isOnline()) return;
        String key = requestKey(packet);
        CrossServerPacket request = outgoingRequests.get(key);
        if (request == null || !Objects.equals(request.getSenderUuid(), packet.getSenderUuid())
                || !Objects.equals(request.getReceiverUuid(), packet.getReceiverUuid())
                || !Objects.equals(request.getTargetServer(), packet.getSenderServer())
                || !Objects.equals(request.getReceiverName(), packet.getReceiverName())) {
            return;
        }
        if (!outgoingRequests.remove(key, request)) return;
        MESSAGEUTILS.sendLang(sender, "request.deny-sender", Map.of("%player%", packet.getReceiverName()));
        SoundUtils.playSound(sender, "deny");
    }

    private CrossServerTradeSession findSession(CrossServerPacket packet) {
        CrossServerTradeSession session = activeSessions.get(packet.getTradeId());
        return session != null && session.acceptsPeer(packet.getSenderUuid(), packet.getSenderServer())
                ? session : null;
    }

    private void dispatch(Player player, Runnable action) {
        if (player == null || closed) return;
        Scheduler.get().run(player, task -> {
            if (!closed && player.isOnline()) action.run();
        }, () -> {});
    }

    private static String requestKey(CrossServerPacket packet) {
        return packet.getSenderUuid() + ":" + packet.getReceiverUuid();
    }

    @Override
    public void close() {
        if (closed) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            String uuid = p.getUniqueId().toString();
            String name = p.getName().toLowerCase(Locale.ROOT);
            publishExecutor.execute(() -> unregisterPlayerNow(uuid, name));
        }
        closed = true;
        for (CrossServerTradeSession session : activeSessions.values()) {
            session.abort(true);
        }
        activeSessions.clear();
        JedisPubSub currentSubscriber = subscriber;
        if (currentSubscriber != null) {
            try {
                currentSubscriber.unsubscribe();
            } catch (Exception ignored) {
            }
        }
        Jedis currentJedis = subscriberJedis;
        if (currentJedis != null) {
            try {
                currentJedis.close();
            } catch (Exception ignored) {
            }
        }
        if (subscriberThread != null) subscriberThread.interrupt();
        publishExecutor.shutdown();
        try {
            if (!publishExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                publishExecutor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            publishExecutor.shutdownNow();
        }
        pool.close();
        instance = null;
    }

}
