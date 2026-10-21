package dev.edith.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.edith.EdithClient;
import dev.edith.EdithConfig;
import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

public final class AiClient {
    private AiClient() {}

    public record Img(String mediaType, String b64, int w, int h) {}
    public record Turn(String role, String text, List<Img> images) {
        public Turn(String role, String text) { this(role, text, List.of()); }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Gson GSON = new Gson();

    /** Async. Callbacks always run on the Minecraft client thread. */
    public static void ask(String system, List<Turn> turns, Consumer<String> onOk, Consumer<String> onErr) {
        EdithConfig c = EdithClient.CONFIG;
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean anthropic = !"openai".equals(c.provider);
        HttpRequest req;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", c.model);
            JsonArray msgs = new JsonArray();
            if (anthropic) {
                body.addProperty("max_tokens", 1024);
                body.addProperty("system", system);
            } else {
                body.addProperty("max_tokens", 1024);
                msgs.add(plain("system", system));
            }
            for (Turn t : turns) msgs.add(msg(t, anthropic));
            body.add("messages", msgs);

            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(60))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
            if (anthropic) {
                b.uri(URI.create(c.endpoint.isBlank() ? "https://api.anthropic.com/v1/messages" : c.endpoint))
                        .header("x-api-key", c.apiKey)
                        .header("anthropic-version", "2023-06-01");
            } else {
                b.uri(URI.create(c.endpoint.isBlank() ? "https://api.openai.com/v1/chat/completions" : c.endpoint));
                if (!c.apiKey.isBlank()) b.header("authorization", "Bearer " + c.apiKey);
            }
            req = b.build();
        } catch (Exception e) {
            onErr.accept("Bad request setup: " + e.getMessage());
            return;
        }

        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(resp -> {
            try {
                String out = parse(resp.statusCode(), resp.body(), anthropic);
                mc.execute(() -> onOk.accept(out));
            } catch (Exception e) {
                String m = e.getMessage();
                mc.execute(() -> onErr.accept(m));
            }
        }).exceptionally(ex -> {
            String m = "Connection failed: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
            mc.execute(() -> onErr.accept(m));
            return null;
        });
    }

    private static JsonObject msg(Turn t, boolean anthropic) {
        if (t.images().isEmpty()) return plain(t.role(), t.text());
        JsonArray parts = new JsonArray();
        for (Img im : t.images()) {
            JsonObject part = new JsonObject();
            if (anthropic) {
                part.addProperty("type", "image");
                JsonObject src = new JsonObject();
                src.addProperty("type", "base64");
                src.addProperty("media_type", im.mediaType());
                src.addProperty("data", im.b64());
                part.add("source", src);
            } else {
                part.addProperty("type", "image_url");
                JsonObject url = new JsonObject();
                url.addProperty("url", "data:" + im.mediaType() + ";base64," + im.b64());
                part.add("image_url", url);
            }
            parts.add(part);
        }
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", t.text());
        parts.add(text);
        JsonObject o = new JsonObject();
        o.addProperty("role", t.role());
        o.add("content", parts);
        return o;
    }

    private static JsonObject plain(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private static String parse(int status, String body, boolean anthropic) {
        JsonObject root;
        try { root = GSON.fromJson(body, JsonObject.class); } catch (Exception e) { root = null; }
        if (status < 200 || status >= 300) {
            String m = body.length() > 200 ? body.substring(0, 200) : body;
            if (root != null && root.has("error")) {
                JsonElement err = root.get("error");
                if (err.isJsonObject() && err.getAsJsonObject().has("message"))
                    m = err.getAsJsonObject().get("message").getAsString();
            }
            throw new RuntimeException("API " + status + ": " + m);
        }
        if (root == null) throw new RuntimeException("Empty or invalid API response.");
        StringBuilder sb = new StringBuilder();
        if (anthropic) {
            for (JsonElement e : root.getAsJsonArray("content")) {
                JsonObject o = e.getAsJsonObject();
                if (o.has("text")) sb.append(o.get("text").getAsString());
            }
        } else {
            sb.append(root.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString());
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? "(empty reply)" : s;
    }
}
