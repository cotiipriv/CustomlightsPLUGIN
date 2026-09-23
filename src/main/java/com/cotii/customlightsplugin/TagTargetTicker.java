package com.cotii.customlightsplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entity tags only exist on the server: once a second, follow lights with a {@code tag:<tag>} target get the UUIDs of the
 * entities carrying that tag, and the clients are told when the list changes.
 */
final class TagTargetTicker implements Runnable {
    private final LightStore store;
    private final LightMessenger messenger;

    TagTargetTicker(LightStore store, LightMessenger messenger) {
        this.store = store;
        this.messenger = messenger;
    }

    @Override
    public void run() {
        refresh(store.global(), new ArrayList<>(Bukkit.getOnlinePlayers()));
        for (Map.Entry<UUID, LightStore.Group> entry : store.players().entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                refresh(entry.getValue(), List.of(player));
            }
        }
    }

    private void refresh(LightStore.Group group, List<Player> recipients) {
        if (recipients.isEmpty()) {
            return;
        }
        for (JsonObject light : group.lights.values()) {
            String target = light.has("target") ? light.get("target").getAsString() : "";
            if (!"follow".equals(light.has("kind") ? light.get("kind").getAsString() : "") || !target.regionMatches(true, 0, "tag:", 0, 4)) {
                continue;
            }
            JsonArray ids = find(target.substring(4));
            if (!ids.equals(light.get("targetIds"))) {
                light.add("targetIds", ids);
                messenger.targets(recipients, light.get("id").getAsString(), ids);
            }
        }
    }

    private static JsonArray find(String tag) {
        JsonArray ids = new JsonArray();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().contains(tag)) {
                    ids.add(entity.getUniqueId().toString());
                }
            }
        }
        return ids;
    }
}
