package com.edith;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** The E.D.I.T.H popup. Everything is drawn with rectangles + text, same look as the Blocks Per Sometimes menu. */
public final class EdithScreen extends Screen {
    private static final int W = 480, H = 316, ROW_H = 22, HEAD_H = 16;
    private static final int T_HEADER = 0, T_TOGGLE = 1, T_CYCLE = 3, T_STEP = 4, T_TEXT = 5, T_ACTION = 6, T_INFO = 7;

    // palette (ARGB) - same as the BPS menu
    private static final int BG = 0xF00B0F14, TITLE_BG = 0xFF070A0E, SIDE_BG = 0xFF0D1218;
    private static final int ROW = 0xFF141A21, ROW_HOVER = 0xFF1C242D, ROW_DARK = 0xFF0F1419;
    private static final int ACCENT = 0xFF2DE2E6, ACCENT_DIM = 0xFF17606A, LINE = 0xFF26323D;
    private static final int GREEN = 0xFF3DFF8A, GREEN_BG = 0xFF123A27, GREEN_BG_HOVER = 0xFF1A4E35;
    private static final int TEXT = 0xFFE6EDF3, TEXT_OFF = 0xFFC4CED8, DIM = 0xFF7D8A97, RED = 0xFFFF5C6C, AMBER = 0xFFFFC857;

    private static final String[] TABS = {"Chat", "Server", "Local", "Memory", "Brain"};
    private static final int[] TAB_ICONS = {Icons.CHAT, Icons.SERVER, Icons.PERSON, Icons.BOOK, Icons.CHIP};
    private static final String[] SPOTS = {"top-left", "top-middle", "top-right", "middle-left", "center", "middle-right",
            "bottom-left", "bottom-middle", "bottom-right"};

    private static int page = 0;
    private static final StringBuilder input = new StringBuilder();
    private static int cursor = 0;
    private static final List<String> sent = new ArrayList<>();
    private static int histIdx = -1;
    private static int scroll = 0;
    private static int memPage = 0;

    // ---- row model (for Server / Local / Brain pages) ----
    private static final class Field {
        Supplier<String> get;
        Consumer<String> set;
        int max = 24;
        boolean mask = false;
    }

    private static final class Row {
        int type;
        int icon = -1;
        String label = "";
        BooleanSupplier on;
        BooleanSupplier locked;
        Runnable click, minus, plus;
        Supplier<String> value;
        int fixedW = 0;
        Field field;

        int height() {
            return switch (type) {
                case T_HEADER -> HEAD_H;
                case T_INFO -> 12 + 10 * (label.split("\n").length);
                default -> ROW_H;
            };
        }
    }

    private record Hit(int x, int y, int w, int h, Runnable run) {}

    private final long openedAt = System.currentTimeMillis();
    private final List<Hit> hits = new ArrayList<>();
    private List<Row> rows;
    private Field active = null;
    private final StringBuilder buf = new StringBuilder();
    private float alpha = 1f;
    private float curScale = 1f;
    private float mouseLX, mouseLY;
    private int maxScroll = 0;
    private static final Map<String, ItemStack> STACKS = new HashMap<>();

    public EdithScreen() {
        super(Component.literal("E.D.I.T.H"));
        rows = build(page);
        Brain.unread = false;
    }

    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ row builders
    private static Row header(String text) {
        Row r = new Row();
        r.type = T_HEADER;
        r.label = text;
        return r;
    }

    private static Row info(String text) {
        Row r = new Row();
        r.type = T_INFO;
        r.label = text;
        return r;
    }

    private static Row toggle(int icon, String label, BooleanSupplier on, Runnable flip, BooleanSupplier locked) {
        Row r = new Row();
        r.type = T_TOGGLE;
        r.icon = icon;
        r.label = label;
        r.on = on;
        r.locked = locked;
        r.click = () -> {
            if (locked != null && locked.getAsBoolean()) return;
            flip.run();
            EdithConfig.get().save();
        };
        return r;
    }

    private static Row action(int icon, String label, Runnable run) {
        Row r = new Row();
        r.type = T_ACTION;
        r.icon = icon;
        r.label = label;
        r.click = run;
        return r;
    }

    private static Row cycle(int icon, String label, Supplier<String> value, Runnable next) {
        Row r = new Row();
        r.type = T_CYCLE;
        r.icon = icon;
        r.label = label;
        r.value = value;
        r.click = () -> {
            next.run();
            EdithConfig.get().save();
        };
        return r;
    }

    private static Row stepper(int icon, String label, Supplier<String> value, Runnable minus, Runnable plus) {
        Row r = new Row();
        r.type = T_STEP;
        r.icon = icon;
        r.label = label;
        r.value = value;
        r.minus = () -> { minus.run(); EdithConfig.get().save(); };
        r.plus = () -> { plus.run(); EdithConfig.get().save(); };
        r.click = () -> { };
        return r;
    }

    private static Row textRow(int icon, String label, int fixedW, Supplier<String> get, Consumer<String> set, int max, boolean mask) {
        Row r = new Row();
        r.type = T_TEXT;
        r.icon = icon;
        r.label = label;
        r.fixedW = fixedW;
        Field f = new Field();
        f.get = get;
        f.set = v -> { set.accept(v); EdithConfig.get().save(); };
        f.max = max;
        f.mask = mask;
        r.field = f;
        return r;
    }

