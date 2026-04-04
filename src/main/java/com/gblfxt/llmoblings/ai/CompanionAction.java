package com.gblfxt.llmoblings.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

public class CompanionAction {
    private final String action;
    private final String message;
    private final JsonObject data;

    public CompanionAction(String action, @Nullable String message) {
        this(action, message, new JsonObject());
    }

    public CompanionAction(String action, @Nullable String message, JsonObject data) {
        this.action = action;
        this.message = message;
        this.data = data;
    }

    public static CompanionAction fromJson(JsonObject json) {
        String action = getStringValue(json, "action", "idle");
        String message = getNullableStringValue(json, "message");
        return new CompanionAction(action, message, json);
    }

    public String getAction() {
        return action;
    }

    @Nullable
    public String getMessage() {
        return message;
    }

    public JsonObject getData() {
        return data;
    }

    public String getString(String key, String defaultValue) {
        return getStringValue(data, key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        if (!data.has(key)) return defaultValue;
        JsonElement element = data.get(key);
        if (element == null || element.isJsonNull()) return defaultValue;
        try {
            return element.getAsInt();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    public double getDouble(String key, double defaultValue) {
        if (!data.has(key)) return defaultValue;
        JsonElement element = data.get(key);
        if (element == null || element.isJsonNull()) return defaultValue;
        try {
            return element.getAsDouble();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        if (!data.has(key)) return defaultValue;
        JsonElement element = data.get(key);
        if (element == null || element.isJsonNull()) return defaultValue;
        try {
            return element.getAsBoolean();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static String getStringValue(JsonObject json, String key, String defaultValue) {
        if (!json.has(key)) return defaultValue;
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) return defaultValue;
        try {
            return element.getAsString();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static String getNullableStringValue(JsonObject json, String key) {
        if (!json.has(key)) return null;
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) return null;
        try {
            return element.getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    public boolean has(String key) {
        return data.has(key);
    }

    public void setParameter(String key, String value) {
        data.addProperty(key, value);
    }

    public void setParameter(String key, int value) {
        data.addProperty(key, value);
    }

    @Override
    public String toString() {
        return "CompanionAction{action='" + action + "', message='" + message + "', data=" + data + "}";
    }
}
