package dev.edith.sense;

import dev.edith.EdithClient;
import dev.edith.EdithConfig;
import dev.edith.mixin.BossBarHudAccessor;
import dev.edith.mixin.PlayerListHudAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.AbstractInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.*;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.stream.Collectors;

/** Registers every event hook / tick scanner. Server-sourced sensors are gated by EdithConfig.serverReading. */
public final class Sensors {
    private Sensors() {}

    private static int ticks;
    private static Vec3d lastPos;
    private static String lastDim;
    private static boolean wasFly, wasGlide, wasSwim, wasRide;
    private static float lastHealth = -1;
    private static int lastFood = -1, lastLevel = -1;
    private static Map<String, Integer> lastInv;
    private static Map<String, String> lastSb = new HashMap<>();
    private static final Map<String, Long> sbLogged = new HashMap<>();
    private static Set<String> lastTab;
    private static Set<String> lastBars = new HashSet<>();
    private static String lastMenuHash = "";
    private static long lastActionLog;

    private static void reset() {
        lastPos = null; lastDim = null; lastHealth = -1; lastFood = -1; lastLevel = -1;
        lastInv = null; lastSb = new HashMap<>(); sbLogged.clear(); lastTab = null; lastBars = new HashSet<>();
        lastMenuHash = ""; wasFly = wasGlide = wasSwim = wasRide = false;
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(Sensors::tick);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            reset();
            WorldMemory.onJoin(mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "singleplayer");
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> WorldMemory.save());
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> WorldMemory.save());

        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> onMessage(msg, overlay));
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) -> onMessage(msg, false));

        ClientPlayerBlockBreakEvents.AFTER.register((world, player, pos, state) -> {
            if (EdithClient.CONFIG.blocks) WorldMemory.add("break", state.getBlock().getName().getString());
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient && EdithClient.CONFIG.blocks) {
                ItemStack st = player.getStackInHand(hand);
                if (st.getItem() instanceof BlockItem bi) WorldMemory.add("place", bi.getBlock().getName().getString());
            }
            return ActionResult.PASS;
        });
    }

    // ------------------------------------------------------------ chat / action bar
    private static void onMessage(Text msg, boolean overlay) {
        EdithConfig c = EdithClient.CONFIG;
        String s = WorldMemory.clean(msg.getString());
        if (s.isBlank()) return;
        if (overlay) {
            if (c.readTrackers()) {
                WorldMemory.actionBar = s;
                long now = System.currentTimeMillis();
                if (now - lastActionLog > 10_000) { lastActionLog = now; WorldMemory.add("actionbar", s); }
            }
            return;
        }
        if (c.readChat()) WorldMemory.onChatLine(s);
    }

    // ------------------------------------------------------------ tick
    private static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;
        EdithConfig c = EdithClient.CONFIG;
        ticks++;
        if (c.movement) movement(mc, p);
        if (c.stats && ticks % 10 == 0) stats(p);
        if (ticks % 20 == 0) {
            if (c.readScoreboard()) scoreboard(mc);
            if (c.readTab()) tab(mc);
            if (c.readTrackers()) trackers(mc, p);
        }
        if (c.readMenus() && ticks % 5 == 0) menus(mc);
    }

    private static void movement(MinecraftClient mc, ClientPlayerEntity p) {
        String dim = mc.world.getRegistryKey().getValue().toString();
        Vec3d pos = p.getPos();
        if (lastDim != null && !dim.equals(lastDim)) { WorldMemory.add("move", "Entered dimension " + dim); lastPos = null; }
        lastDim = dim;
        if (lastPos != null) {
            double dx = pos.x - lastPos.x, dz = pos.z - lastPos.z, h = Math.sqrt(dx * dx + dz * dz);
            double d = pos.distanceTo(lastPos);
            if (d > 8) {
                WorldMemory.add("move", String.format(Locale.ROOT, "Teleported %.0f blocks to %d %d %d", d, (int) pos.x, (int) pos.y, (int) pos.z));
            } else {
                WorldMemory.walked += h;
                WorldMemory.speed = WorldMemory.speed * 0.9 + (h * 20) * 0.1;
            }
        }
        lastPos = pos;
        boolean fly = p.getAbilities().flying, glide = p.isFallFlying(), swim = p.isSwimming(), ride = p.hasVehicle();
        if (fly && !wasFly) WorldMemory.add("move", "Started flying");
        if (glide && !wasGlide) WorldMemory.add("move", "Started gliding (elytra)");
        if (swim && !wasSwim) WorldMemory.add("move", "Started swimming");
        if (ride && !wasRide) WorldMemory.add("move", "Mounted " + (p.getVehicle() != null ? p.getVehicle().getName().getString() : "vehicle"));
        wasFly = fly; wasGlide = glide; wasSwim = swim; wasRide = ride;
    }

    private static void stats(ClientPlayerEntity p) {
        float hp = p.getHealth();
        if (lastHealth >= 0 && Math.abs(hp - lastHealth) >= 1) WorldMemory.add("stat", String.format(Locale.ROOT, "Health %.0f -> %.0f", lastHealth, hp));
        lastHealth = hp;
        int food = p.getHungerManager().getFoodLevel();
        if (lastFood >= 0 && food != lastFood) WorldMemory.add("stat", "Food " + lastFood + " -> " + food);
        lastFood = food;
        int lvl = p.experienceLevel;
        if (lastLevel >= 0 && lvl != lastLevel) WorldMemory.add("stat", "XP level " + lastLevel + " -> " + lvl);
        lastLevel = lvl;

        Map<String, Integer> now = new HashMap<>();
        for (int i = 0; i < p.getInventory().size(); i++) {
            ItemStack st = p.getInventory().getStack(i);
            if (!st.isEmpty()) now.merge(WorldMemory.clean(st.getName().getString()), st.getCount(), Integer::sum);
        }
        if (lastInv != null) {
            int n = 0;
            Set<String> keys = new HashSet<>(now.keySet());
            keys.addAll(lastInv.keySet());
            for (String k : keys) {
                int diff = now.getOrDefault(k, 0) - lastInv.getOrDefault(k, 0);
                if (diff != 0 && n++ < 6) WorldMemory.add("inv", (diff > 0 ? "+" : "") + diff + " " + k);
            }
        }
        lastInv = now;
    }

    private static void scoreboard(MinecraftClient mc) {
        Scoreboard sb = mc.world.getScoreboard();
        ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (obj == null) { WorldMemory.sidebarTitle = ""; WorldMemory.sidebar = List.of(); lastSb = new HashMap<>(); return; }
        List<String> lines = new ArrayList<>();
        sb.getScoreboardEntries(obj).stream()
                .filter(e -> !e.hidden())
                .sorted(Comparator.comparingInt(ScoreboardEntry::value).reversed())
                .limit(15)
                .forEach(e -> lines.add(WorldMemory.clean(
                        Team.decorateName(sb.getScoreHolderTeam(e.owner()), e.name()).getString())));
        WorldMemory.sidebarTitle = WorldMemory.clean(obj.getDisplayName().getString());
        WorldMemory.sidebar = lines;

        Map<String, String> now = new LinkedHashMap<>();
        for (String l : lines) now.put(l.replaceAll("[0-9][0-9,.]*", "#"), l);
        long t = System.currentTimeMillis();
        int n = 0;
        for (Map.Entry<String, String> e : now.entrySet()) {
            String old = lastSb.get(e.getKey());
            if (old != null && !old.equals(e.getValue()) && n < 4
                    && t - sbLogged.getOrDefault(e.getKey(), 0L) > 15_000) {
                WorldMemory.add("scoreboard", old + " -> " + e.getValue());
                sbLogged.put(e.getKey(), t);
                n++;
            }
        }
        lastSb = now;
    }

    private static void tab(MinecraftClient mc) {
        ClientPlayNetworkHandler nh = mc.getNetworkHandler();
        if (nh == null) return;
        PlayerListHud hud = mc.inGameHud.getPlayerListHud();
        List<String> names = nh.getPlayerList().stream()
                .map(e -> WorldMemory.clean(hud.getPlayerName(e).getString()))
                .filter(s -> !s.isBlank()).sorted().collect(Collectors.toList());
        Set<String> set = new HashSet<>(names);
        if (lastTab != null) {
            List<String> joined = set.stream().filter(s -> !lastTab.contains(s)).toList();
            List<String> left = lastTab.stream().filter(s -> !set.contains(s)).toList();
            if (joined.size() + left.size() <= 8) {
                for (String s : joined) WorldMemory.add("tab", "Appeared in tab list: " + s);
                for (String s : left) WorldMemory.add("tab", "Left tab list: " + s);
            }
        }
        lastTab = set;
        WorldMemory.tabPlayers = names;
        Text h = ((PlayerListHudAccessor) hud).edith$getHeader();
        Text f = ((PlayerListHudAccessor) hud).edith$getFooter();
        WorldMemory.tabHeader = h == null ? "" : WorldMemory.clean(h.getString());
        WorldMemory.tabFooter = f == null ? "" : WorldMemory.clean(f.getString());
    }

    private static void trackers(MinecraftClient mc, ClientPlayerEntity p) {
        Map<UUID, ClientBossBar> bars = ((BossBarHudAccessor) mc.inGameHud.getBossBarHud()).edith$getBossBars();
        List<String> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ClientBossBar b : bars.values()) {
            String n = WorldMemory.clean(b.getName().getString());
            names.add(n);
            out.add(n + " (" + Math.round(b.getPercent() * 100) + "%)");
        }
        for (String n : names) if (!lastBars.contains(n)) WorldMemory.add("tracker", "Boss bar appeared: " + n);
        lastBars = names;
        WorldMemory.bossBars = out;

        record Near(String text, double d) {}
        List<Near> near = new ArrayList<>();
        for (AbstractClientPlayerEntity o : mc.world.getPlayers()) {
            if (o == p) continue;
            double d = p.distanceTo(o);
            if (d > 64) continue;
            ItemStack h = o.getMainHandStack();
            near.add(new Near(String.format(Locale.ROOT, "%s %.0fm, holding %s", o.getName().getString(), d,
                    h.isEmpty() ? "nothing" : WorldMemory.clean(h.getName().getString())), d));
        }
        near.sort(Comparator.comparingDouble(Near::d));
        List<String> res = new ArrayList<>();
        for (Near n : near) { if (res.size() >= 10) break; res.add("player " + n.text()); }

        Map<String, Integer> counts = new HashMap<>();
        for (Entity e : mc.world.getEntities()) {
            if (e == p || e instanceof PlayerEntity) continue;
            if (p.squaredDistanceTo(e) <= 32 * 32) counts.merge(e.getType().getName().getString(), 1, Integer::sum);
        }
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
                .forEach(e -> res.add(e.getValue() + "x " + e.getKey() + " within 32m"));
        WorldMemory.nearby = res;
    }

    private static void menus(MinecraftClient mc) {
        Screen s = mc.currentScreen;
        if (!(s instanceof HandledScreen<?> hs) || s instanceof AbstractInventoryScreen) return;
        ScreenHandler h = hs.getScreenHandler();
        String title = WorldMemory.clean(s.getTitle().getString());
        List<String> items = new ArrayList<>();
        for (Slot slot : h.slots) {
            if (slot.inventory instanceof net.minecraft.entity.player.PlayerInventory) continue;
            ItemStack st = slot.getStack();
            if (st.isEmpty()) continue;
            StringBuilder line = new StringBuilder("slot ").append(slot.id).append(": ").append(st.getCount()).append("x ")
                    .append(WorldMemory.clean(st.getName().getString()));
            LoreComponent lore = st.get(DataComponentTypes.LORE);
            if (lore != null && !lore.lines().isEmpty()) {
                line.append(" | ");
                for (int i = 0; i < Math.min(3, lore.lines().size()); i++) {
                    if (i > 0) line.append(" / ");
                    line.append(WorldMemory.clean(lore.lines().get(i).getString()));
                }
            }
            String l = line.toString();
            items.add(l.length() > 200 ? l.substring(0, 200) : l);
            if (items.size() >= 54) break;
        }
        String hash = title + "#" + items.hashCode();
        if (hash.equals(lastMenuHash)) { WorldMemory.menuTime = System.currentTimeMillis(); return; }
        if (!title.equals(WorldMemory.menuTitle)) WorldMemory.add("menu", "Opened menu \"" + title + "\"");
        lastMenuHash = hash;
        WorldMemory.menuTitle = title;
        WorldMemory.menuItems = items;
        WorldMemory.menuTime = System.currentTimeMillis();
        WorldMemory.version++;
    }
}