    private List<Row> build(int p) {
        EdithConfig c = EdithConfig.get();
        List<Row> r = new ArrayList<>();
        BooleanSupplier lock = () -> !c.serverReading;
        switch (p) {
            case 1 -> {
                r.add(toggle(Icons.SERVER, "Server reading", () -> c.serverReading, () -> c.serverReading = !c.serverReading, null));
                r.add(header(c.serverReading ? "What E.D.I.T.H can see on the server" : "Client-only mode: server data is not read"));
                r.add(toggle(Icons.CHAT, "Chat log", () -> c.chat, () -> c.chat = !c.chat, lock));
                r.add(toggle(Icons.LIST, "Scoreboard", () -> c.scoreboard, () -> c.scoreboard = !c.scoreboard, lock));
                r.add(toggle(Icons.PERSON, "Tab list", () -> c.tab, () -> c.tab = !c.tab, lock));
                r.add(toggle(Icons.EYE, "Titles, action bar, boss bars", () -> c.titles, () -> c.titles = !c.titles, lock));
                r.add(toggle(Icons.CUBE, "Open menus (GUIs)", () -> c.menus, () -> c.menus = !c.menus, lock));
                r.add(toggle(Icons.SIGNAL, "Server block changes", () -> c.serverBlocks, () -> c.serverBlocks = !c.serverBlocks, lock));
                r.add(toggle(Icons.BOOK, "Q&A notebook (players' questions)", () -> c.qa, () -> c.qa = !c.qa, lock));
                r.add(toggle(Icons.KEY, "Include private messages", () -> c.privateMsgs, () -> c.privateMsgs = !c.privateMsgs, lock));
                r.add(stepper(Icons.LIST, "Chat lines sent to AI", () -> String.valueOf(c.chatLines),
                        () -> c.chatLines = Math.max(5, c.chatLines - 5), () -> c.chatLines = Math.min(150, c.chatLines + 5)));
            }
            case 2 -> {
                r.add(header("Always works - read from your own client"));
                r.add(toggle(Icons.ARROWS, "Movement & position", () -> c.movement, () -> c.movement = !c.movement, null));
                r.add(toggle(Icons.POWER, "Stats (health, hunger, XP)", () -> c.stats, () -> c.stats = !c.stats, null));
                r.add(toggle(Icons.CUBE, "My breaking / placing + trackers", () -> c.myBlocks, () -> c.myBlocks = !c.myBlocks, null));
                r.add(toggle(Icons.FISH, "Nearby entities", () -> c.entities, () -> c.entities = !c.entities, null));
                r.add(toggle(Icons.BOTTLE, "Inventory", () -> c.inventory, () -> c.inventory = !c.inventory, null));
                r.add(toggle(Icons.EYE, "Crosshair target", () -> c.lookAt, () -> c.lookAt = !c.lookAt, null));
                r.add(stepper(Icons.SCALE, "Nearby radius (blocks)", () -> String.valueOf(c.radius),
                        () -> c.radius = Math.max(4, c.radius - 4), () -> c.radius = Math.min(64, c.radius + 4)));
                r.add(info("Everything here is sampled on your PC. Nothing is sent\nanywhere until you press SEND in the chat tab."));
            }
            case 4 -> {
                r.add(header("Connection"));
                r.add(cycle(Icons.CHIP, "Provider", () -> "< " + c.provider.name() + " >", c::nextProvider));
                r.add(textRow(Icons.TEXT, "Model", 210, () -> c.model, v -> c.model = v.trim(), 60, false));
                r.add(textRow(Icons.KEY, "API key", 210, () -> c.apiKey, v -> c.apiKey = v.trim(), 200, true));
                r.add(textRow(Icons.SIGNAL, "Endpoint (blank = default)", 150, () -> c.endpoint, v -> c.endpoint = v.trim(), 200, false));
                r.add(stepper(Icons.SCALE, "Max reply tokens", () -> String.valueOf(c.maxTokens),
                        () -> c.maxTokens = Math.max(100, c.maxTokens - 100), () -> c.maxTokens = Math.min(4000, c.maxTokens + 100)));
                r.add(header("Conversation"));
                r.add(action(Icons.RESET, "Clear conversation", () -> { Brain.clear(); scroll = 0; }));
                r.add(info("Key is saved in config/edith.json, or set the\nEDITH_API_KEY environment variable instead."));
            }
            default -> {
            }
        }
        return r;
    }

    // ------------------------------------------------------------------ drawing helpers
    private int col(int argb) {
        int a = Math.round(((argb >>> 24) & 0xFF) * alpha);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        g.fill(x, y, x + w, y + h, col(color));
    }

