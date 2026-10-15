package com.artillexstudios.axtrade.storage;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PreferenceListener implements Listener {
    private final PreferencesService preferences;

    public PreferenceListener(PreferencesService preferences) {
        this.preferences = preferences;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        preferences.load(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        preferences.unload(event.getPlayer().getUniqueId());
    }
}
