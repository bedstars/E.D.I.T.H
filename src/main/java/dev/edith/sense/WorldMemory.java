package dev.edith.sense;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.edith.EdithConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Everything E.D.I.T.H knows about the world: rolling event log, live snapshots, and the chat Q&A notebook. */
public final class WorldMemory {
    private WorldMemory() {}

    public static final class Event {
        public final long time; public final String kind, text;
        Event(long t, String k, String s) { time = t; kind = k; text = s; }
    }
    public static final class QA {
        public String asker = "", question = "";
        public List<String> answers = new ArrayList<>();
        public long time;
    }

    public static final ArrayDeque<Event> EVENTS = new ArrayDeque<>();
    private static final ArrayDeque<Event> BLOCKS = new ArrayDeque<>();
    public static List<QA> qa = new ArrayList<>();
    public static int version;
    private static QA open;
    private static String key = "singleplayer";

    // live snapshots (written by Sensors)
    public static String sidebarTitle = "", tabHeader = "", tabFooter = "", actionBar = "", menuTitle = "";
    public static List<String> sidebar = List.of(), tabPlayers = List.of(), bossBars = List.of(), nearby = List.of(), menuItems = List.of();
    public static long menuTime;
    public static double speed, walked;

    public static String key() { return key; }

    // ---------- helpers ----------
    public static String clean(String s) {
        return s == null ? "" : s.replaceAll("\u00A7.", "").replaceAll("\\s+", " ").trim();
    }
    private static String clip(String s, int n) { return s.length() > n ? s.substring(0, n) + "..." : s; }

    public static void add(String kind, String text) {
        Event e = new Event(System.currentTimeMillis(), kind, clip(clean(text), 220));
        ArrayDeque<Event> q = (kind.equals("break") || kind.equals("place")) ? BLOCKS : EVENTS;
        q.addLast(e);
        while (q.size() > 300) q.pollFirst();
        version++;
    }

    public static void clearLive() {
        sidebarTitle = tabHeader = tabFooter = actionBar = menuTitle = "";
        sidebar = tabPlayers = bossBars = nearby = menuItems = List.of();
        version++;
    }

    // ---------- chat + Q&A ----------
    private static final Pattern VANILLA = Pattern.compile("^<([A-Za-z0-9_]{3,16})>\\s*(.+)$");
    private static final Pattern SERVER =
            Pattern.compile("^(?:\\[[^\\]]{1,24}\\]\\s*)*([A-Za-z0-9_]{3,16})\\s*[:\\u00BB>]\\s*(.+)$");

    public static void onChatLine(String s) {
        String name = null, msg = null;
        Matcher m = VANILLA.matcher(s);
        if (m.matches()) { name = m.group(1); msg = m.group(2); }
        else if ((m = SERVER.matcher(s)).matches()) { name = m.group(1); msg = m.group(2); }
        if (name == null) { add("chat", s); return; }
        add("chat", name + ": " + msg);

        long now = System.currentTimeMillis();
        if (open != null && now - open.time < 60_000 && !name.equalsIgnoreCase(open.asker)
                && !msg.contains("?") && open.answers.size() < 3) {
            open.answers.add(name + ": " + clip(msg, 160));
            version++;
        }
        if (msg.trim().endsWith("?")) {
            QA q = new QA();
            q.asker = name; q.question = clip(msg, 200); q.time = now;
            qa.add(q); open = q;
            if (qa.size() > 200) qa.remove(0);
            version++;
        }
    }