    private void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        rect(g, x, y, w, 1, color);
        rect(g, x, y + h - 1, w, 1, color);
        rect(g, x, y, 1, h, color);
        rect(g, x + w - 1, y, 1, h, color);
    }

    private void txt(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        g.text(this.font, s, x, y, col(color), false);
    }

    private void txtCenter(GuiGraphicsExtractor g, String s, int cx, int y, int color) {
        txt(g, s, cx - this.font.width(s) / 2, y, color);
    }

    private void icon(GuiGraphicsExtractor g, int icon, int x, int y, int color) {
        Icons.draw(g, icon, x, y, col(color));
    }

    private void iconBox(GuiGraphicsExtractor g, int icon, int x, int y, int color) {
        rect(g, x, y, 14, 14, 0xFF0A0E13);
        outline(g, x, y, 14, 14, LINE);
        icon(g, icon, x + 3, y + 3, color);
    }

    private boolean hov(int x, int y, int w, int h) {
        return mouseLX >= x && mouseLX < x + w && mouseLY >= y && mouseLY < y + h;
    }

    private String clip(String s, int maxW) {
        if (this.font.width(s) <= maxW) return s;
        while (s.length() > 1 && this.font.width(s + "...") > maxW) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private String tail(String s, int maxW) {
        while (s.length() > 1 && this.font.width(s) > maxW) s = s.substring(1);
        return s;
    }

    private List<String> wrap(String s, int maxW) {
        List<String> out = new ArrayList<>();
        for (String para : s.split("\n", -1)) {
            if (para.isEmpty()) {
                out.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String test = line.length() == 0 ? word : line + " " + word;
                if (this.font.width(test) <= maxW) {
                    line.setLength(0);
                    line.append(test);
                    continue;
                }
                if (line.length() > 0) out.add(line.toString());
                line.setLength(0);
                String w = word;
                while (this.font.width(w) > maxW && w.length() > 1) {
                    int cut = w.length() - 1;
                    while (cut > 1 && this.font.width(w.substring(0, cut)) > maxW) cut--;
                    out.add(w.substring(0, cut));
                    w = w.substring(cut);
                }
                line.append(w);
            }
            out.add(line.toString());
        }
        return out;
    }

    private static String nice(String id) {
        String[] parts = id.replace("minecraft:", "").split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private static String ago(long t) {
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        if (s < 90) return s + "s ago";
        if (s < 5400) return (s / 60) + "m ago";
        if (s < 172800) return (s / 3600) + "h ago";
        return (s / 86400) + "d ago";
    }

    private static ItemStack stack(String id) {
        return STACKS.computeIfAbsent(id, k -> {
            try {
                Object ident = Identifier.fromNamespaceAndPath("minecraft", k);
                Object item = R.call(BuiltInRegistries.ITEM, "getValue", ident);
                if (item instanceof Item it) {
                    ItemStack st = new ItemStack(it);
                    if (!st.isEmpty()) return st;
                }
            } catch (Throwable ignored) {
            }
            return ItemStack.EMPTY;
        });
    }

    /** draws a real item icon if the renderer supports it; returns false if we should fall back to text */
    private boolean drawItem(GuiGraphicsExtractor g, String id, int x, int y) {
        if (id == null) return false;
        ItemStack st = stack(id);
        if (st.isEmpty() || !R.can(g, "item", st, 0, 0)) return false;
        R.call(g, "item", st, x, y);
        return true;
    }

    // ------------------------------------------------------------------ main render
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        hits.clear();
        EdithConfig cfg = EdithConfig.get();

        long now = System.currentTimeMillis();
        float t = Math.min(1f, (now - openedAt) / 300f);
        float u = t - 1f;
        float ease = 1f + 2.70158f * u * u * u + 1.70158f * u * u;
        float fit = Math.min(1f, Math.min((this.height - 8f) / H, (this.width - 8f) / W));
        curScale = (0.80f + 0.20f * ease) * fit;
        alpha = Math.min(1f, t * 2.2f);

        float cx = this.width / 2f, cy = this.height / 2f;
        mouseLX = cx + (mx - cx) / curScale;
        mouseLY = cy + (my - cy) / curScale;

        int x0 = (this.width - W) / 2;
        int y0 = (this.height - H) / 2;

        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx, cy);
        pose.scale(curScale, curScale);
        pose.translate(-cx, -cy);

        // panel + glow
        rect(g, x0 - 2, y0 - 2, W + 4, H + 4, 0x40000000 | (ACCENT & 0xFFFFFF));
        rect(g, x0 - 1, y0 - 1, W + 2, H + 2, ACCENT_DIM);
        rect(g, x0, y0, W, H, BG);

        // title bar
        rect(g, x0, y0, W, 26, TITLE_BG);
        iconBox(g, Icons.LOGO, x0 + 6, y0 + 6, ACCENT);
        txt(g, "E.D.I.T.H", x0 + 26, y0 + 5, ACCENT);
        txt(g, "SYS // EVEN DEAD I'M THE HERO  v1.0", x0 + 26, y0 + 15, DIM);
        int closeX = x0 + W - 22, closeY = y0 + 5;
        boolean hc = hov(closeX, closeY, 16, 16);
        rect(g, closeX, closeY, 16, 16, hc ? 0xFF3A1A20 : ROW_DARK);
        outline(g, closeX, closeY, 16, 16, hc ? RED : LINE);
        icon(g, Icons.CLOSE, closeX + 4, closeY + 4, hc ? RED : DIM);
        hits.add(new Hit(closeX, closeY, 16, 16, this::onClose));

        // mode chip (click to switch server link <-> client only)
        String mode = cfg.serverReading ? "SERVER LINK" : "CLIENT ONLY";
        int mw = this.font.width(mode) + 20;
        int mxp = closeX - 8 - mw, myp = y0 + 6;
        boolean hm = hov(mxp, myp, mw, 14);
        int modeCol = cfg.serverReading ? GREEN : AMBER;
        rect(g, mxp, myp, mw, 14, hm ? ROW_HOVER : ROW_DARK);
        outline(g, mxp, myp, mw, 14, hm ? modeCol : LINE);
        rect(g, mxp + 5, myp + 4, 6, 6, modeCol);
        txt(g, mode, mxp + 15, myp + 3, modeCol);
        hits.add(new Hit(mxp, myp, mw, 14, () -> {
            cfg.serverReading = !cfg.serverReading;
            cfg.save();
            rows = build(page);
        }));

        // title underline with a moving highlight
        rect(g, x0, y0 + 26, W, 1, ACCENT_DIM);
        int seg = 70;
        int sx = (int) ((now / 7) % (W + seg)) - seg;
        int a = Math.max(0, sx), b = Math.min(W, sx + seg);
        if (b > a) rect(g, x0 + a, y0 + 26, b - a, 1, ACCENT);

        // corner brackets
        int bl = 9;
        rect(g, x0 - 2, y0 - 2, bl, 2, ACCENT);
        rect(g, x0 - 2, y0 - 2, 2, bl, ACCENT);
        rect(g, x0 + W - bl + 2, y0 - 2, bl, 2, ACCENT);
        rect(g, x0 + W, y0 - 2, 2, bl, ACCENT);
        rect(g, x0 - 2, y0 + H, bl, 2, ACCENT);
        rect(g, x0 - 2, y0 + H - bl + 2, 2, bl, ACCENT);
        rect(g, x0 + W - bl + 2, y0 + H, bl, 2, ACCENT);
        rect(g, x0 + W, y0 + H - bl + 2, 2, bl, ACCENT);

        // sidebar
        rect(g, x0 + 4, y0 + 32, 96, H - 32 - 30, SIDE_BG);
        for (int i = 0; i < TABS.length; i++) {
            int ty = y0 + 36 + i * 26;
            int tx = x0 + 6, tw = 92, th = 22;
            boolean sel = i == page;
            boolean h = hov(tx, ty, tw, th);
            if (sel) {
                rect(g, tx, ty, tw, th, ROW_HOVER);
                rect(g, tx, ty, 2, th, ACCENT);
            } else if (h) {
                rect(g, tx, ty, tw, th, ROW);
            }
            icon(g, TAB_ICONS[i], tx + 8, ty + 7, sel ? ACCENT : DIM);
            txt(g, TABS[i], tx + 22, ty + 7, sel ? TEXT : TEXT_OFF);
            final int target = i;
            hits.add(new Hit(tx, ty, tw, th, () -> {
                page = target;
                active = null;
                rows = build(page);
            }));
        }

        // content
        int xr = x0 + 106, rw = W - 106 - 6;
        int ry = y0 + 34;
        int fy = y0 + H - 24;
        int contentBottom = fy - 4;
        if (page == 0) {
            drawChat(g, xr, ry, rw, contentBottom, now);
        } else if (page == 3) {
            drawMemory(g, xr, ry, rw, contentBottom);
        } else {
            for (Row r : rows) {
                drawRow(g, r, xr, ry, rw, now);
                ry += r.height();
            }
        }

        // footer
        rect(g, x0 + 4, fy - 2, W - 8, 1, LINE);
        boolean blink = (now / 600) % 2 == 0;
        String status;
        int statusCol;
        if (Brain.busy) {
            status = "THINKING";
            statusCol = AMBER;
        } else if (cfg.key().isEmpty() && !(cfg.provider == EdithConfig.Provider.OPENAI && !cfg.endpoint.isBlank())) {
            status = "NO KEY";
            statusCol = RED;
        } else {
            status = "ONLINE";
            statusCol = GREEN;
        }
        rect(g, x0 + 8, fy + 7, 5, 5, blink ? statusCol : ((statusCol & 0xFFFFFF) | 0x55000000));
        txt(g, status, x0 + 18, fy + 6, statusCol);
        txt(g, "[I] open menu   [ESC] close", x0 + 76, fy + 6, DIM);
        int dx = x0 + W - 70, dy = fy + 2;
        boolean hd = hov(dx, dy, 62, 16);
        rect(g, dx, dy, 62, 16, hd ? 0xFF14323A : 0xFF10222A);
        outline(g, dx, dy, 62, 16, hd ? ACCENT : ACCENT_DIM);
        txtCenter(g, "DONE", dx + 31, dy + 4, ACCENT);
        hits.add(new Hit(dx, dy, 62, 16, this::onClose));

        pose.popMatrix();
    }

    // ------------------------------------------------------------------ chat page
    private int blockHeight(Brain.Part p, int innerW, int shownChars) {
        if (p.craft != null) return craftHeight(p.craft, innerW);
        return linesFor(p, innerW, shownChars).size() * 10;
    }

    private List<String> linesFor(Brain.Part p, int innerW, int shownChars) {
        if (p.lines == null || p.cachedW != innerW) {
            p.lines = wrap(p.text, innerW);
            p.cachedW = innerW;
        }
        if (shownChars >= p.text.length()) return p.lines;
        List<String> out = new ArrayList<>();
        int budget = Math.max(0, shownChars);
        for (String l : p.lines) {
            if (budget <= 0) break;
            if (budget >= l.length() + 1) {
                out.add(l);
                budget -= l.length() + 1;
            } else {
                out.add(l.substring(0, budget));
                budget = 0;
            }
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    private Map<String, List<String>> legend(Brain.Craft c) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < 9; i++) {
            if (c.slots()[i] == null) continue;
            m.computeIfAbsent(nice(c.slots()[i]), k -> new ArrayList<>()).add(SPOTS[i]);
        }
        return m;
    }

    private List<String> legendLines(Brain.Craft c, int legendW) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : legend(c).entrySet()) {
            out.addAll(wrap(e.getKey() + ": " + String.join(", ", e.getValue()), legendW));
        }
        return out;
    }

    private int craftHeight(Brain.Craft c, int innerW) {
        int legendW = Math.max(60, innerW - 134);
        int lh = legendLines(c, legendW).size() * 10;
        return 14 + Math.max(74, lh) + 4;
    }

    private void drawCraft(GuiGraphicsExtractor g, Brain.Craft c, int x, int y, int innerW, int areaTop, int areaBottom) {
        int h = craftHeight(c, innerW);
        if (y < areaTop || y + h > areaBottom) return;
        txt(g, "RECIPE  " + nice(Brain.resultId(c.result())) + (c.shapeless() ? "  [shapeless]" : ""), x, y, ACCENT);
        int gx = x, gy = y + 13;
        for (int r = 0; r < 3; r++) {
            for (int k = 0; k < 3; k++) {
                int cx = gx + k * 24, cy = gy + r * 24;
                String id = c.slots()[r * 3 + k];
                rect(g, cx, cy, 22, 22, id == null ? ROW_DARK : ROW);
                outline(g, cx, cy, 22, 22, id == null ? LINE : ACCENT_DIM);
                if (id != null && !drawItem(g, id, cx + 3, cy + 3)) {
                    txtCenter(g, nice(id).substring(0, Math.min(3, nice(id).length())), cx + 11, cy + 7, TEXT_OFF);
                }
            }
        }
        // arrow + result
        txt(g, "->", gx + 78, gy + 32, ACCENT);
        int rx = gx + 96, ry = gy + 24;
        rect(g, rx, ry, 22, 22, GREEN_BG);
        outline(g, rx, ry, 22, 22, GREEN);
        String rid = Brain.resultId(c.result());
        if (!drawItem(g, rid, rx + 3, ry + 3)) txtCenter(g, nice(rid).substring(0, Math.min(3, nice(rid).length())), rx + 11, ry + 7, 0xFFC8FFE0);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("x\\s*(\\d+)\\s*$").matcher(c.result().strip());
        if (m.find()) txt(g, "x" + m.group(1), rx + 4, ry + 24, GREEN);
        // legend
        int lx = x + 126, ly = gy + 2;
        for (String line : legendLines(c, Math.max(60, innerW - 134))) {
            txt(g, line, lx, ly, TEXT_OFF);
            ly += 10;
        }
    }

    private void drawChat(GuiGraphicsExtractor g, int xr, int top, int rw, int bottom, long now) {
        int inputH = 20;
        int inputY = bottom - inputH;
        int areaTop = top, areaBottom = inputY - 4;
        int innerW = rw - 24;
        List<Brain.Msg> log = new ArrayList<>(Brain.log);

        if (log.isEmpty() && !Brain.busy) {
            drawWelcome(g, xr, areaTop, rw, areaBottom);
        } else {
            // measure
            int n = log.size();
            int[] hs = new int[n];
            int[] shown = new int[n];
            int total = 0;
            for (int i = 0; i < n; i++) {
                Brain.Msg m = log.get(i);
                int budget = Integer.MAX_VALUE;
                if (!m.user && !m.error) budget = (int) Math.max(0, (now - m.shownAt) * 0.2);
                shown[i] = budget;
                int h = 8;
                int remaining = budget;
                int partsShown = 0;
                for (Brain.Part p : m.parts) {
                    if (remaining <= 0 && partsShown > 0) break;
                    if (remaining <= 0) break;
                    int chars = p.craft != null ? 40 : p.text.length();
                    h += blockHeight(p, innerW, remaining) + (partsShown > 0 ? 4 : 0);
                    remaining -= chars;
                    partsShown++;
                }
                if (partsShown == 0) h += 10;
                hs[i] = h;
                total += h + 5;
            }
            int thinkH = Brain.busy ? 20 : 0;
            total += thinkH;
            int viewH = areaBottom - areaTop;
            maxScroll = Math.max(0, total - viewH);
            if (scroll > maxScroll) scroll = maxScroll;
            if (scroll < 0) scroll = 0;

            int y = areaBottom + scroll;
            if (Brain.busy) {
                y -= thinkH;
                if (y >= areaTop && y + 18 <= areaBottom) {
                    rect(g, xr, y, rw - 8, 18, ROW);
                    rect(g, xr, y, 2, 18, AMBER);
                    int dots = (int) ((now / 350) % 4);
                    txt(g, "E.D.I.T.H is thinking" + ".".repeat(dots), xr + 10, y + 5, AMBER);
                    int sw = 40, sxp = (int) ((now / 6) % (rw - 8 + sw)) - sw;
                    int aa = Math.max(0, sxp), bb = Math.min(rw - 8, sxp + sw);
                    if (bb > aa) rect(g, xr + aa, y + 16, bb - aa, 2, AMBER);
                }
            }
            for (int i = n - 1; i >= 0; i--) {
                y -= hs[i] + 5;
                if (y + hs[i] < areaTop || y > areaBottom) continue;
                drawMessage(g, log.get(i), xr, y, rw - 8, hs[i], innerW, shown[i], areaTop, areaBottom);
            }
            if (maxScroll > 0) {
                int barH = Math.max(12, viewH * viewH / total);
                int barY = areaTop + (int) ((viewH - barH) * (1f - (float) scroll / maxScroll));
                rect(g, xr + rw - 3, areaTop, 2, viewH, ROW_DARK);
                rect(g, xr + rw - 3, barY, 2, barH, ACCENT_DIM);
            }
        }

        // input box
        boolean typing = active == null;
        rect(g, xr, inputY, rw - 52, inputH, 0xFF090D11);
        outline(g, xr, inputY, rw - 52, inputH, typing ? ACCENT : LINE);
        int fieldW = rw - 52 - 12;
        String s = input.toString();
        if (s.isEmpty()) {
            txt(g, "Ask E.D.I.T.H anything...", xr + 6, inputY + 6, DIM);
        } else {
            int start = 0;
            while (start < cursor && this.font.width(s.substring(start, cursor)) > fieldW - 4) start++;
            int end = start;
            while (end < s.length() && this.font.width(s.substring(start, end + 1)) <= fieldW) end++;
            txt(g, s.substring(start, end), xr + 6, inputY + 6, TEXT);
        }
        if (typing && (now / 500) % 2 == 0) {
            int start = 0;
            while (start < cursor && this.font.width(s.substring(start, cursor)) > fieldW - 4) start++;
            int cxp = xr + 6 + this.font.width(s.substring(start, Math.min(cursor, s.length())));
            rect(g, cxp, inputY + 4, 1, 11, ACCENT);
        }
        int bx = xr + rw - 48, bw = 40;
        boolean canSend = !Brain.busy && !input.toString().isBlank();
        boolean hs2 = hov(bx, inputY, bw, inputH) && canSend;
        rect(g, bx, inputY, bw, inputH, hs2 ? 0xFF14323A : 0xFF10222A);
        outline(g, bx, inputY, bw, inputH, canSend ? (hs2 ? ACCENT : ACCENT_DIM) : LINE);
        icon(g, Icons.SEND, bx + 5, inputY + 6, canSend ? ACCENT : DIM);
        txt(g, "GO", bx + 18, inputY + 6, canSend ? ACCENT : DIM);
        hits.add(new Hit(bx, inputY, bw, inputH, this::send));
        hits.add(new Hit(xr, inputY, rw - 52, inputH, () -> {
        }));
    }

    private void drawWelcome(GuiGraphicsExtractor g, int xr, int top, int rw, int bottom) {
        int cx = xr + rw / 2;
        int y = top + 14;
        iconBox(g, Icons.LOGO, cx - 7, y, ACCENT);
        txtCenter(g, "E.D.I.T.H online", cx, y + 22, ACCENT);
        txtCenter(g, "Ask me anything: crafting, mobs, redstone, this server.", cx, y + 36, TEXT_OFF);
        txtCenter(g, EdithConfig.get().serverReading
                ? "I can see chat, scoreboard, tab and menus."
                : "Client-only: I only use what your own client does.", cx, y + 48, DIM);
        String[] chips = {"How do I craft a piston?", "What's on the scoreboard right now?", "What are players asking in chat?"};
        int cw = Math.min(rw - 40, 250);
        for (int i = 0; i < chips.length; i++) {
            int bx = cx - cw / 2, by = y + 70 + i * 22;
            boolean h = hov(bx, by, cw, 18);
            rect(g, bx, by, cw, 18, h ? ROW_HOVER : ROW);
            outline(g, bx, by, cw, 18, h ? ACCENT : LINE);
            icon(g, Icons.CHAT, bx + 6, by + 5, h ? ACCENT : DIM);
            txt(g, chips[i], bx + 20, by + 5, h ? TEXT : TEXT_OFF);
            final String q = chips[i];
            hits.add(new Hit(bx, by, cw, 18, () -> {
                input.setLength(0);
                input.append(q);
                cursor = input.length();
                send();
            }));
        }
    }

    private void drawMessage(GuiGraphicsExtractor g, Brain.Msg m, int x, int y, int w, int h, int innerW, int budget, int areaTop, int areaBottom) {
        int top = Math.max(y, areaTop), bot = Math.min(y + h, areaBottom);
        int barCol = m.error ? RED : (m.user ? ACCENT : GREEN);
        if (bot > top) {
            rect(g, x, top, w, bot - top, m.user ? ROW : ROW_DARK);
            rect(g, x, top, 2, bot - top, barCol);
        }
        int ty = y + 4;
        int remaining = budget;
        for (Brain.Part p : m.parts) {
            if (remaining <= 0) break;
            if (p.craft != null) {
                drawCraft(g, p.craft, x + 10, ty, innerW, areaTop, areaBottom);
                ty += craftHeight(p.craft, innerW) + 4;
                remaining -= 40;
            } else {
                List<String> ls = linesFor(p, innerW, remaining);
                for (int li = 0; li < ls.size(); li++) {
                    String line = ls.get(li);
                    if (ty >= areaTop && ty + 9 <= areaBottom) {
                        int color = m.error ? RED : (m.user ? TEXT_OFF : TEXT);
                        if (m.user && li == 0) {
                            txt(g, "> ", x + 10, ty, ACCENT);
                            txt(g, line, x + 10 + this.font.width("> "), ty, color);
                        } else {
                            txt(g, line, x + 10, ty, color);
                        }
                    }
                    ty += 10;
                }
                ty += 4;
                remaining -= p.text.length();
            }
        }
    }

    // ------------------------------------------------------------------ memory page
    private void drawMemory(GuiGraphicsExtractor g, int xr, int top, int rw, int bottom) {
        List<Memory.QA> all = Memory.all();
        int per = 4, entryH = 42;
        int pages = Math.max(1, (all.size() + per - 1) / per);
        if (memPage >= pages) memPage = pages - 1;
        if (memPage < 0) memPage = 0;

        EdithConfig cfg = EdithConfig.get();
        String head = cfg.serverReading
                ? all.size() + " questions heard  -  " + Memory.menuCount() + " menus remembered"
                : "Client-only mode: the notebook is paused";
        txt(g, head, xr + 2, top + 2, ACCENT);
        rect(g, xr, top + 13, rw, 1, ACCENT_DIM);

        int y = top + 18;
        if (all.isEmpty()) {
            txt(g, "Nothing yet. When players ask questions in chat,", xr + 4, y + 6, DIM);
            txt(g, "E.D.I.T.H writes down the question and the replies.", xr + 4, y + 18, DIM);
        }
        int start = all.size() - 1 - memPage * per;
        for (int i = 0; i < per; i++) {
            int idx = start - i;
            if (idx < 0) break;
            Memory.QA q = all.get(idx);
            rect(g, xr, y, rw, entryH - 3, ROW);
            rect(g, xr, y, 2, entryH - 3, q.answers.isEmpty() ? DIM : GREEN);
            txt(g, clip("Q " + q.asker + ": " + q.q, rw - 16), xr + 8, y + 4, TEXT);
            txt(g, q.answers.isEmpty() ? "no answer heard" : clip("A " + q.answers.get(0), rw - 16), xr + 8, y + 15, q.answers.isEmpty() ? DIM : TEXT_OFF);
            txt(g, ago(q.t) + (q.answers.size() > 1 ? "  -  " + q.answers.size() + " replies" : ""), xr + 8, y + 26, DIM);
            y += entryH;
        }

        // controls
        int cy = bottom - 18;
        stepBtn(g, xr, cy, 54, 16, "< PREV", () -> memPage = Math.max(0, memPage - 1));
        txtCenter(g, "PAGE " + (memPage + 1) + "/" + pages, xr + 54 + 60, cy + 4, TEXT_OFF);
        stepBtn(g, xr + 54 + 120 - 6, cy, 54, 16, "NEXT >", () -> memPage = Math.min(pages - 1, memPage + 1));
        int clw = 90;
        int clx = xr + rw - clw;
        boolean hv = hov(clx, cy, clw, 16);
        rect(g, clx, cy, clw, 16, hv ? 0xFF3A1A20 : ROW_DARK);
        outline(g, clx, cy, clw, 16, hv ? RED : LINE);
        txtCenter(g, "FORGET ALL", clx + clw / 2, cy + 4, hv ? RED : DIM);
        hits.add(new Hit(clx, cy, clw, 16, () -> {
            Memory.clear();
            memPage = 0;
        }));
    }

    private void stepBtn(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, Runnable run) {
        boolean hv = hov(x, y, w, h);
        rect(g, x, y, w, h, hv ? ROW_HOVER : ROW_DARK);
        outline(g, x, y, w, h, hv ? ACCENT : LINE);
        txtCenter(g, label, x + w / 2, y + 4, hv ? ACCENT : TEXT);
        hits.add(new Hit(x, y, w, h, run));
    }

    // ------------------------------------------------------------------ row pages
    private void drawRow(GuiGraphicsExtractor g, Row r, int xr, int y, int rw, long now) {
        int h = r.height();
        switch (r.type) {
            case T_HEADER -> {
                String t = r.label.toUpperCase(Locale.ROOT);
                txt(g, t, xr + 2, y + 4, ACCENT);
                int tw = this.font.width(t);
                rect(g, xr + 8 + tw, y + 8, rw - 8 - tw, 1, ACCENT_DIM);
            }
            case T_INFO -> {
                String[] lines = r.label.split("\n");
                for (int i = 0; i < lines.length; i++) txt(g, lines[i], xr + 4, y + 3 + i * 10, DIM);
            }
            case T_TOGGLE -> {
                boolean lock = r.locked != null && r.locked.getAsBoolean();
                boolean on = r.on.getAsBoolean() && !lock;
                boolean hv = hov(xr, y, rw, h - 2) && !lock;
                rect(g, xr, y, rw, h - 2, on ? (hv ? GREEN_BG_HOVER : GREEN_BG) : (hv ? ROW_HOVER : ROW));
                if (on) rect(g, xr, y, 2, h - 2, GREEN);
                iconBox(g, r.icon, xr + 6, y + 2, on ? GREEN : DIM);
                txt(g, r.label, xr + 26, y + 6, lock ? DIM : (on ? 0xFFC8FFE0 : TEXT_OFF));
                String st = lock ? "LOCKED" : (on ? "ON" : "OFF");
                txt(g, st, xr + rw - 8 - this.font.width(st), y + 6, on ? GREEN : DIM);
                int bx = xr + rw - 8 - this.font.width(lock ? "LOCKED" : "OFF") - 14;
                if (on) rect(g, bx, y + 6, 8, 8, GREEN);
                else outline(g, bx, y + 6, 8, 8, DIM);
                hits.add(new Hit(xr, y, rw, h - 2, r.click));
            }
            case T_CYCLE -> {
                boolean hv = hov(xr, y, rw, h - 2);
                rect(g, xr, y, rw, h - 2, hv ? ROW_HOVER : ROW);
                iconBox(g, r.icon, xr + 6, y + 2, ACCENT);
                txt(g, r.label, xr + 26, y + 6, TEXT);
                String v = r.value.get();
                txt(g, v, xr + rw - 8 - this.font.width(v), y + 6, ACCENT);
                hits.add(new Hit(xr, y, rw, h - 2, r.click));
            }
            case T_STEP -> {
                rect(g, xr, y, rw, h - 2, ROW);
                iconBox(g, r.icon, xr + 6, y + 2, ACCENT);
                txt(g, r.label, xr + 26, y + 6, TEXT);
                int pb = xr + rw - 6 - 16;
                int vx = pb - 4 - 70;
                int mb = vx - 4 - 16;
                stepBox(g, mb, y + 2, 16, h - 6, "-", r.minus);
                stepBox(g, vx, y + 2, 70, h - 6, r.value.get(), r.click);
                stepBox(g, pb, y + 2, 16, h - 6, "+", r.plus);
            }
            case T_TEXT -> {
                rect(g, xr, y, rw, h - 2, ROW);
                iconBox(g, r.icon, xr + 6, y + 2, ACCENT);
                txt(g, r.label, xr + 26, y + 6, TEXT);
                int aw = r.fixedW;
                int fx = xr + rw - 4 - aw;
                Field f = r.field;
                boolean act = active == f;
                String shown = act ? buf.toString() : f.get.get();
                if (f.mask) shown = "*".repeat(shown.length());
                rect(g, fx, y + 2, aw, h - 6, 0xFF090D11);
                outline(g, fx, y + 2, aw, h - 6, act ? ACCENT : LINE);
                String text = shown;
                if (act && (now / 500) % 2 == 0) text += "_";
                txt(g, tail(text, aw - 6), fx + 3, y + 6, TEXT);
                final Field target = f;
                hits.add(new Hit(fx, y + 2, aw, h - 6, () -> {
                    active = target;
                    buf.setLength(0);
                    buf.append(target.get.get());
                }));
            }
            case T_ACTION -> {
                boolean hv = hov(xr, y, rw, h - 2);
                rect(g, xr, y, rw, h - 2, hv ? 0xFF14323A : 0xFF10222A);
                outline(g, xr, y, rw, h - 2, hv ? ACCENT : ACCENT_DIM);
                icon(g, r.icon, xr + 8, y + 7, ACCENT);
                txtCenter(g, r.label, xr + rw / 2, y + 6, ACCENT);
                hits.add(new Hit(xr, y, rw, h - 2, r.click));
            }
            default -> {
            }
        }
    }

    private void stepBox(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, Runnable run) {
        stepBtn(g, x, y, w, h, label, run);
    }

    // ------------------------------------------------------------------ actions
    private void send() {
        String q = input.toString().strip();
        if (q.isEmpty() || Brain.busy) return;
        if (q.equalsIgnoreCase("/clear")) {
            Brain.clear();
            input.setLength(0);
            cursor = 0;
            return;
        }
        if (sent.isEmpty() || !sent.get(sent.size() - 1).equals(q)) sent.add(q);
        if (sent.size() > 30) sent.remove(0);
        histIdx = -1;
        input.setLength(0);
        cursor = 0;
        scroll = 0;
        Brain.ask(q);
    }

    // ------------------------------------------------------------------ input
    @Override
    public boolean mouseClicked(MouseButtonEvent ev, boolean doubleClick) {
        float cx = this.width / 2f, cy = this.height / 2f;
        float lx = cx + ((float) ev.x() - cx) / curScale;
        float ly = cy + ((float) ev.y() - cy) / curScale;
        if (ev.button() == 0) {
            active = null;
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (lx >= h.x() && lx < h.x() + h.w() && ly >= h.y() && ly < h.y() + h.h()) {
                    h.run().run();
                    return true;
                }
            }
        }
        return true;
    }

    /** no @Override on purpose: if the signature ever changes the menu still works (PageUp/PageDown scroll too) */
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (page == 0) {
            scroll = Math.max(0, Math.min(maxScroll, scroll + (int) (scrollY * 24)));
        } else if (page == 3) {
            memPage = Math.max(0, memPage - (int) Math.signum(scrollY));
        }
        return true;
    }

    private void apply() {
        if (active != null) active.set.accept(buf.toString());
    }

    @Override
    public boolean keyPressed(KeyEvent ev) {
        int k = ev.key();
        if (active != null) {
            if (k == 259) { // backspace
                if (buf.length() > 0) {
                    buf.deleteCharAt(buf.length() - 1);
                    apply();
                }
            } else if (k == 257 || k == 335 || k == 256) { // enter / esc
                active = null;
            } else if (k == 86 && Boolean.TRUE.equals(R.call(ev, "hasControlDown"))) { // ctrl+V
                String clip = clipboard();
                if (!clip.isEmpty()) {
                    int room = Math.max(0, active.max - buf.length());
                    buf.append(clip, 0, Math.min(room, clip.length()));
                    apply();
                }
            }
            return true;
        }
        if (page == 0) {
            boolean ctrl = Boolean.TRUE.equals(R.call(ev, "hasControlDown"));
            switch (k) {
                case 257, 335 -> send();
                case 259 -> {
                    if (cursor > 0) {
                        input.deleteCharAt(cursor - 1);
                        cursor--;
                    }
                }
                case 261 -> {
                    if (cursor < input.length()) input.deleteCharAt(cursor);
                }
                case 263 -> cursor = Math.max(0, cursor - 1);
                case 262 -> cursor = Math.min(input.length(), cursor + 1);
                case 268 -> cursor = 0;
                case 269 -> cursor = input.length();
                case 266 -> scroll = Math.min(maxScroll, scroll + 80);
                case 267 -> scroll = Math.max(0, scroll - 80);
                case 265 -> {
                    if (!sent.isEmpty()) {
                        histIdx = histIdx < 0 ? sent.size() - 1 : Math.max(0, histIdx - 1);
                        input.setLength(0);
                        input.append(sent.get(histIdx));
                        cursor = input.length();
                    }
                }
                case 264 -> {
                    if (histIdx >= 0) {
                        histIdx++;
                        input.setLength(0);
                        if (histIdx < sent.size()) input.append(sent.get(histIdx));
                        else histIdx = -1;
                        cursor = input.length();
                    }
                }
                case 86 -> {
                    if (ctrl) {
                        String clip = clipboard();
                        int room = Math.max(0, 400 - input.length());
                        if (!clip.isEmpty() && room > 0) {
                            String ins = clip.substring(0, Math.min(room, clip.length()));
                            input.insert(cursor, ins);
                            cursor += ins.length();
                        }
                    }
                }
                case 256 -> {
                    return super.keyPressed(ev);
                }
                default -> {
                }
            }
            return true;
        }
        return super.keyPressed(ev);
    }

    private String clipboard() {
        try {
            Object s = R.call(Minecraft.getInstance().keyboardHandler, "getClipboard");
            if (s == null) return "";
            return String.valueOf(s).replace("\r", " ").replace("\n", " ").replace("\t", " ");
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public boolean charTyped(CharacterEvent ev) {
        int cp = ev.codepoint();
        if (cp < 32 || cp == 127) return true;
        if (active != null) {
            if (buf.length() < active.max) {
                buf.append(Character.toChars(cp));
                apply();
            }
            return true;
        }
        if (page == 0 && input.length() < 400) {
            String s = new String(Character.toChars(cp));
            input.insert(cursor, s);
            cursor += s.length();
            histIdx = -1;
        }
        return true;
    }
}
