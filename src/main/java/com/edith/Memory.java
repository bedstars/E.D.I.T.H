package com.edith;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-server notebook: questions players ask in chat (+ the answers that follow) and the contents of menus
 * you opened. Saved to config/edith/&lt;server&gt;.json so E.D.I.T.H still knows next session.
 */
public final class Memory {
    public static final class QA {
        public String asker = "";
        public String q = "";
        public long t;
        public List<String> answers = new ArrayList<>();
    }

    private static final class Data {
        List<QA> qa = new ArrayList<>();
        Map<String, String> menus = new LinkedHashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern CHAT_LINE = Pattern.compile(
            "^(?:\\[[^\\]]{1,24}\\]\\s*|\\([^)]{1,24}\\)\\s*|[^\\w\\s]{1,3}\\s*)*([A-Za-z0-9_]{3,16})\\s*[:\u00bb>]\\s*(.{2,})$");
    private static final Pattern WORD = Pattern.compile("[a-z0-9_']{3,}");

    private static Data data = new Data();
    private static String key = "";
    private static boolean dirty = false;
    private static int tick = 0;

    /** questions that are still waiting for an answer (max 3, 90 s) */
    private static final List<QA> open = new ArrayList<>();

    // ------------------------------------------------------------------ server key / io
    public static String serverName() {
        Minecraft mc = Minecraft.getInstance();
        try {
            Object sd = R.call(mc, "getCurrentServer");
            if (sd == null) return mc.level == null ? "" : "singleplayer";
            Object ip = R.field(sd, "ip");
            return ip == null ? "server" : String.valueOf(ip);
        } catch (Throwable t) {
            return "server";
        }
    }

    private static Path file(String k) {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("edith");
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
        }
        return dir.resolve(k.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json");
    }

