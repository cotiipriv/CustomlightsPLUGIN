package com.cotii.customlightsplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Text used by {@code /customlight copy}, {@code /customlight paste}, the editor and the server plugin ({@code /lightplugin
 * paste}): one or more lights in a single line, like FogTools.
 */
final class PasteFormat {
    private PasteFormat() {
    }

    // ── Writing
    // ─────────────────────────────────────────────────────

    /** {@code light} is a light in its JSON form; {@code type} is static, follow, model or flashlight. */
    public static String write(JsonObject light, String type) {
        StringBuilder builder = new StringBuilder("(");
        builder.append("type:").append(type);
        if (!type.equals("flashlight")) {
            builder.append(",id:").append(string(light, "id", "light"));
        }
        if (type.equals("follow")) {
            builder.append(",target:").append(string(light, "target", ""));
            builder.append(",offset:[").append(num(light, "offsetX", 0)).append(',').append(num(light, "offsetY", 0)).append(',')
                    .append(num(light, "offsetZ", 0)).append(']');
            if (light.has("visibleFirstPerson") && !light.get("visibleFirstPerson").getAsBoolean()) {
                builder.append(",firstperson:false");
            }
        } else if (!type.equals("flashlight")) {
            builder.append(",pos:[").append(num(light, "x", 0)).append(',').append(num(light, "y", 0)).append(',')
                    .append(num(light, "z", 0)).append(']');
        }
        builder.append(",shape:").append(string(light, "shape", type.equals("flashlight") ? "flashlight" : "sphere"));
        builder.append(",color:").append(string(light, "color", "white"));
        builder.append(",radius:").append(num(light, "radius", 1));
        builder.append(",distance:").append(num(light, "distance", 0));
        builder.append(",intensity:").append(num(light, "intensity", 1));
        builder.append(",atmosphere:").append(num(light, "atmosphere", 0));
        if (light.has("luminosity") && light.get("luminosity").getAsDouble() > 0.0) {
            builder.append(",luminosity:").append(num(light, "luminosity", 0));
        }
        if (type.equals("flashlight")) {
            if (light.has("cameraDelay")) {
                builder.append(",delay:").append(num(light, "cameraDelay", 0));
            }
            String holder = string(light, "target", "@self");
            if (!holder.isEmpty() && !holder.equalsIgnoreCase("@self")) {
                builder.append(",target:").append(holder);
            }
        } else {
            builder.append(",fadein:").append(num(light, "fadeIn", 0));
            builder.append(",fadeout:").append(num(light, "fadeOut", 0));
            builder.append(",yaw:").append(num(light, "yaw", 0));
            builder.append(",pitch:").append(num(light, "pitch", 0));
        }
        if (light.has("group") && !type.equals("flashlight")) {
            builder.append(",group:").append(string(light, "group", ""));
        }
        if (light.has("seethrough") && !light.get("seethrough").getAsBoolean()) {
            builder.append(",seethrough:false");
        }
        if (light.has("angle")) {
            builder.append(",angle:").append(num(light, "angle", 0));
        }
        if (light.has("stretchX")) {
            builder.append(",stretch:[").append(num(light, "stretchX", 1)).append(',').append(num(light, "stretchY", 1)).append(',')
                    .append(num(light, "stretchZ", 1)).append(']');
        }
        if (light.has("pivotX")) {
            builder.append(",pivot:[").append(num(light, "pivotX", 0)).append(',').append(num(light, "pivotY", 0)).append(',')
                    .append(num(light, "pivotZ", 0)).append(']');
        }
        if (light.has("animation") && !type.equals("flashlight")) {
            JsonObject animation = light.getAsJsonObject("animation");
            if (animation.has("presets") && !animation.getAsJsonArray("presets").isEmpty()) {
                builder.append(",presets:[");
                boolean first = true;
                for (JsonElement element : animation.getAsJsonArray("presets")) {
                    JsonObject preset = element.getAsJsonObject();
                    if (!first) {
                        builder.append(',');
                    }
                    first = false;
                    builder.append(string(preset, "preset", "circle")).append(':').append(num(preset, "time", 40));
                    if (preset.has("amount")) {
                        builder.append(':').append(num(preset, "amount", 0));
                    }
                }
                builder.append(']');
            }
            if (animation.has("colors") && animation.getAsJsonArray("colors").size() >= 2) {
                builder.append(",colors:[");
                boolean first = true;
                for (JsonElement element : animation.getAsJsonArray("colors")) {
                    if (!first) {
                        builder.append(',');
                    }
                    first = false;
                    builder.append(element.getAsString());
                }
                builder.append(']');
            }
            if (animation.has("sizeTime") && animation.get("sizeTime").getAsInt() > 0) {
                builder.append(",size:[").append(num(animation, "sizeRadius", 1)).append(',').append(num(animation, "sizeDistance", 1)).append(',')
                        .append(num(animation, "sizeTime", 40)).append(']');
            }
            if (animation.has("radiusPulseTime") && animation.get("radiusPulseTime").getAsInt() > 0) {
                builder.append(",radiuspulse:[").append(num(animation, "radiusPulseTarget", 2)).append(',')
                        .append(num(animation, "radiusPulseTime", 40)).append(']');
            }
        }
        return builder.append(')').toString();
    }

