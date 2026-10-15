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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.artillexstudios.axtrade.AxTrade.CONFIG;
import static com.artillexstudios.axtrade.AxTrade.LANG;
import static com.artillexstudios.axtrade.AxTrade.MESSAGEUTILS;

public class CrossServerManager implements AutoCloseable {
    private static volatile CrossServerManager instance;

    private final AxTrade plugin;
    private final String serverId;
    private final String namespace;
    private final String channel;
    private final JedisPool pool;
    private final ExecutorService publishExecutor;
    private final ConcurrentHashMap<String, CrossServerTradeSession> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CrossServerPacket> incomingRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CrossServerPacket> outgoingRequests = new ConcurrentHashMap<>();
    private volatile boolean closed = false;
    private Thread subscriberThread;
    private Jedis subscriberJedis;

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
        if (closed) return;
        publishExecutor.execute(() -> {
            try (Jedis jedis = pool.getResource()) {
                String keyPlayers = namespace + ":players";
                String keyNames = namespace + ":player_names";
                jedis.hset(keyPlayers, player.getUniqueId().toString(), serverId + ":" + player.getName());
                jedis.hset(keyNames, player.getName().toLowerCase(Locale.ROOT), player.getUniqueId().toString());
            } catch (Exception ignored) {
            }
        });
    }

    public void unregisterPlayer(Player player) {
        if (closed) return;
        publishExecutor.execute(() -> {
            try (Jedis jedis = pool.getResource()) {
                String keyPlayers = namespace + ":players";
                String keyNames = namespace + ":player_names";
                jedis.hdel(keyPlayers, player.getUniqueId().toString());
                jedis.hdel(keyNames, player.getName().toLowerCase(Locale.ROOT));
            } catch (Exception ignored) {
            }
        });
    }

    public NetworkPlayer findPlayer(String name) {
        if (name == null || name.isBlank() || closed) return null;
        try (Jedis jedis = pool.getResource()) {
            String uuidStr = jedis.hget(namespace + ":player_names", name.toLowerCase(Locale.ROOT));
            if (uuidStr == null) return null;
            UUID uuid = UUID.fromString(uuidStr);
            String info = jedis.hget(namespace + ":players", uuidStr);
            if (info == null) return null;
            String[] parts = info.split(":", 2);
            String targetServer = parts[0];
            String realName = parts.length > 1 ? parts[1] : name;
            return new NetworkPlayer(uuid, realName, targetServer, System.currentTimeMillis());
        } catch (Exception ex) {
            return null;
        }
    }

    public List<String> getNetworkPlayerNames(Player viewer) {
        if (closed) return List.of();
        try (Jedis jedis = pool.getResource()) {
            Set<String> names = jedis.hkeys(namespace + ":player_names");
            List<String> result = new ArrayList<>();
            for (String n : names) {
                if (viewer != null && n.equalsIgnoreCase(viewer.getName())) continue;
                if (Bukkit.getPlayerExact(n) != null) continue; // local players already handled
                result.add(n);
            }
            return result;
        } catch (Exception ex) {
            return List.of();
        }
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
        if (closed) return false;
        if (Trades.isTrading(sender)) {
            MESSAGEUTILS.sendLang(sender, "request.already-in-trade", Map.of("%player%", target.name()));
            return false;
        }
        if (AxTrade.getPreferences().isDisabled(sender.getUniqueId())) {
            MESSAGEUTILS.sendLang(sender, "request.disabled-trading", Map.of("%player%", target.name()));
            return false;
        }
        if (outgoingRequests.containsKey(target.uuid())) {
            MESSAGEUTILS.sendLang(sender, "request.already-sent", Map.of("%player%", target.name()));
            return false;
        }

        CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.REQUEST);
        packet.setSenderUuid(sender.getUniqueId());
        packet.setSenderName(sender.getName());
        packet.setSenderServer(serverId);
        packet.setReceiverUuid(target.uuid());
        packet.setReceiverName(target.name());
        packet.setTargetServer(target.serverId());

        outgoingRequests.put(target.uuid(), packet);
        publish(packet);

        MESSAGEUTILS.sendLang(sender, "request.sent-sender", Map.of("%player%", target.name()));
        SoundUtils.playSound(sender, "requested");

        // Expire after 60s
        Scheduler.get().runLater(task -> {
            CrossServerPacket req = outgoingRequests.remove(target.uuid());
            if (req != null && sender.isOnline()) {
                MESSAGEUTILS.sendLang(sender, "request.expired", Map.of("%player%", target.name()));
            }
        }, 1200);

        return true;
    }

    public boolean acceptRequest(Player receiver, String senderName) {
        if (closed) return false;
        CrossServerPacket req = null;
        if (senderName == null) {
            if (incomingRequests.isEmpty()) {
                return false;
            }
            if (incomingRequests.size() > 1) {
                MESSAGEUTILS.sendLang(receiver, "request.multiple", Map.of());
                return true;
            }
            req = incomingRequests.values().iterator().next();
        } else {
            for (CrossServerPacket p : incomingRequests.values()) {
                if (p.getSenderName().equalsIgnoreCase(senderName)) {
                    req = p;
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

        incomingRequests.remove(req.getSenderUuid());

        String tradeId = UUID.randomUUID().toString();
        CrossServerPacket acceptPacket = new CrossServerPacket(CrossServerPacket.Type.ACCEPT);
        acceptPacket.setTradeId(tradeId);
        acceptPacket.setSenderUuid(req.getSenderUuid());
        acceptPacket.setSenderServer(req.getSenderServer());
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
        CrossServerPacket req = null;
        if (senderName == null) {
            if (incomingRequests.isEmpty()) {
                return false;
            }
            req = incomingRequests.values().iterator().next();
        } else {
            for (CrossServerPacket p : incomingRequests.values()) {
                if (p.getSenderName().equalsIgnoreCase(senderName)) {
                    req = p;
                    break;
                }
            }
        }
        if (req == null) return false;

        incomingRequests.remove(req.getSenderUuid());

        CrossServerPacket packet = new CrossServerPacket(CrossServerPacket.Type.DENY);
        packet.setSenderUuid(req.getSenderUuid());
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
        for (CrossServerPacket p : incomingRequests.values()) {
            if (p.getReceiverUuid().equals(receiver.getUniqueId())) {
                list.add(p.getSenderName());
            }
        }
        return list;
    }

    private void startSession(String tradeId, Player localPlayer, UUID remoteUuid, String remoteName, String remoteServer) {
        Scheduler.get().run(localPlayer, task -> {
            try {
                CrossServerTradeSession session = new CrossServerTradeSession(
                        tradeId, localPlayer, remoteUuid, remoteName, remoteServer, this);
                activeSessions.put(tradeId, session);
                Trades.addCrossServerTrade(session);
                MESSAGEUTILS.sendLang(localPlayer, "trade.started", Map.of("%player%", remoteName));
                SoundUtils.playSound(localPlayer, "started");
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not start cross-server trade session: " + ex.getMessage());
            }
        }, () -> {});
    }

    private void subscribeLoop() {
        while (!closed) {
            try (Jedis jedis = pool.getResource()) {
                subscriberJedis = jedis;
                jedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channelName, String message) {
                        try {
                            CrossServerPacket packet = CrossServerPacket.fromJson(message);
                            if (packet == null) return;
                            handlePacket(packet);
                        } catch (Exception ignored) {
                        }
                    }
                }, channel);
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
        if (packet.getType() == null) return;
        // Check if targeted to this server or relevant
        if (packet.getTargetServer() != null && !packet.getTargetServer().equalsIgnoreCase(serverId)) {
            return;
        }

        switch (packet.getType()) {
            case REQUEST -> {
                Player receiver = Bukkit.getPlayer(packet.getReceiverUuid());
                if (receiver == null || !receiver.isOnline() || Trades.isTrading(receiver)
                        || AxTrade.getPreferences().isDisabled(receiver.getUniqueId())) {
                    CrossServerPacket deny = new CrossServerPacket(CrossServerPacket.Type.DENY);
                    deny.setSenderUuid(packet.getSenderUuid());
                    deny.setTargetServer(packet.getSenderServer());
                    deny.setReceiverUuid(packet.getReceiverUuid());
                    deny.setReceiverName(packet.getReceiverName());
                    publish(deny);
                    return;
                }

                incomingRequests.put(packet.getSenderUuid(), packet);

                // Send request message to receiver
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
            case ACCEPT -> {
                Player sender = Bukkit.getPlayer(packet.getSenderUuid());
                if (sender == null || !sender.isOnline()) return;
                outgoingRequests.remove(packet.getReceiverUuid());
                startSession(packet.getTradeId(), sender, packet.getReceiverUuid(), packet.getReceiverName(), packet.getReceiverServer());
            }
            case DENY -> {
                Player sender = Bukkit.getPlayer(packet.getSenderUuid());
                if (sender == null || !sender.isOnline()) return;
                outgoingRequests.remove(packet.getReceiverUuid());
                MESSAGEUTILS.sendLang(sender, "request.deny-sender", Map.of("%player%", packet.getReceiverName()));
                SoundUtils.playSound(sender, "deny");
            }
            case TRADE_ITEMS -> {
                CrossServerTradeSession session = activeSessions.get(packet.getTradeId());
                if (session != null) {
                    List<ItemStack> items = ItemSerialization.deserializeList(packet.getItems());
                    session.onRemoteItemsReceived(items);
                }
            }
            case TRADE_CONFIRM -> {
                CrossServerTradeSession session = activeSessions.get(packet.getTradeId());
                if (session != null) {
                    session.onRemoteConfirm(packet.getConfirmSeconds());
                }
            }
            case TRADE_UNCONFIRM -> {
                CrossServerTradeSession session = activeSessions.get(packet.getTradeId());
                if (session != null) {
                    session.onRemoteUnconfirm();
                }
            }
            case TRADE_ABORT -> {
                CrossServerTradeSession session = activeSessions.get(packet.getTradeId());
                if (session != null) {
                    session.onRemoteAbortReceived();
                }
            }
            case COMMIT_PREPARE -> {
                CrossServerTradeSession session = activeSessions.get(packet.getTradeId());
                if (session != null) {
                    session.onRemoteCommitPrepare();
                }
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        for (Player p : Bukkit.getOnlinePlayers()) {
            unregisterPlayer(p);
        }
        for (CrossServerTradeSession session : activeSessions.values()) {
            session.abort(true);
        }
        activeSessions.clear();
        publishExecutor.shutdownNow();
        if (subscriberJedis != null) {
            try {
                subscriberJedis.close();
            } catch (Exception ignored) {
            }
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
        pool.close();
        instance = null;
    }
}
