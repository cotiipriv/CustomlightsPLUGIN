package com.cotii.customlightsplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** {@code /lightplugin} (alias {@code /lpl}): the client commands with a target after the sub command. */
final class LightCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("set", "remove", "move", "follow", "model_id", "model_remove", "animate", "stretch", "pivot", "angle", "luminosity", "seethrough", "flashlight", "paste",
            "object", "preset", "group", "list", "reload");
    private static final List<String> SHAPES = List.of("sphere", "pad", "cone", "inverted_cone", "spotlight", "beam", "ring", "box", "star",
            "cross", "triangle", "flashlight", "siren", "fire", "portal", "lightning", "aurora", "ripple", "plasma", "tornado", "singularity", "rune", "flare", "mist", "comet", "laser_door");
    private static final Map<String, Float> DEFAULT_PITCH = Map.ofEntries(Map.entry("sphere", -90.0f), Map.entry("pad", -90.0f), Map.entry("cone", 90.0f),
            Map.entry("inverted_cone", -90.0f), Map.entry("spotlight", 90.0f), Map.entry("beam", -90.0f), Map.entry("ring", -90.0f), Map.entry("box", -90.0f),
            Map.entry("star", -90.0f), Map.entry("cross", -90.0f), Map.entry("triangle", -90.0f),
            Map.entry("flashlight", 90.0f), Map.entry("siren", -90.0f), Map.entry("fire", -90.0f), Map.entry("portal", 0.0f),
            Map.entry("lightning", -90.0f), Map.entry("aurora", -90.0f), Map.entry("ripple", -90.0f),
            Map.entry("plasma", -90.0f), Map.entry("tornado", -90.0f), Map.entry("singularity", -90.0f), Map.entry("rune", -90.0f),
            Map.entry("flare", -90.0f), Map.entry("mist", -90.0f), Map.entry("comet", -90.0f), Map.entry("laser_door", -90.0f));
    private static final List<String> ANGLE_SHAPES = List.of("cone", "inverted_cone", "spotlight", "flashlight", "siren");
    private static final float MAX_ANGLE = 170.0f;
    private static final List<String> MOVING_PRESETS = List.of("circle", "random", "orbit", "figure8", "spiral", "bounce", "sway", "float", "zigzag", "firefly");
    private static final List<String> COLORS = List.of("white", "warm", "cold", "red", "crimson", "orange", "lava", "gold", "yellow", "lime", "green",
            "teal", "aqua", "cyan", "blue", "navy", "purple", "magenta", "pink", "soul", "shadow", "black", "party");
    private static final List<String> PRESETS = List.of("circle", "random", "orbit", "figure8", "spiral", "bounce", "sway", "float", "pulse", "flicker",
            "strobe", "breathe", "rainbow", "disco", "sweep", "scan", "wobble", "heartbeat", "candle", "lightning", "zigzag", "pendulum", "police", "glitch",
            "firefly", "ballyhoo", "search", "chase", "grow", "shoot", "iris", "rotation");
    private static final Pattern HEX = Pattern.compile("#?[0-9a-fA-F]{6}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.+:-]{1,48}");
    private static final float MAX_ATMOSPHERE = 10.0f;
    private static final float MAX_INTENSITY = 100.0f;
    private static final float MAX_STRETCH = 64.0f;

    private final CustomLightsPlugin plugin;
    private final LightStore store;
    private final LightMessenger messenger;

    LightCommand(CustomLightsPlugin plugin, LightStore store, LightMessenger messenger) {
        this.plugin = plugin;
        this.store = store;
        this.messenger = messenger;
    }

    private static final class CommandException extends RuntimeException {
        CommandException(String message) {
            super(message);
        }
    }

    /** Who a command is for: everyone (stored globally) or some players (stored for each). */
    private record Selection(boolean global, List<Player> players) {
        List<? extends Player> recipients() {
            return global ? new ArrayList<>(Bukkit.getOnlinePlayers()) : players;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("customlights.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "set" -> set(sender, args, false);
                case "follow" -> set(sender, args, true);
                case "remove" -> remove(sender, args, false);
                case "model_remove" -> remove(sender, args, true);
                case "move" -> move(sender, args);
                case "model_id" -> model(sender, args);
                case "animate" -> animate(sender, args);
                case "stretch" -> stretch(sender, args);
                case "pivot" -> pivot(sender, args);
                case "angle" -> angle(sender, args);
                case "luminosity" -> luminosity(sender, args);
                case "seethrough" -> seeThrough(sender, args);
                case "object" -> objects(sender, args);
                case "preset" -> presets(sender, args);
                case "group" -> group(sender, args);
                case "paste" -> paste(sender, args);
                case "flashlight" -> flashlight(sender, args);
                case "list" -> list(sender, args);
                case "reload" -> {
                    store.load();
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        messenger.sendFull(player);
                    }
                    sender.sendMessage(ChatColor.GOLD + "CustomLights reloaded from lights.json");
                }
                default -> help(sender);
            }
        } catch (CommandException exception) {
            sender.sendMessage(ChatColor.RED + exception.getMessage());
        }
        return true;
    }

    // ── set / follow
    // ───────────────────────────────────────────────

    /**
     * set <targets> <id> <x> <y> <z> <shape> <color> <radius> <distance> <intensity> <atmosphere> <fade_in> <fade_out> [yaw]
     * [pitch] follow <targets> <id> <target> <offX> <offY> <offZ> <shape> ...
     */
    private void set(CommandSender sender, String[] args, boolean follow) {
        int base = follow ? 7 : 6;
        if (args.length < base + 8) {
            throw new CommandException(follow
                    ? "Usage: /lightplugin follow <@a|player> <id> <player|uuid|tag:<tag>> <offX> <offY> <offZ> <shape> <color> <radius> <distance> <intensity> <atmosphere> <fade_in> <fade_out> [yaw] [pitch]"
                    : "Usage: /lightplugin set <@a|player> <id> <x> <y> <z> <shape> <color> <radius> <distance> <intensity> <atmosphere> <fade_in> <fade_out> [yaw] [pitch]");
        }
        Selection selection = select(sender, args[1]);
        String id = id(args[2]);
        JsonObject light = new JsonObject();
        light.addProperty("id", id);
        Location origin = origin(sender, selection);
        if (follow) {
            light.addProperty("kind", "follow");
            String target = followTarget(sender, args[3]);
            light.addProperty("target", target);
            light.addProperty("offsetX", coordinate(args[4], 0.0d, false));
            light.addProperty("offsetY", coordinate(args[5], 0.0d, false));
            light.addProperty("offsetZ", coordinate(args[6], 0.0d, false));
            if (target.regionMatches(true, 0, "tag:", 0, 4)) {
                light.add("targetIds", taggedEntities(target.substring(4)));
            }
        } else {
            light.addProperty("kind", "static");
            light.addProperty("dimension", dimension(origin.getWorld()));
            light.addProperty("x", coordinate(args[3], origin.getX(), true));
            light.addProperty("y", coordinate(args[4], origin.getY(), false));
            light.addProperty("z", coordinate(args[5], origin.getZ(), true));
        }
        String shape = shape(args[base]);
        light.addProperty("shape", shape);
        light.addProperty("color", color(args[base + 1]));
        light.addProperty("radius", number(args[base + 2], 0, 512, "radius"));
        light.addProperty("distance", number(args[base + 3], 0, 512, "distance"));
        light.addProperty("intensity", number(args[base + 4], 0, MAX_INTENSITY, "intensity"));
        light.addProperty("atmosphere", number(args[base + 5], 0, MAX_ATMOSPHERE, "atmosphere"));
        light.addProperty("fadeIn", integer(args[base + 6], "fade_in"));
        light.addProperty("fadeOut", integer(args[base + 7], "fade_out"));
        String[] direction = args;
        if (follow && args.length > base + 8 && isBoolean(args[args.length - 1])) {
            // visible_firstperson, after fade_out or after pitch
            if (!Boolean.parseBoolean(args[args.length - 1])) {
                light.addProperty("visibleFirstPerson", false);
            }
            direction = Arrays.copyOf(args, args.length - 1);
        }
        direction(light, shape, direction, base + 8);

        for (LightStore.Group group : groups(selection)) {
            JsonObject previous = group.light(id);
            JsonObject copy = light.deepCopy();
            // Like the client command: replacing a light keeps its animations and stretch
            copyKept(previous, copy);
            group.putLight(copy);
        }
        store.markDirty();
        for (LightStore.Group group : groups(selection)) {
            messenger.upsert(recipients(selection, group), group.light(id));
        }
        sender.sendMessage(ChatColor.GOLD + "Light '" + id + "' set for " + describe(selection));
    }

    private static boolean isBoolean(String text) {
        return text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false");
    }

    private static void copyKept(JsonObject previous, JsonObject light) {
        if (previous == null) {
            return;
        }
        for (String key : new String[]{"animation", "stretchX", "stretchY", "stretchZ", "pivotX", "pivotY", "pivotZ"}) {
            if (previous.has(key)) {
                light.add(key, previous.get(key).deepCopy());
            }
        }
    }

    private void direction(JsonObject light, String shape, String[] args, int index) {
        float yaw = args.length > index ? number(args[index], -360, 360, "yaw") : 0.0f;
        float pitch = args.length > index + 1 ? number(args[index + 1], -90, 90, "pitch") : DEFAULT_PITCH.get(shape);
        light.addProperty("yaw", yaw);
        light.addProperty("pitch", pitch);
    }

    private static String followTarget(CommandSender sender, String token) {
        if (token.regionMatches(true, 0, "tag:", 0, 4) || token.length() == 36) {
            return token;
        }
        if (token.startsWith("@")) {
            List<Entity> entities = selectEntities(sender, token);
            if (entities.isEmpty()) {
                throw new CommandException("No entity matches " + token);
            }
            Entity entity = entities.get(0);
            return entity instanceof Player player ? player.getName() : entity.getUniqueId().toString();
        }
        return token;
    }

    /** Who holds a flashlight: @self (each player their own) or a follow target, with the entities of a tag. */
    private static void holder(CommandSender sender, String token, JsonObject light) {
        String target = token.equalsIgnoreCase("@self") ? "@self" : followTarget(sender, token);
        light.addProperty("target", target);
        if (target.regionMatches(true, 0, "tag:", 0, 4)) {
            light.add("targetIds", taggedEntities(target.substring(4)));
        }
    }

    static JsonArray taggedEntities(String tag) {
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

    // ── remove / model_remove
    // ──────────────────────────────────────

    private void remove(CommandSender sender, String[] args, boolean model) {
        if (args.length == 2 && args[1].equalsIgnoreCase("all")) {
            // "remove all": every light, for everyone
            args = new String[]{args[0], "@a", "all"};
        }
        if (args.length < 3) {
            throw new CommandException("Usage: /lightplugin " + (model ? "model_remove" : "remove") + " <@a|player> <id|all> [fade_out]");
        }
        Selection selection = select(sender, args[1]);
        String id = args[2];
        Integer fadeOut = args.length > 3 ? integer(args[3], "fade_out") : null;
        int count = 0;
        List<LightStore.Group> targets = new ArrayList<>(groups(selection));
        if (selection.global()) {
            // For everyone: players' own copies go too
            targets.addAll(store.players().values());
        }
        for (LightStore.Group group : targets) {
            Map<String, JsonObject> map = model ? group.models : group.lights;
            List<String> ids = new ArrayList<>();
            if (id.equalsIgnoreCase("all")) {
                count += map.size();
                map.clear();
                // Whatever the client still has of ours goes too
                messenger.removeAll(audience(selection, group), model, fadeOut);
            } else if (map.remove(id.toLowerCase(Locale.ROOT)) != null) {
                ids.add(id);
            }
            for (String removed : ids) {
                if (model) {
                    messenger.modelRemove(audience(selection, group), removed, fadeOut);
                } else {
                    messenger.remove(audience(selection, group), removed, fadeOut);
                }
            }
            count += ids.size();
        }
        store.markDirty();
        if (id.equalsIgnoreCase("all")) {
            sender.sendMessage(ChatColor.GOLD + "Removed every " + (model ? "model light" : "light") + " (" + count + ") for " + describe(selection)
                    + (model ? "" : ChatColor.GRAY + " - object lights: /lightplugin object remove all"));
            return;
        }
        if (count == 0) {
            throw new CommandException("No " + (model ? "model light" : "light") + " '" + id + "' for " + describe(selection));
        }
        sender.sendMessage(ChatColor.GOLD + "Removed " + count + (model ? " model light(s)" : " light(s)") + " for " + describe(selection));
    }

    // ── move
    // ───────────────────────────────────────────────────────

    /** move <targets> <id> <x> <y> <z> <smooth|linear|step|bounce> <time>; ~ stays where it is. */
    private void move(CommandSender sender, String[] args) {
        if (args.length < 8) {
            throw new CommandException("Usage: /lightplugin move <@a|player> <id> <x> <y> <z> <smooth|linear|step|bounce> <time>  (~ stays where it is)");
        }
        Selection selection = select(sender, args[1]);
        String id = args[2];
        String easing = easing(args[6]);
        int time = integer(args[7], "time");
        int moved = 0;
        for (LightStore.Group group : groups(selection)) {
            JsonObject light = group.light(id);
            if (light == null) {
                continue;
            }
            boolean follow = "follow".equals(light.get("kind").getAsString());
            String keyX = follow ? "offsetX" : "x", keyY = follow ? "offsetY" : "y", keyZ = follow ? "offsetZ" : "z";
            JsonObject move = new JsonObject();
            move.addProperty("id", light.get("id").getAsString());
            move.addProperty("x", coordinate(args[3], light.get(keyX).getAsDouble(), !follow));
            move.addProperty("y", coordinate(args[4], light.get(keyY).getAsDouble(), false));
            move.addProperty("z", coordinate(args[5], light.get(keyZ).getAsDouble(), !follow));
            move.addProperty("ease", easing);
            move.addProperty("time", time);
            // The stored light jumps to the end of the move: players joining later see it there
            light.add(keyX, move.get("x"));
            light.add(keyY, move.get("y"));
            light.add(keyZ, move.get("z"));
            for (String key : new String[]{"color", "radius", "distance", "intensity", "atmosphere"}) {
                if (move.has(key)) {
                    light.add(key, move.get(key));
                }
            }
            messenger.move(recipients(selection, group), move);
            moved++;
        }
        if (moved == 0) {
            throw new CommandException("No light '" + id + "' for " + describe(selection));
        }
        store.markDirty();
        sender.sendMessage(ChatColor.GOLD + "Moving '" + id + "' " + easing + " over " + time + "t for " + describe(selection));
    }

    // ── model_id
    // ───────────────────────────────────────────────────

    /**
     * model_id <targets> <id> <x> <y> <z> <shape> <color> <radius> <opacity> <light> <fade_in> <fade_out> [distance] [yaw]
     * [pitch].
     */
    private void model(CommandSender sender, String[] args) {
        if (args.length < 13) {
            throw new CommandException("Usage: /lightplugin model_id <@a|player> <id> <x> <y> <z> <shape> <color> <radius> <opacity> <light> <fade_in> <fade_out> [distance] [yaw] [pitch]");
        }
        Selection selection = select(sender, args[1]);
        String id = id(args[2]).toLowerCase(Locale.ROOT);
        JsonObject model = new JsonObject();
        model.addProperty("id", id);
        model.addProperty("kind", "static");
        model.addProperty("x", coordinate(args[3], 0.0d, false));
        model.addProperty("y", coordinate(args[4], 0.0d, false));
        model.addProperty("z", coordinate(args[5], 0.0d, false));
        String shape = shape(args[6]);
        model.addProperty("shape", shape);
        model.addProperty("color", color(args[7]));
        float radius = number(args[8], 0, 512, "radius");
        model.addProperty("radius", radius);
        model.addProperty("atmosphere", number(args[9], 0, 1, "opacity") * MAX_ATMOSPHERE);
        model.addProperty("intensity", number(args[10], 0, MAX_INTENSITY, "light"));
        model.addProperty("fadeIn", integer(args[11], "fade_in"));
        model.addProperty("fadeOut", integer(args[12], "fade_out"));
        float distance;
        if (args.length > 13) {
            distance = number(args[13], 0, 512, "distance");
        } else {
            distance = Math.max(0.5f, radius);
        }
        model.addProperty("distance", distance);
        direction(model, shape, args, 14);
        for (LightStore.Group group : groups(selection)) {
            JsonObject copy = model.deepCopy();
            copyKept(group.model(id), copy);
            group.putModel(copy);
            messenger.model(recipients(selection, group), copy);
        }
        store.markDirty();
        sender.sendMessage(ChatColor.GOLD + "Model light '" + id + "' set for " + describe(selection) + " (bone/group: customlights_" + id + ")");
    }

    // ── stretch
    // ────────────────────────────────────────────────────

    /** stretch <targets> <id> <x> <y> <z>. */
    private void stretch(CommandSender sender, String[] args) {
        if (args.length < 6) {
            throw new CommandException("Usage: /lightplugin stretch <@a|player> <id> <x> <y> <z>");
        }
        Selection selection = select(sender, args[1]);
        String id = args[2];
        float x = number(args[3], -MAX_STRETCH, MAX_STRETCH, "x");
        float y = number(args[4], -MAX_STRETCH, MAX_STRETCH, "y");
        float z = number(args[5], -MAX_STRETCH, MAX_STRETCH, "z");
        int changed = 0;
        for (LightStore.Group group : groups(selection)) {
            JsonObject light = group.light(id);
            boolean model = false;
            if (light == null) {
                light = group.model(id);
                model = true;
            }
            if (light == null) {
                continue;
            }
            light.addProperty("stretchX", x);
            light.addProperty("stretchY", y);
            light.addProperty("stretchZ", z);
            if (model) {
                messenger.model(recipients(selection, group), light);
            } else {
                messenger.upsert(recipients(selection, group), light);
            }
            changed++;
        }
        if (changed == 0) {
            throw new CommandException("No light or model light '" + id + "' for " + describe(selection));
        }
        store.markDirty();
        sender.sendMessage(ChatColor.GOLD + "Stretched '" + id + "' for " + describe(selection));
    }

    /** pivot <targets> <id> <x> <y> <z>: where on the light's own axes the stretch grows from. */
    private void pivot(CommandSender sender, String[] args) {
        if (args.length < 6) {
            throw new CommandException("Usage: /lightplugin pivot <@a|player> <id> <x> <y> <z>");
        }
        Selection selection = select(sender, args[1]);
        String id = args[2];
        float x = number(args[3], -MAX_STRETCH, MAX_STRETCH, "x");
        float y = number(args[4], -MAX_STRETCH, MAX_STRETCH, "y");
        float z = number(args[5], -MAX_STRETCH, MAX_STRETCH, "z");
        int changed = 0;
        for (LightStore.Group group : groups(selection)) {
            JsonObject light = group.light(id);
            boolean model = false;
            if (light == null) {
                light = group.model(id);
                model = true;
            }
            if (light == null) {
                continue;
            }
            light.addProperty("pivotX", x);
            light.addProperty("pivotY", y);
            light.addProperty("pivotZ", z);
            if (model) {
                messenger.model(recipients(selection, group), light);
            } else {
                messenger.upsert(recipients(selection, group), light);
            }
            changed++;
        }
        if (changed == 0) {
            throw new CommandException("No light or model light '" + id + "' for " + describe(selection));
        }
        store.markDirty();
        sender.sendMessage(ChatColor.GOLD + "Pivot of '" + id + "' moved for " + describe(selection));
    }

    /** angle <targets> <id> <degrees>: how wide cones, spotlights, flashlights and sirens open. */
    /** luminosity <targets> <id> <amount>: how much the light also makes what it reaches glow by itself. */
    private void luminosity(CommandSender sender, String[] args) {
        if (args.length < 4) {
            throw new CommandException("Usage: /lightplugin luminosity <@a|player> <id> <amount>");
        }
        Selection selection = select(sender, args[1]);
        String id = args[2];
        float amount = number(args[3], 0, 10, "amount");
        int changed = 0;
        for (LightStore.Group group : groups(selection)) {
            JsonObject light = group.light(id);
            boolean model = false;
            if (light == null) {
                light = group.model(id);
                model = true;
            }
            if (light == null) {
                continue;
            }
            light.addProperty("luminosity", amount);
            if (model) {
                messenger.model(recipients(selection, group), light);
            } else {
                messenger.upsert(recipients(selection, group), light);
            }
            changed++;
        }
        if (changed == 0) {
            throw new CommandException("No light '" + id + "' for " + describe(selection));
        }
        store.markDirty();
        sender.sendMessage(ChatColor.GOLD + "Luminosity of '" + id + "' set for " + describe(selection));
    }

    private void angle(CommandSender sender, String[] args) {
        if (args.length < 4) {
            throw new CommandException("Usage: /lightplugin angle <@a|player> <id> <degrees>");
        }
        Selection selection = select(sender, args[1]);
        String id = args[2];
        float degrees = number(args[3], 1, MAX_ANGLE, "degrees");
        int changed = 0;
        for (LightStore.Group group : groups(selection)) {
            JsonObject light = group.light(id);
            boolean model = false;
            if (light == null) {
                light = group.model(id);
                model = true;
            }
            if (light == null || !ANGLE_SHAPES.contains(light.has("shape") ? light.get("shape").getAsString() : "sphere")) {
                continue;
            }
            light.addProperty("angle", degrees);
            if (model) {
                messenger.model(recipients(selection, group), light);
            } else {
                messenger.upsert(recipients(selection, group), light);
            }
            changed++;
        }
        if (changed == 0) {
            throw new CommandException("No cone, spotlight, flashlight or siren '" + id + "' for " + describe(selection));
        }
        store.markDirty();
        sender.sendMessage(ChatColor.GOLD + "Angle of '" + id + "' set for " + describe(selection));
    }

    /** seethrough <targets> <id> <true|false>: whether solid blocks stop the light. */
    private void seeThrough(CommandSender sender, String[] args) {
        if (args.length < 4) {
            throw new CommandException("Usage: /lightplugin seethrough <@a|player> <id> <true|false>");
        }
        Selection selection = select(sender, args[1]);
        String id = args[2];
        boolean value;
        if (args[3].equalsIgnoreCase("true")) {
            value = true;
        } else if (args[3].equalsIgnoreCase("false")) {
            value = false;
        } else {
            throw new CommandException("Use true or false");
        }
        int changed = 0;
        for (LightStore.Group group : groups(selection)) {
            JsonObject light = group.light(id);
            boolean model = false;
            if (light == null) {
                light = group.model(id);
                model = true;
            }
            if (light == null) {
                continue;
            }
            if (value) {
                light.remove("seethrough");
            } else {
                light.addProperty("seethrough", false);
            }
            if (model) {
                messenger.model(recipients(selection, group), light);
            } else {
                messenger.upsert(recipients(selection, group), light);
            }
            changed++;
        }
        if (changed == 0) {
            throw new CommandException("No light '" + id + "' for " + describe(selection));
        }
        store.markDirty();
        sender.sendMessage(ChatColor.GOLD + "'" + id + "' " + (value ? "passes through blocks" : "is stopped by solid blocks") + " for " + describe(selection));
    }

    /**
     * group <targets> list | add <group> [ids...] | remove <group> [ids...] | modify <group> on|off|animations <on|off>
     * Groups live on the clients (they are shown in the editor): the lights carry their group name, and modify turns a group
     * on or off for the players.
     */
    private void group(CommandSender sender, String[] args) {
        if (args.length < 3) {
            throw new CommandException("Usage: /lightplugin group <@a|player> <list|add|remove|modify> ...");
        }
        Selection selection = select(sender, args[1]);
        String action = args[2].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            for (LightStore.Group group : groups(selection)) {
                Map<String, List<String>> byGroup = new LinkedHashMap<>();
                for (JsonObject light : group.lights.values()) {
                    String name = light.has("group") ? light.get("group").getAsString() : "";
                    byGroup.computeIfAbsent(name.isEmpty() ? "(no group)" : name, ignored -> new ArrayList<>())
                            .add(light.get("id").getAsString());
                }
                sender.sendMessage(ChatColor.GOLD + "Groups of " + describe(selection) + ": " + byGroup.size());
                byGroup.forEach((name, ids) -> sender.sendMessage(ChatColor.GRAY + " • " + ChatColor.WHITE + name + ChatColor.GRAY + ": "
                        + String.join(", ", ids)));
            }
            return;
        }
        if (args.length < 4) {
            throw new CommandException("Usage: /lightplugin group <@a|player> <add|remove|modify> <group> ...");
        }
        String name = args[3];
        if (!action.equals("modify") && !ID.matcher(name).matches()) {
            throw new CommandException("Group names use letters, numbers and _ - . + : (max 48)");
        }
        switch (action) {
            case "add", "remove" -> {
                boolean adding = action.equals("add");
                List<String> ids = new ArrayList<>(Arrays.asList(args).subList(Math.min(4, args.length), args.length));
                int changed = 0;
                for (LightStore.Group group : groups(selection)) {
                    List<JsonObject> lights = new ArrayList<>();
                    if (ids.isEmpty()) {
                        // No ids: every light of this group name (remove) or every light (add)
                        for (JsonObject light : group.lights.values()) {
                            String light_group = light.has("group") ? light.get("group").getAsString() : "";
                            if (adding || light_group.equalsIgnoreCase(name)) {
                                lights.add(light);
                            }
                        }
                    } else {
                        for (String id : ids) {
                            JsonObject light = group.light(id);
                            if (light == null) {
                                throw new CommandException("No light '" + id + "' for " + describe(selection));
                            }
                            lights.add(light);
                        }
                    }
                    for (JsonObject light : lights) {
                        if (adding) {
                            light.addProperty("group", name);
                        } else {
                            light.remove("group");
                        }
                        messenger.upsert(recipients(selection, group), light);
                        changed++;
                    }
                }
                store.markDirty();
                sender.sendMessage(ChatColor.GOLD + (adding ? "Put " : "Took ") + changed + " light(s) " + (adding ? "in" : "out of")
                        + " group '" + name + "' for " + describe(selection));
            }
            case "modify" -> {
                if (args.length < 5) {
                    throw new CommandException("Usage: /lightplugin group <@a|player> modify <group> <on|off|animations <on|off>>");
                }
                String what = args[4].toLowerCase(Locale.ROOT);
                Boolean enabled = null;
                Boolean animations = null;
                if (what.equals("on") || what.equals("off")) {
                    enabled = what.equals("on");
                } else if (what.equals("animations")) {
                    if (args.length < 6 || !(args[5].equalsIgnoreCase("on") || args[5].equalsIgnoreCase("off"))) {
                        throw new CommandException("Usage: /lightplugin group <@a|player> modify <group> animations <on|off>");
                    }
                    animations = args[5].equalsIgnoreCase("on");
                } else {
                    throw new CommandException("Use on, off or animations <on|off>");
                }
                messenger.group(selection.recipients(), name, enabled, animations);
                sender.sendMessage(ChatColor.GOLD + "Group '" + name + "': " + (enabled != null ? (enabled ? "on" : "off")
                        : "animations " + (animations ? "on" : "off")) + " for " + describe(selection));
            }
            default -> throw new CommandException("Usage: /lightplugin group <@a|player> <list|add|remove|modify> ...");
        }
    }

    /** object list | object remove <key|all>: object lights set for everyone from the editor. */
    private void objects(CommandSender sender, String[] args) {
        String action = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        Map<String, JsonObject> objects = store.global().objects;
        switch (action) {
            case "list" -> {
                sender.sendMessage(ChatColor.GOLD + "Object lights for everyone: " + objects.size());
                for (String key : objects.keySet()) {
                    sender.sendMessage(ChatColor.GRAY + " • " + ChatColor.WHITE + key);
                }
            }
            case "remove" -> {
                if (args.length < 3) {
                    throw new CommandException("Usage: /lightplugin object remove <key|all>");
                }
                List<String> keys = args[2].equalsIgnoreCase("all") ? new ArrayList<>(objects.keySet()) : List.of(args[2].toLowerCase(Locale.ROOT));
                int removed = 0;
                for (String key : keys) {
                    if (objects.remove(key) != null) {
                        messenger.objectRemove(Bukkit.getOnlinePlayers(), key);
                        removed++;
                    }
                }
                if (removed == 0) {
                    throw new CommandException("No object light '" + args[2] + "'");
                }
                store.markDirty();
                sender.sendMessage(ChatColor.GOLD + "Removed " + removed + " object light(s) for everyone");
            }
            default -> throw new CommandException("Usage: /lightplugin object <list|remove <key|all>>");
        }
    }

    /** preset list | preset remove <name|all>: presets shared from the editor. */
    private void presets(CommandSender sender, String[] args) {
        String action = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        Map<String, JsonObject> presets = store.global().presets;
        switch (action) {
            case "list" -> {
                sender.sendMessage(ChatColor.GOLD + "Shared presets: " + presets.size());
                presets.forEach((name, preset) -> sender.sendMessage(ChatColor.GRAY + " • " + ChatColor.WHITE + name + ChatColor.GRAY + " ("
                        + (preset.has("objects") ? preset.getAsJsonArray("objects").size() : 0) + " objects)"));
            }
            case "remove" -> {
                if (args.length < 3) {
                    throw new CommandException("Usage: /lightplugin preset remove <name|all>");
                }
                List<String> names = args[2].equalsIgnoreCase("all") ? new ArrayList<>(presets.keySet()) : List.of(args[2]);
                int removed = 0;
                for (String name : names) {
                    if (presets.remove(name) != null) {
                        messenger.presetRemove(Bukkit.getOnlinePlayers(), name);
                        removed++;
                    }
                }
                if (removed == 0) {
                    throw new CommandException("No shared preset '" + args[2] + "'");
                }
                store.markDirty();
                sender.sendMessage(ChatColor.GOLD + "Stopped sharing " + removed + " preset(s)");
            }
            default -> throw new CommandException("Usage: /lightplugin preset <list|remove <name|all>>");
        }
    }

    // ── animate
    // ────────────────────────────────────────────────────

    /**
     * animate <targets> <id> <preset[+preset]> [time] [amount] | add <preset> [time] [amount] | remove <preset> |
     * color_transition <c1> <c2> [c3...] <time> | size <radius> <distance> <time> | animation_radius <radius> [time] | stop.
     */
    private static final List<String> EASINGS = List.of("smooth", "linear", "step", "bounce");

    private static String easing(String text) {
        String easing = text.toLowerCase(Locale.ROOT);
        if (!EASINGS.contains(easing)) {
            throw new CommandException("Use " + String.join(", ", EASINGS));
        }
        return easing;
    }

    private void animate(CommandSender sender, String[] args) {
        if (args.length < 4) {
            throw new CommandException("Usage: /lightplugin animate <@a|player> <id> <preset[+preset]> [time] [amount] | add <preset> [time] [amount] | remove <preset> | color_transition <c1> <c2> [c3...] <time> | size <radius> <distance> <time> | animation_radius <radius> [time] | stop");
        }
        Selection selection = select(sender, args[1]);
        String id = args[2];
        String mode = args[3].toLowerCase(Locale.ROOT);
        int changed = 0;
        for (LightStore.Group group : groups(selection)) {
            JsonObject light = group.light(id);
            if (light == null) {
                light = group.model(id);
            }
            if (light == null) {
                continue;
            }
            JsonObject animation = light.has("animation") ? light.getAsJsonObject("animation") : new JsonObject();
            switch (mode) {
                case "stop" -> animation = new JsonObject();
                case "color_transition" -> {
                    animation.add("colors", transitionColors(args));
                    animation.remove("colorTime");
                }
                case "size" -> {
                    if (args.length < 7) {
                        throw new CommandException("Use: size <radius> <distance> <time>");
                    }
                    animation.addProperty("sizeRadius", number(args[4], 0, 512, "radius"));
                    animation.addProperty("sizeDistance", number(args[5], 0, 512, "distance"));
                    animation.addProperty("sizeTime", Math.max(1, integer(args[6], "time")));
                }
                case "animation_radius" -> {
                    if (args.length < 5) {
                        throw new CommandException("Use: animation_radius <radius> [time]");
                    }
                    float radius = number(args[4], 0, 512, "radius");
                    int time = args.length > 5 ? integer(args[5], "time") : 0;
                    if (time == 0) {
                        Map<String, JsonObject> presets = presets(animation);
                        for (JsonObject entry : presets.values()) {
                            if (MOVING_PRESETS.contains(entry.get("preset").getAsString())) {
                                entry.addProperty("amount", radius);
                            }
                        }
                        writePresets(animation, presets);
                        animation.remove("radiusPulseTime");
                    } else {
                        animation.addProperty("radiusPulseTarget", radius);
                        animation.addProperty("radiusPulseTime", time);
                    }
                }
                case "remove" -> {
                    if (args.length < 5) {
                        throw new CommandException("Use: remove <preset>");
                    }
                    Map<String, JsonObject> presets = presets(animation);
                    presets.remove(args[4].toLowerCase(Locale.ROOT));
                    writePresets(animation, presets);
                }
                case "add" -> {
                    if (args.length < 5) {
                        throw new CommandException("Use: add <preset> [time] [amount]");
                    }
                    Map<String, JsonObject> presets = presets(animation);
                    putPresets(presets, args[4], args, 5);
                    writePresets(animation, presets);
                }
                default -> {
                    Map<String, JsonObject> presets = presets(animation);
                    presets.keySet().retainAll(presetNames(mode));
                    putPresets(presets, mode, args, 4);
                    writePresets(animation, presets);
                }
            }
            light.add("animation", animation);
            messenger.animate(recipients(selection, group), light.get("id").getAsString(), animation);
            changed++;
        }
        if (changed == 0) {
            throw new CommandException("No light or model light '" + id + "' for " + describe(selection));
        }
        store.markDirty();
        sender.sendMessage(ChatColor.GOLD + "Animation updated on '" + id + "' for " + describe(selection));
    }

    private static List<String> presetNames(String text) {
        List<String> names = new ArrayList<>();
        for (String part : text.toLowerCase(Locale.ROOT).split("[+,]")) {
            if (!PRESETS.contains(part)) {
                throw new CommandException("Unknown preset '" + part + "': " + String.join(", ", PRESETS));
            }
            names.add(part);
        }
        return names;
    }

    /** Adds or updates presets; [time] [amount] at args[index], missing values keep the current ones. */
    private static void putPresets(Map<String, JsonObject> presets, String text, String[] args, int index) {
        for (String name : presetNames(text)) {
            JsonObject entry = presets.computeIfAbsent(name, key -> {
                JsonObject created = new JsonObject();
                created.addProperty("preset", key);
                created.addProperty("time", 40);
                return created;
            });
            if (args.length > index) {
                entry.addProperty("time", Math.max(1, integer(args[index], "time")));
            }
            if (args.length > index + 1) {
                entry.addProperty("amount", number(args[index + 1], 0, 720, "amount"));
            }
        }
    }

    /** The animation's presets by name (the older single "preset" format is converted). */
    private static Map<String, JsonObject> presets(JsonObject animation) {
        Map<String, JsonObject> presets = new LinkedHashMap<>();
        if (animation.has("presets")) {
            for (JsonElement element : animation.getAsJsonArray("presets")) {
                JsonObject entry = element.getAsJsonObject();
                presets.put(entry.get("preset").getAsString(), entry);
            }
        } else if (animation.has("preset")) {
            int time = animation.has("presetTime") ? animation.get("presetTime").getAsInt() : 40;
            for (String name : animation.get("preset").getAsString().split("[+,]")) {
                JsonObject entry = new JsonObject();
                entry.addProperty("preset", name);
                entry.addProperty("time", time);
                presets.put(name, entry);
            }
        }
        return presets;
    }

    private static void writePresets(JsonObject animation, Map<String, JsonObject> presets) {
        animation.remove("preset");
        animation.remove("presetTime");
        animation.remove("animationRadius");
        JsonArray array = new JsonArray();
        presets.values().forEach(array::add);
        animation.add("presets", array);
    }

    /**
     * {@code color_transition <color> <ticks> <color> <ticks> ...} (each color and the ticks it takes to blend into the
     * next), or the older {@code <color> <color> ...
     */
    private static JsonArray transitionColors(String[] args) {
        List<String> parts = new ArrayList<>(Arrays.asList(args).subList(4, args.length));
        JsonArray colors = new JsonArray();
        boolean paired = parts.size() >= 4 && parts.size() % 2 == 0 && parts.get(1).matches("\\d+");
        if (paired) {
            for (int i = 0; i < parts.size(); i += 2) {
                colors.add(color(parts.get(i)) + ":" + Math.max(1, integer(parts.get(i + 1), "ticks")));
            }
        } else {
            if (parts.size() < 3) {
                throw new CommandException("Use: color_transition <color1> <ticks1> <color2> <ticks2> [...]");
            }
            int loop = Math.max(1, integer(parts.get(parts.size() - 1), "time"));
            int each = Math.max(1, loop / (parts.size() - 1));
            for (int i = 0; i < parts.size() - 1; i++) {
                colors.add(color(parts.get(i)) + ":" + each);
            }
        }
        return colors;
    }

    // ── paste
    // ──────────────────────────────────────────────────────

    /** paste <targets> {(...)(...)}: lights copied from the client editor or /customlight copy. */
    private void paste(CommandSender sender, String[] args) {
        if (args.length < 3) {
            throw new CommandException("Usage: /lightplugin paste <@a|player> {(type:static,id:...,...)}");
        }
        Selection selection = select(sender, args[1]);
        String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        List<PasteFormat.Entry> entries;
        try {
            entries = PasteFormat.parse(text);
        } catch (IllegalArgumentException exception) {
            throw new CommandException("Paste failed: " + exception.getMessage());
        }
        Location origin = origin(sender, selection);
        List<String> ids = new ArrayList<>();
        for (PasteFormat.Entry entry : entries) {
            JsonObject light = entry.light();
            if (light.has("shape")) {
                shape(light.get("shape").getAsString());
            }
            if (light.has("color")) {
                light.addProperty("color", color(light.get("color").getAsString()));
            }
            if (!light.has("pitch")) {
                light.addProperty("pitch", DEFAULT_PITCH.getOrDefault(light.has("shape") ? light.get("shape").getAsString() : "sphere", 0.0f));
            }
            switch (entry.type()) {
                case "model" -> {
                    String id = id(light.get("id").getAsString()).toLowerCase(Locale.ROOT);
                    light.addProperty("id", id);
                    for (LightStore.Group group : groups(selection)) {
                        JsonObject copy = light.deepCopy();
                        group.putModel(copy);
                        messenger.model(recipients(selection, group), copy);
                    }
                    ids.add(id);
                }
                default -> {
                    if (entry.type().equals("flashlight")) {
                        // A flashlight is a saved follow light with the flashlight shape
                        light.addProperty("kind", "follow");
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
                    String id = id(light.get("id").getAsString());
                    if (entry.type().equals("static")) {
                        light.addProperty("dimension", dimension(origin.getWorld()));
                    } else {
                        String target = light.get("target").getAsString();
                        if (target.regionMatches(true, 0, "tag:", 0, 4)) {
                            light.add("targetIds", taggedEntities(target.substring(4)));
                        }
                    }
                    for (LightStore.Group group : groups(selection)) {
                        JsonObject copy = light.deepCopy();
                        group.putLight(copy);
                        messenger.upsert(recipients(selection, group), copy);
                    }
                    ids.add(id);
                }
            }
        }
        store.markDirty();
        sender.sendMessage(ChatColor.GOLD + "Pasted " + String.join(", ", ids) + " for " + describe(selection));
    }

    // ── flashlight
    // ─────────────────────────────────────────────────

    /**
     * flashlight <targets> <id> <holder> <color> <scale> <distance> [intensity] [atmosphere] (angle: /lightplugin angle) |
     * flashlight <targets> off.
     */
    private void flashlight(CommandSender sender, String[] args) {
        if (args.length < 3) {
            throw new CommandException("Usage: /lightplugin flashlight <@a|player> <id> <holder> <color> <scale> <distance> [intensity] [atmosphere] | off");
        }
        if (args[2].equalsIgnoreCase("off")) {
            Selection selection = select(sender, args[1]);
            int count = 0;
            for (String id : flashlightIds(args[1])) {
                remove(sender, new String[]{"remove", args[1], id, "4"}, false);
                count++;
            }
            if (count == 0) {
                sender.sendMessage(ChatColor.GOLD + "No flashlights for " + describe(selection));
            }
            return;
        }
        if (args.length < 6) {
            throw new CommandException("Usage: /lightplugin flashlight <@a|player> <id> <holder> <color> <scale> <distance> [intensity] [atmosphere]");
        }
        // The same light a follow command makes, with the flashlight shape
        String[] follow = new String[]{"follow", args[1], args[2], args[3], "0", "0", "0", "flashlight", args[4], args[5], args[6],
                args.length > 7 ? args[7] : "2.8", args.length > 8 ? args[8] : "0.35", "4", "4"};
        set(sender, follow, true);
    }

    /** Ids of the stored flashlights of a target. */
    private List<String> flashlightIds(String token) {
        List<String> ids = new ArrayList<>();
        Player player = Bukkit.getPlayerExact(token);
        LightStore.Group group = player == null ? null : store.playerIfPresent(player.getUniqueId());
        for (String id : idsFor(token, false)) {
            JsonObject light = store.global().light(id);
            if (light == null && group != null) {
                light = group.light(id);
            }
            if (light != null && light.has("shape") && light.get("shape").getAsString().equalsIgnoreCase("flashlight")) {
                ids.add(id);
            }
        }
        return ids;
    }

    // ── list
    // ───────────────────────────────────────────────────────

    private void list(CommandSender sender, String[] args) {
        if (args.length < 2) {
            printGroup(sender, "Everyone (@a)", store.global());
            for (var entry : store.players().entrySet()) {
                if (!entry.getValue().lights.isEmpty() || !entry.getValue().models.isEmpty()) {
                    printGroup(sender, entry.getValue().name == null ? entry.getKey().toString() : entry.getValue().name, entry.getValue());
                }
            }
            return;
        }
        Selection selection = select(sender, args[1]);
        for (LightStore.Group group : groups(selection)) {
            printGroup(sender, selection.global() ? "Everyone (@a)" : group.name, group);
        }
    }

    private static void printGroup(CommandSender sender, String title, LightStore.Group group) {
        sender.sendMessage(ChatColor.GOLD + title + ": " + group.lights.size() + " light(s), " + group.models.size() + " model light(s)"
                + (group.objects.isEmpty() ? "" : ", " + group.objects.size() + " object light(s)")
                + (group.presets.isEmpty() ? "" : ", " + group.presets.size() + " shared preset(s)"));
        for (JsonObject light : group.lights.values()) {
            String where = "follow".equals(text(light, "kind", "static"))
                    ? "follows " + text(light, "target", "?")
                    : String.format(Locale.ROOT, "%.1f %.1f %.1f (%s)", decimal(light, "x"), decimal(light, "y"), decimal(light, "z"),
                    text(light, "dimension", "any"));
            sender.sendMessage(ChatColor.GRAY + " • " + ChatColor.WHITE + text(light, "id", "?") + ChatColor.GRAY + " "
                    + text(light, "shape", "sphere") + " " + text(light, "color", "white") + " " + where);
        }
        for (JsonObject model : group.models.values()) {
            sender.sendMessage(ChatColor.GRAY + " • model " + ChatColor.WHITE + text(model, "id", "?") + ChatColor.GRAY + " "
                    + text(model, "shape", "sphere") + " " + text(model, "color", "white"));
        }
    }

    private static String text(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static double decimal(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsDouble() : 0.0d;
    }

    // ── Helpers
    // ────────────────────────────────────────────────────

    private Selection select(CommandSender sender, String token) {
        if (token.equalsIgnoreCase("@a") || token.equalsIgnoreCase("all") || token.equals("*")) {
            return new Selection(true, List.of());
        }
        if (!token.startsWith("@")) {
            Player player = Bukkit.getPlayerExact(token);
            if (player == null) {
                throw new CommandException("Player '" + token + "' is not online");
            }
            return new Selection(false, List.of(player));
        }
        List<Player> players = new ArrayList<>();
        for (Entity entity : selectEntities(sender, token)) {
            if (entity instanceof Player player) {
                players.add(player);
            }
        }
        if (players.isEmpty()) {
            throw new CommandException("No player matches " + token);
        }
        return new Selection(false, players);
    }

    private static List<Entity> selectEntities(CommandSender sender, String selector) {
        try {
            return Bukkit.selectEntities(sender, selector);
        } catch (IllegalArgumentException exception) {
            throw new CommandException("Invalid selector " + selector + ": " + exception.getMessage());
        }
    }

    private List<LightStore.Group> groups(Selection selection) {
        if (selection.global()) {
            return List.of(store.global());
        }
        List<LightStore.Group> groups = new ArrayList<>();
        for (Player player : selection.players()) {
            groups.add(store.player(player));
        }
        return groups;
    }

    /** Who hears a removal: everyone for the global group, otherwise the group's player when online. */
    private Collection<? extends Player> audience(Selection selection, LightStore.Group group) {
        if (group == store.global()) {
            return Bukkit.getOnlinePlayers();
        }
        for (Map.Entry<java.util.UUID, LightStore.Group> entry : store.players().entrySet()) {
            if (entry.getValue() == group) {
                Player player = Bukkit.getPlayer(entry.getKey());
                return player == null ? List.of() : List.of(player);
            }
        }
        return recipients(selection, group);
    }

    /** The online players a group's changes go to. */
    private Collection<? extends Player> recipients(Selection selection, LightStore.Group group) {
        if (selection.global()) {
            return Bukkit.getOnlinePlayers();
        }
        for (Player player : selection.players()) {
            if (store.playerIfPresent(player.getUniqueId()) == group) {
                return List.of(player);
            }
        }
        return List.of();
    }

    private static String describe(Selection selection) {
        if (selection.global()) {
            return "everyone";
        }
        if (selection.players().size() == 1) {
            return selection.players().get(0).getName();
        }
        return selection.players().size() + " players";
    }

    /** Where ~ coordinates start: the sender's position, the single target's, or the main world's spawn. */
    private static Location origin(CommandSender sender, Selection selection) {
        if (sender instanceof Entity entity) {
            return entity.getLocation();
        }
        if (sender instanceof BlockCommandSender block) {
            return block.getBlock().getLocation().add(0.5d, 0.0d, 0.5d);
        }
        if (!selection.global() && selection.players().size() == 1) {
            return selection.players().get(0).getLocation();
        }
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    private static String dimension(World world) {
        return world.getKey().toString();
    }

    private static String id(String text) {
        if (!ID.matcher(text).matches() || text.equalsIgnoreCase("all")) {
            throw new CommandException("Invalid id '" + text + "': letters, numbers and _ - . + : (max 48), not 'all'");
        }
        return text;
    }

    private static String shape(String text) {
        String shape = text.toLowerCase(Locale.ROOT);
        if (!SHAPES.contains(shape)) {
            throw new CommandException("Unknown shape '" + text + "': " + String.join(", ", SHAPES));
        }
        return shape;
    }

    private static String color(String text) {
        String color = text.toLowerCase(Locale.ROOT);
        if (COLORS.contains(color)) {
            return color;
        }
        if (HEX.matcher(text).matches()) {
            return text.startsWith("#") ? text.toUpperCase(Locale.ROOT) : "#" + text.toUpperCase(Locale.ROOT);
        }
        throw new CommandException("Unknown color '" + text + "': a name or #RRGGBB");
    }

    private static double coordinate(String text, double base, boolean centreIntegers) {
        try {
            if (text.startsWith("~")) {
                return text.length() == 1 ? base : base + Double.parseDouble(text.substring(1));
            }
            double value = Double.parseDouble(text);
            return centreIntegers && !text.contains(".") ? value + 0.5d : value;
        } catch (NumberFormatException exception) {
            throw new CommandException("Invalid coordinate '" + text + "'");
        }
    }

    private static float number(String text, float min, float max, String name) {
        try {
            float value = Float.parseFloat(text);
            if (value < min || value > max || Float.isNaN(value)) {
                throw new CommandException(name + " must be between " + min + " and " + max);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new CommandException("Invalid number '" + text + "' for " + name);
        }
    }

    private static int integer(String text, String name) {
        try {
            int value = Integer.parseInt(text);
            if (value < 0) {
                throw new CommandException(name + " can't be negative");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new CommandException("Invalid whole number '" + text + "' for " + name);
        }
    }

    private void help(CommandSender sender) {
        String[] lines = {
                "set <@a|player> <id> <x> <y> <z> <shape> <color> <radius> <distance> <intensity> <atmosphere> <fade_in> <fade_out> [yaw] [pitch]",
                "follow <@a|player> <id> <player|uuid|tag:<tag>> <offX> <offY> <offZ> <shape> <color> <radius> <distance> <intensity> <atmosphere> <fade_in> <fade_out> [yaw pitch] [visible_firstperson]",
                "move <@a|player> <id> <x> <y> <z> <smooth|linear|step|bounce> <time>   (~ stays where it is)",
                "remove <@a|all|player> <id|all> [fade_out]   (remove all: every light of everyone, their own ones too)",
                "model_id <@a|player> <id> <x> <y> <z> <shape> <color> <radius> <opacity> <light> <fade_in> <fade_out> [distance] [yaw] [pitch]",
                "model_remove <@a|player> <id|all> [fade_out]",
                "animate <@a|player> <id> <preset[+preset]> [time] [amount] | add <preset> [time] [amount] | remove <preset> | color_transition <c1> <t1> <c2> <t2>... | size <r> <d> <time> | animation_radius <r> [time] | stop",
                "stretch <@a|player> <id> <x> <y> <z>",
                "pivot <@a|player> <id> <x> <y> <z>   (where the stretch grows from)",
                "paste <@a|player> {(type:static,id:...,...)}   (copied from the client editor or /customlight copy)",
                "flashlight <@a|player> <color> <scale> <distance> [intensity] [atmosphere] [camera_delay] [angle] [holder] | target <holder> | off",
                "angle <@a|player> <id> <degrees>",
                "seethrough <@a|player> <id> <true|false>   (false: solid blocks stop the light)",
                "object list | object remove <key|all>   (object lights set for everyone from the editor)",
                "preset list | preset remove <name|all>   (presets shared from the editor)",
                "group <@a|player> list | add <group> [ids...] | remove <group> [ids...] | modify <group> on|off|animations <on|off>",
                "list [@a|player]   reload"
        };
        sender.sendMessage(ChatColor.GOLD + "CustomLights server (/lightplugin, /lpl) - @a is saved for everyone, a player/selector only for them");
        for (String line : lines) {
            sender.sendMessage(ChatColor.YELLOW + "/lightplugin " + line);
        }
    }

    // ── Tab completion
    // ─────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("customlights.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String current = args[args.length - 1];
        // Which argument is being typed (2 = the one right after the sub command)
        int index = args.length;
        if (sub.equals("reload")) {
            return List.of();
        }
        if (sub.equals("object") || sub.equals("preset")) {
            if (index == 2) {
                return filter(List.of("list", "remove"), current);
            }
            if (index == 3 && args[1].equalsIgnoreCase("remove")) {
                List<String> names = new ArrayList<>(List.of("all"));
                names.addAll(sub.equals("object") ? store.global().objects.keySet() : store.global().presets.keySet());
                return filter(names, current);
            }
            return List.of();
        }
        if (index == 2) {
            return filter(targets(), current);
        }
        String target = args[1];
        switch (sub) {
            case "set" -> {
                return filter(switch (index) {
                    case 3 -> newOrKnownIds(target, false);
                    case 4, 5, 6 -> List.of("~");
                    case 7 -> SHAPES;
                    case 8 -> colors();
                    case 9 -> List.of("1", "0.5", "2", "4");
                    case 10 -> List.of("2", "0", "5", "10");
                    case 11 -> List.of("3", "1", "5", "10");
                    case 12 -> List.of("1", "0", "3", "6");
                    case 13, 14 -> List.of("10", "0", "20", "40");
                    case 15 -> List.of("0", "90", "180", "-90");
                    case 16 -> List.of("-90", "0", "90");
                    default -> List.of();
                }, current);
            }
            case "flashlight" -> {
                return filter(switch (index) {
                    case 3 -> prepend("off", newOrKnownIds(target, false));
                    case 4 -> prepend("@self", followTargets());
                    case 5 -> colors();
                    case 6 -> List.of("1", "0.5", "2");
                    case 7 -> List.of("8", "3", "12");
                    case 8 -> List.of("2.8", "1", "4");
                    case 9 -> List.of("0.35", "0", "2");
                    default -> List.of();
                }, current);
            }
            case "follow" -> {
                return filter(switch (index) {
                    case 3 -> newOrKnownIds(target, false);
                    case 4 -> followTargets();
                    case 5, 6, 7 -> List.of("0", "1", "-1");
                    case 8 -> SHAPES;
                    case 9 -> colors();
                    case 10 -> List.of("1", "0.5", "2", "4");
                    case 11 -> List.of("2", "0", "5", "10");
                    case 12 -> List.of("3", "1", "5", "10");
                    case 13 -> List.of("1", "0", "3", "6");
                    case 14, 15 -> List.of("10", "0", "20", "40");
                    // visible_firstperson can be last, right after fade_out or after yaw and pitch
                    case 16 -> List.of("0", "90", "180", "-90", "true", "false");
                    case 17 -> List.of("-90", "0", "90");
                    case 18 -> List.of("true", "false");
                    default -> List.of();
                }, current);
            }
            case "move" -> {
                return filter(switch (index) {
                    case 3 -> idsFor(target, false);
                    case 4, 5, 6 -> coordinates(sender, index - 4);
                    case 7 -> EASINGS;
                    case 8 -> List.of("20", "40", "100", "0");
                    default -> List.of();
                }, current);
            }
            case "remove", "model_remove" -> {
                return filter(switch (index) {
                    case 3 -> prepend("all", idsFor(target, sub.equals("model_remove")));
                    case 4 -> List.of("0", "10", "20");
                    default -> List.of();
                }, current);
            }
            case "model_id" -> {
                return filter(switch (index) {
                    case 3 -> newOrKnownIds(target, true);
                    case 4, 5, 6 -> List.of("0", "0.5");
                    case 7 -> SHAPES;
                    case 8 -> colors();
                    case 9 -> List.of("0.5", "1", "2");
                    case 10 -> List.of("0.3", "0", "1");
                    case 11 -> List.of("3", "1", "5", "10");
                    case 12, 13 -> List.of("10", "0", "20", "40");
                    case 14 -> List.of("2", "0", "5");
                    case 15 -> List.of("0", "90", "180", "-90");
                    case 16 -> List.of("-90", "0", "90");
                    default -> List.of();
                }, current);
            }
            case "animate" -> {
                if (index == 3) {
                    List<String> ids = idsFor(target, false);
                    ids.addAll(idsFor(target, true));
                    return filter(ids, current);
                }
                if (index == 4) {
                    return filter(prepend(List.of("stop", "add", "remove", "color_transition", "size", "animation_radius"), PRESETS), current);
                }
                String what = args[3].toLowerCase(Locale.ROOT);
                if (what.equals("color_transition")) {
                    // Colors and their times, one after the other
                    return filter(index % 2 == 1 ? colors() : List.of("40", "20", "80"), current);
                }
                if (what.equals("add") || what.equals("remove")) {
                    return index == 5 ? filter(PRESETS, current)
                            : index == 6 ? filter(List.of("40", "20", "80"), current)
                            : index == 7 && what.equals("add") ? filter(List.of("1", "0.5", "2"), current) : List.of();
                }
                if (what.equals("size")) {
                    return filter(index <= 6 ? List.of("1", "2", "0.5") : index == 7 ? List.of("40", "20", "80") : List.of(), current);
                }
                if (what.equals("animation_radius")) {
                    return filter(index == 5 ? List.of("2", "1", "4") : index == 6 ? List.of("40", "20", "80") : List.of(), current);
                }
                if (what.equals("stop")) {
                    return List.of();
                }
                // A preset (or several joined with +): time and amount
                return filter(index == 5 ? List.of("40", "20", "80") : index == 6 ? List.of("1", "0.5", "2") : List.of(), current);
            }
            case "stretch" -> {
                return filter(index == 3 ? idsFor(target, false) : index <= 6 ? List.of("1", "2", "0.5", "-1") : List.of(), current);
            }
            case "pivot" -> {
                return filter(index == 3 ? idsFor(target, false) : index <= 6 ? List.of("0", "1", "-1", "2") : List.of(), current);
            }
            case "angle" -> {
                return filter(index == 3 ? idsFor(target, false) : index == 4 ? List.of("45", "20", "90", "120") : List.of(), current);
            }
            case "luminosity" -> {
                return filter(index == 3 ? idsFor(target, false) : index == 4 ? List.of("1", "0", "2", "5") : List.of(), current);
            }
            case "seethrough" -> {
                List<String> ids = idsFor(target, false);
                ids.addAll(idsFor(target, true));
                return filter(index == 3 ? ids : index == 4 ? List.of("true", "false") : List.of(), current);
            }
            case "group" -> {
                if (index == 3) {
                    return filter(List.of("list", "add", "remove", "modify"), current);
                }
                String action = args[2].toLowerCase(Locale.ROOT);
                if (action.equals("list")) {
                    return List.of();
                }
                if (index == 4) {
                    return filter(groupNames(), current);
                }
                if (action.equals("modify")) {
                    return filter(index == 5 ? List.of("on", "off", "animations")
                            : index == 6 && args[4].equalsIgnoreCase("animations") ? List.of("on", "off") : List.of(), current);
                }
                // add / remove: the lights to move, one after the other
                return filter(idsFor(target, false), current);
            }
            case "paste" -> {
                return index == 3 ? filter(List.of("{(type:static,id:light1,pos:[~,~,~],shape:sphere,color:white,radius:1,distance:2,intensity:3,atmosphere:1,fadein:10,fadeout:10)}"), current) : List.of();
            }
            default -> {
                return List.of();
            }
        }
    }

    /** Where a move can go: ~ for where the light is, and the sender's own position. */
    private static List<String> coordinates(CommandSender sender, int axis) {
        List<String> suggestions = new ArrayList<>(List.of("~"));
        if (sender instanceof Entity entity) {
            Location at = entity.getLocation();
            double value = switch (axis) {
                case 0 -> at.getX();
                case 1 -> at.getY() + 1.0d;
                default -> at.getZ();
            };
            suggestions.add(String.format(Locale.ROOT, "%.2f", value));
        }
        return suggestions;
    }

    /** Who a command can be for. */
    private static List<String> targets() {
        List<String> targets = new ArrayList<>(List.of("@a", "all", "@p", "@r", "@s"));
        Bukkit.getOnlinePlayers().forEach(player -> targets.add(player.getName()));
        return targets;
    }

    /** What a follow light can follow. */
    private static List<String> followTargets() {
        List<String> targets = new ArrayList<>(List.of("@s", "@p", "tag:"));
        Bukkit.getOnlinePlayers().forEach(player -> targets.add(player.getName()));
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Player) && targets.size() < 40) {
                    targets.add(entity.getUniqueId().toString());
                }
            }
        }
        return targets;
    }

    private static List<String> colors() {
        List<String> colors = new ArrayList<>(COLORS);
        colors.add("#FFAA00");
        return colors;
    }

    /** The stored light ids a command for this target would find. */
    private List<String> idsFor(String token, boolean models) {
        List<String> ids = new ArrayList<>();
        boolean global = token.equalsIgnoreCase("@a") || token.equalsIgnoreCase("all") || token.equals("*");
        addIds(ids, store.global(), models);
        if (!global) {
            Player player = Bukkit.getPlayerExact(token);
            if (player != null) {
                LightStore.Group group = store.playerIfPresent(player.getUniqueId());
                if (group != null) {
                    addIds(ids, group, models);
                }
            } else {
                // A selector: anything anyone has
                for (LightStore.Group group : store.allGroups()) {
                    addIds(ids, group, models);
                }
            }
        }
        return ids;
    }

    /** Ids for a command that also makes new lights: what is there, and a free name to start from. */
    private List<String> newOrKnownIds(String token, boolean models) {
        List<String> ids = idsFor(token, models);
        for (int i = 1; i <= 99; i++) {
            String name = (models ? "model" : "light") + i;
            if (!ids.contains(name)) {
                ids.add(0, name);
                break;
            }
        }
        return ids;
    }

    private static void addIds(List<String> ids, LightStore.Group group, boolean models) {
        for (JsonObject light : (models ? group.models : group.lights).values()) {
            String id = light.has("id") ? light.get("id").getAsString() : null;
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        }
    }

    /** Group names the stored lights use. */
    private List<String> groupNames() {
        List<String> names = new ArrayList<>();
        for (LightStore.Group group : store.allGroups()) {
            for (JsonObject light : group.lights.values()) {
                String name = light.has("group") ? light.get("group").getAsString() : "";
                if (!name.isEmpty() && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private List<String> knownIds(boolean models) {
        List<String> ids = new ArrayList<>();
        for (LightStore.Group group : store.allGroups()) {
            for (JsonObject light : (models ? group.models : group.lights).values()) {
                String id = light.get("id").getAsString();
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private static List<String> prepend(String first, List<String> rest) {
        return prepend(List.of(first), rest);
    }

    private static List<String> prepend(List<String> first, List<String> rest) {
        List<String> all = new ArrayList<>(first);
        all.addAll(rest);
        return all;
    }

    private static List<String> filter(Collection<String> options, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }

}