    // ---------- persistence of the notebook (per server) ----------
    private static final Gson GSON = new GsonBuilder().create();
    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("edith_memory.json"); }

    private static Map<String, List<QA>> readAll() {
        try {
            if (Files.exists(file())) {
                Map<String, List<QA>> m = GSON.fromJson(Files.readString(file()),
                        new TypeToken<Map<String, List<QA>>>() {}.getType());
                if (m != null) return m;
            }
        } catch (Exception ignored) {}
        return new HashMap<>();
    }

    public static void onJoin(String serverKey) {
        key = serverKey;
        qa = new ArrayList<>(readAll().getOrDefault(serverKey, List.of()));
        open = null;
        EVENTS.clear(); BLOCKS.clear();
        speed = 0; walked = 0;
        clearLive();
    }

    public static void save() {
        try {
            Map<String, List<QA>> all = readAll();
            if (qa.isEmpty()) all.remove(key); else all.put(key, qa);
            Files.writeString(file(), GSON.toJson(all));
        } catch (Exception ignored) {}
    }

    // ---------- snapshot handed to the AI ----------
    private static boolean visible(String kind, EdithConfig c) {
        return switch (kind) {
            case "chat" -> c.readChat();
            case "scoreboard" -> c.readScoreboard();
            case "tab" -> c.readTab();
            case "menu" -> c.readMenus();
            case "tracker", "actionbar" -> c.readTrackers();
            case "move" -> c.movement;
            case "stat", "inv" -> c.stats;
            default -> true;
        };
    }

    private static String ago(long t) {
        long s = (System.currentTimeMillis() - t) / 1000;
        return s < 60 ? s + "s ago" : s < 3600 ? (s / 60) + "m ago" : (s / 3600) + "h ago";
    }

    private static void section(StringBuilder sb, String title, List<String> lines, int max) {
        if (lines.isEmpty()) return;
        sb.append(title).append(":\n");
        for (int i = 0; i < Math.min(max, lines.size()); i++) sb.append("  ").append(lines.get(i)).append('\n');
        if (lines.size() > max) sb.append("  ... +").append(lines.size() - max).append(" more\n");
    }

    public static String snapshot(MinecraftClient mc, EdithConfig c) {
        StringBuilder s = new StringBuilder();
        s.append("mode: ").append(c.serverReading ? "SERVER LINK" : "CLIENT ONLY (server data is not read)").append('\n');
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return s.append("not in a world\n").toString();
        if (c.serverReading) {
            ServerInfo si = mc.getCurrentServerEntry();
            s.append("server: ").append(si != null ? si.address : "singleplayer/LAN").append('\n');
        }
        s.append("player: ").append(p.getName().getString()).append('\n');

        if (c.movement) {
            BlockPos bp = p.getBlockPos();
            String biome = mc.world.getBiome(bp).getKey().map(k -> k.getValue().toString()).orElse("?");
            s.append(String.format(Locale.ROOT,
                    "position: %d %d %d | dimension: %s | biome: %s | yaw %.0f | speed %.1f blocks/s | walked %.0f blocks this session\n",
                    bp.getX(), bp.getY(), bp.getZ(), mc.world.getRegistryKey().getValue(), biome, p.getYaw(), speed, walked));
            List<String> fl = new ArrayList<>();
            if (p.isSprinting()) fl.add("sprinting");
            if (p.isSneaking()) fl.add("sneaking");
            if (p.getAbilities().flying) fl.add("flying");
            if (p.isFallFlying()) fl.add("gliding");
            if (p.isSwimming()) fl.add("swimming");
            if (p.hasVehicle()) fl.add("riding");
            if (!fl.isEmpty()) s.append("state: ").append(String.join(", ", fl)).append('\n');
        }

        if (c.stats) {
            s.append(String.format(Locale.ROOT, "health %.0f/%.0f | food %d | xp level %d | armor %d\n",
                    p.getHealth(), p.getMaxHealth(), p.getHungerManager().getFoodLevel(), p.experienceLevel, p.getArmor()));
            ItemStack held = p.getMainHandStack();
            s.append("holding: ").append(held.isEmpty() ? "nothing" : held.getCount() + "x " + clean(held.getName().getString())).append('\n');
            StringBuilder hb = new StringBuilder();
            for (int i = 0; i < 9; i++) {
                ItemStack st = p.getInventory().getStack(i);
                hb.append(i + 1).append('=').append(st.isEmpty() ? "-" : st.getCount() + "x " + clean(st.getName().getString()));
                if (i < 8) hb.append(" | ");
            }
            s.append("hotbar: ").append(hb).append('\n');
            Map<String, Integer> tot = new LinkedHashMap<>();
            for (int i = 0; i < p.getInventory().size(); i++) {
                ItemStack st = p.getInventory().getStack(i);
                if (!st.isEmpty()) tot.merge(clean(st.getName().getString()), st.getCount(), Integer::sum);
            }
            List<String> inv = new ArrayList<>();
            tot.forEach((k, v) -> inv.add(v + "x " + k));
            if (!inv.isEmpty()) s.append("inventory totals: ").append(String.join(", ", inv.subList(0, Math.min(40, inv.size())))).append('\n');
        }

        if (c.readScoreboard() && !sidebar.isEmpty()) {
            s.append("SIDEBAR \"").append(sidebarTitle).append("\":\n");
            for (String l : sidebar) s.append("  ").append(l).append('\n');
        }
        if (c.readTab()) {
            if (!tabHeader.isEmpty()) s.append("TAB header: ").append(tabHeader).append('\n');
            if (!tabFooter.isEmpty()) s.append("TAB footer: ").append(tabFooter).append('\n');
            if (!tabPlayers.isEmpty()) s.append("TAB players (").append(tabPlayers.size()).append("): ")
                    .append(String.join(", ", tabPlayers.subList(0, Math.min(60, tabPlayers.size())))).append('\n');
        }
        if (c.readTrackers()) {
            section(s, "BOSS BARS", bossBars, 5);
            if (!actionBar.isEmpty()) s.append("ACTION BAR: ").append(actionBar).append('\n');
            section(s, "NEARBY", nearby, 14);
        }
        if (c.readMenus() && !menuItems.isEmpty()) {
            s.append("LAST MENU \"").append(menuTitle).append("\" (seen ").append(ago(menuTime)).append("):\n");
            for (String l : menuItems) s.append("  ").append(l).append('\n');
        }

        if (c.blocks) {
            long cutoff = System.currentTimeMillis() - 300_000;
            Map<String, Integer> br = new LinkedHashMap<>(), pl = new LinkedHashMap<>();
            for (Event e : BLOCKS) {
                if (e.time < cutoff) continue;
                (e.kind.equals("break") ? br : pl).merge(e.text, 1, Integer::sum);
            }
            StringBuilder b = new StringBuilder();
            br.forEach((k, v) -> b.append("broke ").append(v).append("x ").append(k).append("; "));
            pl.forEach((k, v) -> b.append("placed ").append(v).append("x ").append(k).append("; "));
            if (b.length() > 0) s.append("BLOCK ACTIVITY (last 5 min): ").append(b).append('\n');
        }

        List<String> chat = new ArrayList<>(), other = new ArrayList<>();
        for (Event e : EVENTS) {
            if (!visible(e.kind, c)) continue;
            String line = "[" + ago(e.time) + "] " + e.kind + ": " + e.text;
            if (e.kind.equals("chat")) chat.add(line); else other.add(line);
        }
        section(s, "RECENT CHAT", chat.subList(Math.max(0, chat.size() - 25), chat.size()), 25);
        section(s, "RECENT EVENTS", other.subList(Math.max(0, other.size() - 30), other.size()), 30);

        if (c.readChat() && !qa.isEmpty()) {
            s.append("Q&A NOTEBOOK (questions players asked in chat, newest last):\n");
            for (int i = Math.max(0, qa.size() - 12); i < qa.size(); i++) {
                QA q = qa.get(i);
                s.append("  Q ").append(q.asker).append(": ").append(q.question).append('\n');
                for (String a : q.answers) s.append("    A ").append(a).append('\n');
            }
        }
        String out = s.toString();
        return out.length() > 9000 ? out.substring(0, 9000) + "\n...(truncated)\n" : out;
    }
}
