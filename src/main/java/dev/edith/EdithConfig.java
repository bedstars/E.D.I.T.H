package dev.edith;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public final class EdithConfig {
    // --- AI backend ---
    public String provider = "anthropic";          // "anthropic" or "openai" (OpenAI-compatible, incl. local servers)
    public String apiKey = "";
    public String model = "claude-haiku-5-5";
    public String endpoint = "";                    // optional override
    // --- UI ---
    public boolean textShadow = true;
    // --- sensors ---
    public boolean serverReading = true;            // master switch for everything the SERVER sends
    public boolean chat = true, scoreboard = true, tab = true, menus = true, trackers = true;
    public boolean movement = true, blocks = true, stats = true;   // local-only sensors

    public boolean readChat()       { return serverReading && chat; }
    public boolean readScoreboard() { return serverReading && scoreboard; }
    public boolean readTab()        { return serverReading && tab; }
    public boolean readMenus()      { return serverReading && menus; }
    public boolean readTrackers()   { return serverReading && trackers; }

    public boolean ready() {
        return !apiKey.isBlank() || ("openai".equals(provider) && !endpoint.isBlank());
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("edith.json"); }

    public static EdithConfig load() {
        try {
            if (Files.exists(file())) {
                EdithConfig c = GSON.fromJson(Files.readString(file()), EdithConfig.class);
                if (c != null) return c;
            }
        } catch (Exception ignored) {}
        EdithConfig c = new EdithConfig();
        c.save();
        return c;
    }

    public void save() {
        try { Files.writeString(file(), GSON.toJson(this)); } catch (Exception ignored) {}
    }
}
