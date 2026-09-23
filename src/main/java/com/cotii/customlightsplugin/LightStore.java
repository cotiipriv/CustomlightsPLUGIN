package com.cotii.customlightsplugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code plugins/CustomLights/lights.json}: lights for everyone ({@code @a}, also sent to players who join later) and lights
 * for single players.
 */
final class LightStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** The lights of one audience: everyone, or one player. */
    static final class Group {
        final Map<String, JsonObject> lights = new LinkedHashMap<>();
        final Map<String, JsonObject> models = new LinkedHashMap<>();
        /** Object lights (items, blocks, entities...) by key, and shared presets by name: everyone only. */
        final Map<String, JsonObject> objects = new LinkedHashMap<>();
        final Map<String, JsonObject> presets = new LinkedHashMap<>();
        String name;

        JsonObject light(String id) {
            return lights.get(id.toLowerCase(Locale.ROOT));
        }

        void putLight(JsonObject light) {
            lights.put(light.get("id").getAsString().toLowerCase(Locale.ROOT), light);
        }

        JsonObject model(String id) {
            return models.get(id.toLowerCase(Locale.ROOT));
        }

        void putModel(JsonObject model) {
            models.put(model.get("id").getAsString().toLowerCase(Locale.ROOT), model);
        }

        /** Stores an object light; returns its key. */
        String putObject(JsonObject rule) {
            String key = objectKey(rule);
            objects.put(key, rule);
            return key;
        }
    }

    /** Same key as the client mod: kind:target (plus #custom_model_data for items). */
    static String objectKey(JsonObject rule) {
        String kind = rule.has("kind") ? rule.get("kind").getAsString().toLowerCase(Locale.ROOT) : "item";
        String target = rule.has("target") ? rule.get("target").getAsString().toLowerCase(Locale.ROOT) : "";
        int customModelData = rule.has("customModelData") ? rule.get("customModelData").getAsInt() : -1;
        return kind + ":" + target + (kind.equals("item") && customModelData >= 0 ? "#" + customModelData : "");
    }

    private final Plugin plugin;
    private final Path path;
    private final Group global = new Group();
    private final Map<UUID, Group> players = new LinkedHashMap<>();
    private boolean dirty;

    LightStore(Plugin plugin) {
        this.plugin = plugin;
        this.path = plugin.getDataFolder().toPath().resolve("lights.json");
    }

    Group global() {
        return global;
    }

    Group player(OfflinePlayer player) {
        Group group = players.computeIfAbsent(player.getUniqueId(), uuid -> new Group());
        String name = player.getName() == null ? player.getUniqueId().toString() : player.getName();
        if (!name.equals(group.name)) {
            group.name = name;
            dirty = true;
        }
        return group;
    }

    Group playerIfPresent(UUID uuid) {
        return players.get(uuid);
    }

    Collection<Group> allGroups() {
        java.util.List<Group> groups = new java.util.ArrayList<>();
        groups.add(global);
        groups.addAll(players.values());
        return groups;
    }

    Map<UUID, Group> players() {
        return players;
    }

    void markDirty() {
        dirty = true;
    }

    void load() {
        global.lights.clear();
        global.models.clear();
        global.objects.clear();
        global.presets.clear();
        players.clear();
        if (!Files.exists(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                return;
            }
            if (root.has("global")) {
                readGroup(root.getAsJsonObject("global"), global);
            }
            if (root.has("players")) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("players").entrySet()) {
                    Group group = new Group();
                    readGroup(entry.getValue().getAsJsonObject(), group);
                    players.put(UUID.fromString(entry.getKey()), group);
                }
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to load lights.json: " + exception.getMessage());
        }
    }

    private static void readGroup(JsonObject object, Group group) {
        group.name = object.has("name") ? object.get("name").getAsString() : null;
        if (object.has("lights")) {
            for (JsonElement element : object.getAsJsonArray("lights")) {
                group.putLight(element.getAsJsonObject());
            }
        }
        if (object.has("models")) {
            for (JsonElement element : object.getAsJsonArray("models")) {
                group.putModel(element.getAsJsonObject());
            }
        }
        if (object.has("objects")) {
            for (JsonElement element : object.getAsJsonArray("objects")) {
                group.putObject(element.getAsJsonObject());
            }
        }
        if (object.has("presets")) {
            for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("presets").entrySet()) {
                group.presets.put(entry.getKey(), entry.getValue().getAsJsonObject());
            }
        }
    }

    private static JsonObject writeGroup(Group group) {
        JsonObject object = new JsonObject();
        if (group.name != null) {
            object.addProperty("name", group.name);
        }
        JsonArray lights = new JsonArray();
        group.lights.values().forEach(lights::add);
        object.add("lights", lights);
        JsonArray models = new JsonArray();
        group.models.values().forEach(models::add);
        object.add("models", models);
        if (!group.objects.isEmpty()) {
            JsonArray objects = new JsonArray();
            group.objects.values().forEach(objects::add);
            object.add("objects", objects);
        }
        if (!group.presets.isEmpty()) {
            JsonObject presets = new JsonObject();
            group.presets.forEach(presets::add);
            object.add("presets", presets);
        }
        return object;
    }

    void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    void save() {
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.add("global", writeGroup(global));
            JsonObject playerRoot = new JsonObject();
            for (Map.Entry<UUID, Group> entry : players.entrySet()) {
                if (!entry.getValue().lights.isEmpty() || !entry.getValue().models.isEmpty()) {
                    playerRoot.add(entry.getKey().toString(), writeGroup(entry.getValue()));
                }
            }
            root.add("players", playerRoot);
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            dirty = false;
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to save lights.json: " + exception.getMessage());
        }
    }
}
