package com.cotii.customlightsplugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/** Sends JSON operations on {@code customlights:sync} (a varint-prefixed UTF-8 string, like a vanilla string). */
final class LightMessenger {
    static final String CHANNEL = "customlights:sync";
    private static final Gson GSON = new Gson();

    private final Plugin plugin;
    private final LightStore store;

    LightMessenger(Plugin plugin, LightStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    /** Everything the player should see: the global lights plus their own (their own win on the same id). */
    void sendFull(Player player) {
        JsonObject root = new JsonObject();
        root.addProperty("op", "full");
        JsonArray lights = new JsonArray();
        JsonArray models = new JsonArray();
        LightStore.Group own = store.playerIfPresent(player.getUniqueId());
        for (var entry : store.global().lights.entrySet()) {
            if (own == null || !own.lights.containsKey(entry.getKey())) {
                lights.add(entry.getValue());
            }
        }
        for (var entry : store.global().models.entrySet()) {
            if (own == null || !own.models.containsKey(entry.getKey())) {
                models.add(entry.getValue());
            }
        }
        if (own != null) {
            own.lights.values().forEach(lights::add);
            own.models.values().forEach(models::add);
        }
        root.add("lights", lights);
        root.add("models", models);
        JsonArray objects = new JsonArray();
        store.global().objects.values().forEach(objects::add);
        root.add("objects", objects);
        JsonArray presets = new JsonArray();
        store.global().presets.forEach((name, preset) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", name);
            entry.add("preset", preset);
            presets.add(entry);
        });
        root.add("presets", presets);
        send(player, root);
    }

    /** Tells a player the plugin is here and whether they may edit the server's lights from the editor. */
    void hello(Player player, boolean editor) {
        JsonObject root = new JsonObject();
        root.addProperty("op", "hello");
        root.addProperty("editor", editor);
        root.addProperty("version", plugin.getDescription().getVersion());
        send(player, root);
    }

    void object(Collection<? extends Player> players, JsonObject rule) {
        JsonObject root = new JsonObject();
        root.addProperty("op", "object");
        root.add("rule", rule);
        send(players, root);
    }

    void objectRemove(Collection<? extends Player> players, String key) {
        JsonObject root = new JsonObject();
        root.addProperty("op", "object_remove");
        root.addProperty("key", key);
        send(players, root);
    }

    void preset(Collection<? extends Player> players, String name, JsonObject preset) {
        JsonObject root = new JsonObject();
        root.addProperty("op", "preset");
        root.addProperty("name", name);
        root.add("preset", preset);
        send(players, root);
    }

    void presetRemove(Collection<? extends Player> players, String name) {
        JsonObject root = new JsonObject();
        root.addProperty("op", "preset_remove");
        root.addProperty("name", name);
        send(players, root);
    }

    void upsert(Collection<? extends Player> players, JsonObject light) {
        JsonObject root = new JsonObject();
        root.addProperty("op", "upsert");
        root.add("light", light);
        send(players, root);
    }

    void remove(Collection<? extends Player> players, String id, Integer fadeOut) {
        JsonObject root = new JsonObject();
        root.addProperty("op", "remove");
        root.addProperty("id", id);
        if (fadeOut != null) {
            root.addProperty("fadeout", fadeOut);
        }
        send(players, root);
    }

    /** Every light (or every model light) of this player is taken away, whatever the client has. */
    void removeAll(Collection<? extends Player> players, boolean models, Integer fadeOut) {
        JsonObject root = new JsonObject();
        root.addProperty("op", models ? "model_remove_all" : "remove_all");
        if (fadeOut != null) {
            root.addProperty("fadeout", fadeOut);
        }
        send(players, root);
    }

    /** Turns a group of lights on or off (or its animations) on the client. */
    void group(Collection<? extends Player> players, String name, Boolean enabled, Boolean animations) {
        JsonObject root = new JsonObject();
        root.addProperty("op", "group");
        root.addProperty("name", name);
        if (enabled != null) {
            root.addProperty("enabled", enabled);
        }
        if (animations != null) {
            root.addProperty("animations", animations);
        }
        send(players, root);
    }

    void move(Collection<? extends Player> players, JsonObject move) {
        move.addProperty("op", "move");
        send(players, move);
    }

    void animate(Collection<? extends Player> players, String id, JsonObject animation) {
        JsonObject root = new JsonObject();
        root.addProperty("op", "animate");
        root.addProperty("id", id);
        root.add("animation", animation);
        send(players, root);
    }

    void targets(Collection<? extends Player> players, String id, JsonArray targetIds) {
        JsonObject root = new JsonObject();
        root.addProperty("op", "targets");
        root.addProperty("id", id);
        root.add("targetIds", targetIds);
        send(players, root);
    }

    void model(Collection<? extends Player> players, JsonObject model) {
        JsonObject root = new JsonObject();
        root.addProperty("op", "model");
        root.add("model", model);
        send(players, root);
    }

    void modelRemove(Collection<? extends Player> players, String id, Integer fadeOut) {
        JsonObject root = new JsonObject();
        root.addProperty("op", "model_remove");
        root.addProperty("id", id);
        if (fadeOut != null) {
            root.addProperty("fadeout", fadeOut);
        }
        send(players, root);
    }

    /** {@code light} null turns the flashlight off. */
    void flashlight(Collection<? extends Player> players, JsonObject light) {
        JsonObject root = new JsonObject();
        root.addProperty("op", "flashlight");
        if (light != null) {
            root.add("light", light);
        }
        send(players, root);
    }

    /** Hands the flashlight the players have on to someone else ({@code holder} has its target and tag entities). */
    void flashlightTarget(Collection<? extends Player> players, JsonObject holder) {
        JsonObject root = new JsonObject();
        root.addProperty("op", "flashlight_target");
        root.add("light", holder);
        send(players, root);
    }

    private void send(Collection<? extends Player> players, JsonObject root) {
        byte[] payload = encodeUtf(GSON.toJson(root));
        for (Player player : players) {
            player.sendPluginMessage(plugin, CHANNEL, payload);
        }
    }

    private void send(Player player, JsonObject root) {
        player.sendPluginMessage(plugin, CHANNEL, encodeUtf(GSON.toJson(root)));
    }

    /** Reads a varint-prefixed UTF-8 string (what the mod sends on customlights:edit). */
    static String decodeUtf(byte[] data) {
        int length = 0;
        int shift = 0;
        int index = 0;
        while (true) {
            if (index >= data.length || shift > 28) {
                throw new IllegalArgumentException("Bad length");
            }
            byte next = data[index++];
            length |= (next & 127) << shift;
            shift += 7;
            if ((next & 128) == 0) {
                break;
            }
        }
        if (length < 0 || index + length > data.length) {
            throw new IllegalArgumentException("Bad length");
        }
        return new String(data, index, length, StandardCharsets.UTF_8);
    }

    private static byte[] encodeUtf(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 5);
        int length = bytes.length;
        while ((length & -128) != 0) {
            out.write(length & 127 | 128);
            length >>>= 7;
        }
        out.write(length);
        out.write(bytes, 0, bytes.length);
        return out.toByteArray();
    }
}