    private static void load(String k) {
        data = new Data();
        open.clear();
        try {
            Path p = file(k);
            if (Files.exists(p)) {
                Data d = GSON.fromJson(Files.readString(p), Data.class);
                if (d != null) {
                    data = d;
                    if (data.qa == null) data.qa = new ArrayList<>();
                    if (data.menus == null) data.menus = new LinkedHashMap<>();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        dirty = false;
    }

    public static void flush() {
        if (key.isEmpty() || !dirty) return;
        try {
            Files.writeString(file(key), GSON.toJson(data));
            dirty = false;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** called every client tick */
    public static void tick() {
        if (++tick % 60 != 0) return;
        String k = serverName();
        if (!k.equals(key)) {
            flush();
            key = k;
            if (!k.isEmpty()) load(k);
        }
        if (tick % 600 == 0) flush();
        long now = System.currentTimeMillis();
        open.removeIf(q -> now - q.t > 90_000);
    }

    public static void onDisconnect() {
        flush();
        key = "";
        data = new Data();
        open.clear();
    }

    // ------------------------------------------------------------------ chat Q&A
    private static String safe(String s) {
        return s == null ? "" : s.replace('<', '(').replace('>', ')');
    }

    /** sender may be null (system chat); then we try to read "Name: message" ourselves */
    public static void onChat(String sender, String text) {
        if (!EdithConfig.get().srv(EdithConfig.get().qa)) return;
        if (text == null) return;
        String msg = text.trim();
        if (sender == null || sender.isBlank()) {
            Matcher m = CHAT_LINE.matcher(msg);
            if (!m.matches()) return;
            sender = m.group(1);
            msg = m.group(2).trim();
        } else {
            // regular signed chat: the component text is "<Name> message" -> strip the name part
            Matcher m = CHAT_LINE.matcher(msg);
            if (m.matches() && m.group(1).equalsIgnoreCase(sender)) msg = m.group(2).trim();
            else if (msg.startsWith("<" + sender + ">")) msg = msg.substring(sender.length() + 2).trim();
        }
        if (msg.length() < 2 || msg.length() > 300) return;

        long now = System.currentTimeMillis();
        String me = "";
        try {
            me = Minecraft.getInstance().player == null ? "" : Minecraft.getInstance().player.getName().getString();
        } catch (Throwable ignored) {
        }

        boolean isQuestion = msg.contains("?") && msg.length() >= 6;
        if (isQuestion) {
            for (QA q : data.qa) {
                if (q.q.equalsIgnoreCase(msg) && now - q.t < 300_000) return;
            }
            QA q = new QA();
            q.asker = sender;
            q.q = msg;
            q.t = now;
            data.qa.add(q);
            while (data.qa.size() > 300) data.qa.remove(0);
            open.add(q);
            while (open.size() > 3) open.remove(0);
            dirty = true;
            return;
        }
        if (open.isEmpty() || sender.equalsIgnoreCase("server")) return;
        // attach as an answer: to the question that mentions this reply's target, else the newest open one
        QA target = null;
        String low = msg.toLowerCase();
        for (int i = open.size() - 1; i >= 0; i--) {
            QA q = open.get(i);
            if (q.asker.equalsIgnoreCase(sender)) continue;
            if (low.contains(q.asker.toLowerCase())) {
                target = q;
                break;
            }
            if (target == null) target = q;
        }
        if (target != null && target.answers.size() < 4) {
            target.answers.add(sender + ": " + (msg.length() > 200 ? msg.substring(0, 200) : msg));
            dirty = true;
        }
    }

    public static List<QA> all() { return data.qa; }

    public static int menuCount() { return data.menus.size(); }

    public static void clear() {
        data = new Data();
        open.clear();
        dirty = true;
        flush();
    }

    // ------------------------------------------------------------------ menus
    public static void rememberMenu(String title, String text) {
        if (title == null || title.isBlank() || text == null || text.isBlank()) return;
        String t = title.length() > 60 ? title.substring(0, 60) : title;
        String old = data.menus.get(t);
        if (text.equals(old)) return;
        data.menus.remove(t);
        data.menus.put(t, text.length() > 1800 ? text.substring(0, 1800) : text);
        while (data.menus.size() > 40) {
            String first = data.menus.keySet().iterator().next();
            data.menus.remove(first);
        }
        dirty = true;
    }

    // ------------------------------------------------------------------ retrieval for the prompt
    private static Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        Matcher m = WORD.matcher(s.toLowerCase());
        while (m.find()) out.add(m.group());
        return out;
    }

    public static String qaForPrompt(String question) {
        if (data.qa.isEmpty()) return "";
        Set<String> qw = words(question);
        List<QA> scored = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (QA q : data.qa) {
            Set<String> w = words(q.q);
            for (String a : q.answers) w.addAll(words(a));
            int s = 0;
            for (String x : qw) if (w.contains(x)) s++;
            if (s > 0) {
                int i = 0;
                while (i < scores.size() && scores.get(i) >= s) i++;
                scores.add(i, s);
                scored.add(i, q);
            }
        }
        List<QA> pick = new ArrayList<>(scored.subList(0, Math.min(6, scored.size())));
        // plus the newest few so "what did people just ask" works
        for (int i = data.qa.size() - 1, n = 0; i >= 0 && n < 4; i--, n++) {
            if (!pick.contains(data.qa.get(i))) pick.add(data.qa.get(i));
        }
        StringBuilder sb = new StringBuilder();
        for (QA q : pick) {
            sb.append("Q (").append(safe(q.asker)).append("): ").append(safe(q.q)).append('\n');
            if (q.answers.isEmpty()) sb.append("   (no answer heard)\n");
            for (String a : q.answers) sb.append("   A ").append(safe(a)).append('\n');
        }
        return sb.toString();
    }

    public static String menusForPrompt(String question) {
        if (data.menus.isEmpty()) return "";
        Set<String> qw = words(question);
        StringBuilder sb = new StringBuilder("Menus seen on this server: ");
        sb.append(safe(String.join(" | ", data.menus.keySet()))).append('\n');
        List<String> best = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (Map.Entry<String, String> e : data.menus.entrySet()) {
            Set<String> w = words(e.getKey() + " " + e.getValue());
            int s = 0;
            for (String x : qw) if (w.contains(x)) s += e.getKey().toLowerCase().contains(x) ? 3 : 1;
            if (s > 0) {
                int i = 0;
                while (i < scores.size() && scores.get(i) >= s) i++;
                scores.add(i, s);
                best.add(i, e.getKey());
            }
        }
        int used = 0;
        for (int i = 0; i < Math.min(3, best.size()); i++) {
            String body = data.menus.get(best.get(i));
            if (used + body.length() > 3800) break;
            used += body.length();
            sb.append("--- menu \"").append(safe(best.get(i))).append("\" ---\n").append(safe(body)).append('\n');
        }
        return sb.toString();
    }

    private Memory() {}
}
