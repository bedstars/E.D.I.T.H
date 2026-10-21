package com.edith;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class EdithConfig {
    public enum Provider { ANTHROPIC, OPENAI }

    public static final String MODEL_ANTHROPIC = "claude-haiku-5-5";
    public static final String MODEL_OPENAI = "gpt-4o-mini";
    public static final String URL_ANTHROPIC = "https://api.anthropic.com/v1/messages";
    public static final String URL_OPENAI = "https://api.openai.com/v1/chat/completions";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static EdithConfig instance = new EdithConfig();

    // ---- brain ----
    public Provider provider = Provider.ANTHROPIC;
    public String apiKey = "";
    public String model = MODEL_ANTHROPIC;
    /** blank = provider default. Any OpenAI-compatible URL works (OpenRouter, Ollama, LM Studio ...). */
    public String endpoint = "";
    public int maxTokens = 700;

    // ---- server senses (all need serverReading) ----
    public boolean serverReading = true;
    public boolean chat = true;
    public boolean scoreboard = true;
    public boolean tab = true;
    public boolean titles = true;
    public boolean menus = true;
    public boolean serverBlocks = true;
    public boolean qa = true;
    public boolean privateMsgs = false;
    public int chatLines = 40;

    // ---- local senses (always client side) ----
    public boolean movement = true;
    public boolean stats = true;
    public boolean myBlocks = true;
    public boolean entities = true;
    public boolean inventory = true;
    public boolean lookAt = true;
    public int radius = 16;

    public static EdithConfig get() { return instance; }

    /** true when this server sense is allowed right now */
    public boolean srv(boolean flag) { return serverReading && flag; }

    public String key() {
        if (apiKey != null && !apiKey.isBlank()) return apiKey.trim();
        String env = System.getenv("EDITH_API_KEY");
        return env == null ? "" : env.trim();
    }

    public String url() {
        if (endpoint != null && !endpoint.isBlank()) return endpoint.trim();
        return provider == Provider.ANTHROPIC ? URL_ANTHROPIC : URL_OPENAI;
    }

    public void nextProvider() {
        provider = provider == Provider.ANTHROPIC ? Provider.OPENAI : Provider.ANTHROPIC;
        if (model == null || model.isBlank() || model.equals(MODEL_ANTHROPIC) || model.equals(MODEL_OPENAI)) {
            model = provider == Provider.ANTHROPIC ? MODEL_ANTHROPIC : MODEL_OPENAI;
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("edith.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                EdithConfig loaded = GSON.fromJson(Files.readString(p), EdithConfig.class);
                if (loaded != null) instance = loaded;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        EdithConfig c = instance;
        if (c.provider == null) c.provider = Provider.ANTHROPIC;
        if (c.apiKey == null) c.apiKey = "";
        if (c.model == null || c.model.isBlank()) c.model = MODEL_ANTHROPIC;
        if (c.endpoint == null) c.endpoint = "";
        c.maxTokens = Math.max(100, Math.min(4000, c.maxTokens));
        c.chatLines = Math.max(5, Math.min(150, c.chatLines));
        c.radius = Math.max(4, Math.min(64, c.radius));
        c.save();
    }

    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private EdithConfig() {}
}
