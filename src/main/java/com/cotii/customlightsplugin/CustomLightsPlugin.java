package com.cotii.customlightsplugin;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class CustomLightsPlugin extends JavaPlugin {
    private LightStore store;

    @Override
    public void onEnable() {
        store = new LightStore(this);
        store.load();
        LightMessenger messenger = new LightMessenger(this, store);

        getServer().getMessenger().registerOutgoingPluginChannel(this, LightMessenger.CHANNEL);
        LightEditListener edits = new LightEditListener(this, store, messenger);
        getServer().getMessenger().registerIncomingPluginChannel(this, LightEditListener.CHANNEL, edits);

        LightCommand command = new LightCommand(this, store, messenger);
        PluginCommand lightPlugin = getCommand("lightplugin");
        if (lightPlugin != null) {
            lightPlugin.setExecutor(command);
            lightPlugin.setTabCompleter(command);
        }

        Bukkit.getPluginManager().registerEvents(new LightPlayerListener(this, edits), this);
        Bukkit.getScheduler().runTaskTimer(this, new TagTargetTicker(store, messenger), 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, edits::checkPermissions, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, store::saveIfDirty, 20L * 30L, 20L * 30L);

        for (Player player : Bukkit.getOnlinePlayers()) {
            Bukkit.getScheduler().runTaskLater(this, () -> edits.syncOnce(player), 20L);
        }
    }

    @Override
    public void onDisable() {
        if (store != null) {
            store.save();
        }
    }
}