    public static String wrap(List<String> entries) {
        return "{" + String.join("", entries) + "}";
    }

    // ── Parsing
    // ─────────────────────────────────────────────────────

    /** One pasted light: its type and its JSON (client light format). */
    public record Entry(String type, JsonObject light) {
    }

    /** Parses a paste string (a full copied command like {@code /customlight paste {...}} works too). */
    public static List<Entry> parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Nothing to paste: copy a light first (editor or /customlight copy)");
        }
        String text = input.strip();
        int brace = text.indexOf('{');
        int paren = text.indexOf('(');
        int start = brace >= 0 ? brace : paren;
        if (start < 0) {
            throw new IllegalArgumentException("Expected {(...)}");
        }
        text = text.substring(start).replaceAll("\\s+", "");
        if (text.startsWith("{")) {
            if (!text.endsWith("}")) {
                throw new IllegalArgumentException("Missing closing '}'");
            }
            text = text.substring(1, text.length() - 1);
        }
        List<Entry> entries = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == ',') {
                index++;
                continue;
            }
            if (c != '(') {
                throw new IllegalArgumentException("Expected '(' at '" + text.substring(index, Math.min(text.length(), index + 12)) + "'");
            }
            int end = closing(text, index);
            if (end < 0) {
                throw new IllegalArgumentException("Missing closing ')'");
            }
            entries.add(parseLight(text.substring(index + 1, end)));
            index = end + 1;
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("No light to paste");
        }
        return entries;
    }

    private static Entry parseLight(String text) {
        JsonObject light = new JsonObject();
        JsonObject animation = new JsonObject();
        String type = "static";
        for (String part : splitTopLevel(text)) {
            if (part.isEmpty()) {
                continue;
            }
            int colon = part.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("Expected key:value but found '" + part + "'");
            }
            String key = part.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = part.substring(colon + 1);
            try {
                switch (key) {
                    case "type" -> type = value.toLowerCase(Locale.ROOT);
                    case "id" -> light.addProperty("id", value);
                    case "pos", "position" -> vector(light, value, "x", "y", "z");
                    case "offset" -> vector(light, value, "offsetX", "offsetY", "offsetZ");
                    case "target" -> light.addProperty("target", value);
                    case "firstperson", "visible_firstperson" -> light.addProperty("visibleFirstPerson", bool(value));
                    case "seethrough", "see_through" -> light.addProperty("seethrough", bool(value));
                    case "shape" -> light.addProperty("shape", value.toLowerCase(Locale.ROOT));
                    case "color" -> light.addProperty("color", value);
                    case "radius" -> light.addProperty("radius", number(value));
                    case "distance", "blur" -> light.addProperty("distance", number(value));
                    case "intensity", "light" -> light.addProperty("intensity", number(value));
                    case "atmosphere" -> light.addProperty("atmosphere", number(value));
                    case "luminosity" -> light.addProperty("luminosity", number(value));
                    case "fadein" -> light.addProperty("fadeIn", (int) number(value));
                    case "fadeout" -> light.addProperty("fadeOut", (int) number(value));
                    case "yaw" -> light.addProperty("yaw", number(value));
                    case "pitch" -> light.addProperty("pitch", number(value));
                    case "angle" -> light.addProperty("angle", number(value));
                    case "group" -> light.addProperty("group", value);
                    case "stretch" -> vector(light, value, "stretchX", "stretchY", "stretchZ");
                    case "pivot" -> vector(light, value, "pivotX", "pivotY", "pivotZ");
                    case "delay", "cameradelay" -> light.addProperty("cameraDelay", number(value));
                    case "presets", "preset" -> {
                        JsonArray presets = new JsonArray();
                        for (String item : list(value)) {
                            String[] pieces = item.split(":");
                            JsonObject preset = new JsonObject();
                            preset.addProperty("preset", pieces[0].toLowerCase(Locale.ROOT));
                            preset.addProperty("time", pieces.length > 1 ? (int) number(pieces[1]) : 40);
                            if (pieces.length > 2) {
                                preset.addProperty("amount", number(pieces[2]));
                            }
                            presets.add(preset);
                        }
                        animation.add("presets", presets);
                    }
                    case "colors" -> {
                        JsonArray colors = new JsonArray();
                        for (String item : list(value)) {
                            colors.add(item);
                        }
                        animation.add("colors", colors);
                    }
                    case "size" -> {
                        List<String> values = list(value);
                        if (values.size() != 3) {
                            throw new IllegalArgumentException("use [radius,distance,time]");
                        }
                        animation.addProperty("sizeRadius", number(values.get(0)));
                        animation.addProperty("sizeDistance", number(values.get(1)));
                        animation.addProperty("sizeTime", (int) number(values.get(2)));
                    }
                    case "radiuspulse" -> {
                        List<String> values = list(value);
                        if (values.size() != 2) {
                            throw new IllegalArgumentException("use [radius,time]");
                        }
                        animation.addProperty("radiusPulseTarget", number(values.get(0)));
                        animation.addProperty("radiusPulseTime", (int) number(values.get(1)));
                    }
                    default -> throw new IllegalArgumentException("unknown key");
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(key + ": " + exception.getMessage());
            }
        }
        if (!List.of("static", "follow", "model", "flashlight").contains(type)) {
            throw new IllegalArgumentException("type: use static, follow, model or flashlight");
        }
        if (!type.equals("flashlight") && !light.has("id")) {
            throw new IllegalArgumentException("Missing id");
        }
        if (type.equals("follow")) {
            light.addProperty("kind", "follow");
            if (!light.has("target")) {
                throw new IllegalArgumentException("A follow light needs a target");
            }
        } else if (type.equals("flashlight")) {
            light.addProperty("id", "flashlight");
            light.addProperty("kind", "follow");
            if (!light.has("target")) {
                light.addProperty("target", "@self");
            }
            if (!light.has("shape")) {
                light.addProperty("shape", "flashlight");
            }
        } else {
            light.addProperty("kind", "static");
        }
        if (!animation.entrySet().isEmpty()) {
            light.add("animation", animation);
        }
        return new Entry(type, light);
    }

    // ── Helpers
    // ─────────────────────────────────────────────────────

    private static void vector(JsonObject light, String value, String x, String y, String z) {
        List<String> values = list(value);
        if (values.size() != 3) {
            throw new IllegalArgumentException("use [x,y,z]");
        }
        light.addProperty(x, number(values.get(0)));
        light.addProperty(y, number(values.get(1)));
        light.addProperty(z, number(values.get(2)));
    }

    private static List<String> list(String value) {
        String inner = value;
        if (inner.startsWith("[") && inner.endsWith("]")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        List<String> items = new ArrayList<>();
        for (String item : inner.split(",")) {
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[' || c == '(') {
                depth++;
            } else if (c == ']' || c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static int closing(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static float number(String value) {
        try {
            float number = Float.parseFloat(value);
            if (Float.isNaN(number) || Float.isInfinite(number)) {
                throw new NumberFormatException();
            }
            return number;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("'" + value + "' is not a number");
        }
    }

    private static boolean bool(String value) {
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(value);
        }
        throw new IllegalArgumentException("use true or false");
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static String num(JsonObject object, String key, double fallback) {
        double value = object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsDouble() : fallback;
        String text = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return text.equals("-0") ? "0" : text;
    }
}
