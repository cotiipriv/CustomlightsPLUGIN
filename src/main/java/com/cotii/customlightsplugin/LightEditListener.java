package com.cotii.customlightsplugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code customlights:edit}: the mod says hello (the player gets everything, and learns whether they may edit), and
 * operators send lights, object lights and presets for everyone from the editor ("Set to everyone").
 */
final class LightEditListener implements PluginMessageListener {
    static final String CHANNEL = "customlights:edit";
    static final String PERMISSION = "customlights.admin";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.+:-]{1,48}");
    private static final Pattern PRESET_NAME = Pattern.compile("[A-Za-z0-9_.+-]{1,48}");

    private final Plugin plugin;
    private final LightStore store;
    private final LightMessenger messenger;
    /** Players who already got the full list this session. */
    private final Set<UUID> synced = new HashSet<>();
    /** Whether each player with the mod may edit, as last told to them. */
    private final Map<UUID, Boolean> editors = new HashMap<>();

    LightEditListener(Plugin plugin, LightStore store, LightMessenger messenger) {
        this.plugin = plugin;
        this.store = store;
        this.messenger = messenger;
    }

    /** Sends the full list once per session (on hello, or a moment after joining for older mods). */
    void syncOnce(Player player) {
        if (player.isOnline() && synced.add(player.getUniqueId())) {
            hello(player);
            messenger.sendFull(player);
        }
    }

    void forget(Player player) {
        synced.remove(player.getUniqueId());
        editors.remove(player.getUniqueId());
    }

    /** Once a second: players whose permission changed (op / deop) are told. */
    void checkPermissions() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Boolean told = editors.get(player.getUniqueId());
            if (told != null && told != player.hasPermission(PERMISSION)) {
                hello(player);
            }
        }
    }

    private void hello(Player player) {
        boolean editor = player.hasPermission(PERMISSION);
        editors.put(player.getUniqueId(), editor);
        messenger.hello(player, editor);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(LightMessenger.decodeUtf(message)).getAsJsonObject();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Ignored a broken CustomLights message from " + player.getName());
            return;
        }
        String op = root.has("op") ? root.get("op").getAsString() : "";
        if (op.equals("hello")) {
            if (synced.contains(player.getUniqueId())) {
                hello(player);
            } else {
                syncOnce(player);
            }
            return;
        }
        if (!player.hasPermission(PERMISSION)) {
            hello(player);
            return;
        }
        try {
            switch (op) {
                case "put_light" -> putLight(player, root);
                case "remove_light" -> removeLight(player, root);
                case "put_object" -> putObject(player, root);
                case "remove_object" -> removeObject(player, root);
                case "put_preset" -> putPreset(player, root);
                case "remove_preset" -> removePreset(player, root);
                default -> plugin.getLogger().warning("Unknown CustomLights edit '" + op + "' from " + player.getName());
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Ignored a broken CustomLights edit from " + player.getName() + ": " + exception.getMessage());
        }
    }

    private static List<Player> everyone() {
        return new ArrayList<>(Bukkit.getOnlinePlayers());
    }

    private void putLight(Player player, JsonObject root) {
        JsonObject light = root.getAsJsonObject("light");
        String type = root.has("type") ? root.get("type").getAsString() : "static";
        light.remove("server");
        if (type.equals("flashlight")) {
            // A flashlight is a saved follow light with the flashlight shape
            type = "follow";
            light.addProperty("shape", "flashlight");
            light.addProperty("fadeIn", 4);
            light.addProperty("fadeOut", 4);
            if (!light.has("id")) {
                light.addProperty("id", "flashlight");
            }
            if (!light.has("target")) {
                light.addProperty("target", "@self");
            }
        }
        String id = light.has("id") ? light.get("id").getAsString() : "";
        if (!ID.matcher(id).matches() || id.equalsIgnoreCase("all")) {
            return;
        }
        light.addProperty("kind", type.equals("follow") ? "follow" : "static");
        if (type.equals("follow")) {
            // Clients never see tags: they get the entities that have it
            String target = light.has("target") ? light.get("target").getAsString() : "";
            if (target.regionMatches(true, 0, "tag:", 0, 4)) {
                light.add("targetIds", LightCommand.taggedEntities(target.substring(4)));
            }
        }
        if (!type.equals("follow") && !light.has("dimension")) {
            light.addProperty("dimension", player.getWorld().getKey().toString());
        }
        boolean isNew = store.global().light(id) == null;
        store.global().putLight(light);
        store.markDirty();
        messenger.upsert(everyone(), light);
        if (isNew) {
            plugin.getLogger().info(player.getName() + " set the light '" + id + "' for everyone");
        }
    }

    private void removeLight(Player player, JsonObject root) {
        String id = root.get("id").getAsString();
        Integer fadeOut = root.has("fadeout") ? root.get("fadeout").getAsInt() : null;
        if (store.global().lights.remove(id.toLowerCase(Locale.ROOT)) != null) {
            store.markDirty();
            messenger.remove(everyone(), id, fadeOut);
            plugin.getLogger().info(player.getName() + " removed the light '" + id + "' for everyone");
        }
    }

    private void putObject(Player player, JsonObject root) {
        JsonObject rule = root.getAsJsonObject("rule");
        if (!rule.has("target") || !rule.has("light")) {
            return;
        }
        boolean isNew = !store.global().objects.containsKey(LightStore.objectKey(rule));
        String key = store.global().putObject(rule);
        store.markDirty();
        messenger.object(everyone(), rule);
        if (isNew) {
            plugin.getLogger().info(player.getName() + " set the object light '" + key + "' for everyone");
        }
    }

    private void removeObject(Player player, JsonObject root) {
        String key = root.get("key").getAsString();
        if (store.global().objects.remove(key) != null) {
            store.markDirty();
            messenger.objectRemove(everyone(), key);
            plugin.getLogger().info(player.getName() + " removed the object light '" + key + "' for everyone");
        }
    }

    private void putPreset(Player player, JsonObject root) {
        String name = root.get("name").getAsString();
        if (!PRESET_NAME.matcher(name).matches()) {
            return;
        }
        JsonObject preset = root.getAsJsonObject("preset");
        store.global().presets.put(name, preset);
        store.markDirty();
        messenger.preset(everyone(), name, preset);
        plugin.getLogger().info(player.getName() + " shared the preset '" + name + "'");
    }

    private void removePreset(Player player, JsonObject root) {
        String name = root.get("name").getAsString();
        if (store.global().presets.remove(name) != null) {
            store.markDirty();
            messenger.presetRemove(everyone(), name);
            plugin.getLogger().info(player.getName() + " stopped sharing the preset '" + name + "'");
        }
    }
}
