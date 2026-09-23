package com.cotii.customlightsplugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Joining players get the current lights (the mod asks for them with a hello; older mods get them a moment after joining).
 */
final class LightPlayerListener implements Listener {
    private final Plugin plugin;
    private final LightEditListener edits;

    LightPlayerListener(Plugin plugin, LightEditListener edits) {
        this.plugin = plugin;
        this.edits = edits;
    }

    @EventHandler
    void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> edits.syncOnce(event.getPlayer()), 40L);
    }

    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        edits.forget(event.getPlayer());
    }
}
