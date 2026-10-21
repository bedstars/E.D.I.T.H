package dev.edith.ui;

import dev.edith.EdithClient;
import dev.edith.EdithConfig;
import dev.edith.ai.AiClient;
import dev.edith.ai.ImageUtil;
import dev.edith.ai.Prompts;
import dev.edith.sense.WorldMemory;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class EdithScreen extends Screen {
    private enum Tab {
        CHAT("Chat", ">"), SENSORS("Sensors", "S"), MEMORY("Memory", "Q"), LOG("Live log", "L"), CORE("Core", "C");
        final String label, icon;
        Tab(String l, String i) { label = l; icon = i; }
    }
    private record Toggle(String name, String icon, Supplier<Boolean> get, Consumer<Boolean> set, boolean server) {}
    private record Line(OrderedText text, int color) {}

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static Tab lastTab = Tab.CHAT;
    private static boolean pending;

    private Tab tab = lastTab;
    private int px, py, pw, ph, cx, cy, cw, ch;
    private TextFieldWidget input, model, apiKey, endpoint;
    private List<Toggle> toggles;
    private int scroll, seenVersion = -1, ticks, cacheVer = -1, cacheW = -1;
    private boolean stick = true;
    private Tab cacheTab;
    private List<Line> cache = List.of();
    private final int[] chip = new int[4];

    public EdithScreen() { super(Text.literal("E.D.I.T.H")); }

    @Override public boolean shouldPause() { return false; }   // the game (and the sensors) keep running

    // ================================================================= init
    @Override
    protected void init() {
        EdithConfig c = EdithClient.CONFIG;
        pw = Math.min(width - 16, 420);
        ph = Math.min(height - 16, 276);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        cx = px + 108; cy = py + 36; cw = pw - 116; ch = ph - 64;

        input = field(cx + 16, cy + ch - 15, cw - 124, "Ask anything (drop images here)...");
        input.setMaxLength(600);

        int fx = cx + cw - 172;
        model = field(fx, rowY(1) + 7, 164, "model id");
        model.setText(c.model);
        model.setChangedListener(s -> c.model = s.trim());

        apiKey = field(fx, rowY(2) + 7, 164, "API key");
        apiKey.setText(c.apiKey);
        apiKey.setMaxLength(300);
        apiKey.setChangedListener(s -> c.apiKey = s.trim());
        apiKey.setRenderTextProvider((s, i) -> OrderedText.styledForwardsVisitedString("*".repeat(s.length()), Style.EMPTY));

        endpoint = field(fx, rowY(3) + 7, 164, "optional URL override");
        endpoint.setText(c.endpoint);
        endpoint.setMaxLength(300);
        endpoint.setChangedListener(s -> c.endpoint = s.trim());

        toggles = List.of(
                new Toggle("Server reading (master)", "#", () -> c.serverReading, v -> {
                    c.serverReading = v;
                    if (!v) WorldMemory.clearLive();
                }, false),
                new Toggle("Chat + Q&A notes", "C", () -> c.chat, v -> c.chat = v, true),
                new Toggle("Scoreboard", "B", () -> c.scoreboard, v -> c.scoreboard = v, true),
                new Toggle("Tab list", "T", () -> c.tab, v -> c.tab = v, true),
                new Toggle("Menus (GUIs)", "M", () -> c.menus, v -> c.menus = v, true),
                new Toggle("Trackers (bossbar, nearby)", "R", () -> c.trackers, v -> c.trackers = v, true),
                new Toggle("Movement (local)", "V", () -> c.movement, v -> c.movement = v, false),
                new Toggle("Break / place (local)", "X", () -> c.blocks, v -> c.blocks = v, false),
                new Toggle("Stats + inventory (local)", "+", () -> c.stats, v -> c.stats = v, false));

        setTab(tab);
    }

    private TextFieldWidget field(int x, int y, int w, String hint) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 12, Text.literal(hint));
        f.setDrawsBackground(false);
        f.setEditableColor(Theme.TEXT);
        f.setPlaceholder(Text.literal(hint).formatted(Formatting.DARK_GRAY));
        addSelectableChild(f);
        return f;
    }

    private int rowY(int i) { return cy + 4 + i * 24; }

    private void setTab(Tab t) {
        tab = t; lastTab = t; scroll = 0; stick = true;
        input.visible = input.active = t == Tab.CHAT;
        boolean core = t == Tab.CORE;
        for (TextFieldWidget f : new TextFieldWidget[]{model, apiKey, endpoint}) f.visible = f.active = core;
        setFocused(null);
        if (t == Tab.CHAT) setFocused(input);
    }

    @Override public void tick() { ticks++; }

    @Override public void removed() { EdithClient.CONFIG.save(); }

    // ================================================================= submit
    private void submit() {
        EdithConfig c = EdithClient.CONFIG;
        String typed = input.getText().trim();
        boolean hasImg = !EdithClient.ATTACH.isEmpty();
        if ((typed.isEmpty() && !hasImg) || pending) return;
        if (!c.ready()) {
            EdithClient.LOG.add(ChatLog.Role.SYS, "No API key set. Open the CORE tab and paste your key.");
            return;
        }
        final String q = typed.isEmpty() ? "Look at the attached image(s) and help me with what you see." : typed;
        input.setText("");
        final List<AiClient.Img> imgs = new ArrayList<>(EdithClient.ATTACH);
        EdithClient.ATTACH.clear();
        EdithClient.LOG.add(ChatLog.Role.USER, imgs.isEmpty() ? q : "[" + imgs.size() + " image" + (imgs.size() > 1 ? "s" : "") + "] " + q);

        List<AiClient.Turn> req = new ArrayList<>();
        List<AiClient.Turn> h = EdithClient.HISTORY;
        List<AiClient.Turn> recent = new ArrayList<>(h.subList(Math.max(0, h.size() - 12), h.size()));
        while (!recent.isEmpty() && !recent.get(0).role().equals("user")) recent.remove(0);
        req.addAll(recent);
        req.add(new AiClient.Turn("user", Prompts.wrap(q), imgs));   // fresh world snapshot + images on the newest message only
        h.add(new AiClient.Turn("user", imgs.isEmpty() ? q : q + " [player attached " + imgs.size() + " image(s)]"));

        pending = true;
        EdithClient.LOG.setThinking(true);
        AiClient.ask(Prompts.system(), req, reply -> {
            pending = false;
            EdithClient.LOG.setThinking(false);
            h.add(new AiClient.Turn("assistant", reply));
            EdithClient.LOG.add(ChatLog.Role.EDITH, reply);
        }, err -> {
            pending = false;
            EdithClient.LOG.setThinking(false);
            if (!h.isEmpty() && h.get(h.size() - 1).role().equals("user")) h.remove(h.size() - 1);
            for (AiClient.Img im : imgs) EdithClient.attach(im);   // keep them so you can retry
            EdithClient.LOG.add(ChatLog.Role.SYS, "Error: " + err);
        });
    }

    @Override
    public void onFilesDropped(List<Path> paths) {
        for (Path p : paths) {
            if (EdithClient.ATTACH.size() >= 4) { EdithClient.LOG.add(ChatLog.Role.SYS, "Attachment limit reached (4)."); break; }
            try {
                if (Files.size(p) > 25_000_000) throw new java.io.IOException("file too large (25 MB max)");
                EdithClient.attach(ImageUtil.fromBytes(Files.readAllBytes(p)));
            } catch (Exception e) {
                EdithClient.LOG.add(ChatLog.Role.SYS, "Could not read " + p.getFileName() + ": " + e.getMessage());
            }
        }
        if (tab != Tab.CHAT) setTab(Tab.CHAT);
    }

    // ================================================================= render
    @Override
    public void render(DrawContext g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        EdithConfig c = EdithClient.CONFIG;

        // --- frame
        Theme.box(g, px, py, px + pw, py + ph, Theme.BG, Theme.BORDER);
        Theme.brackets(g, px, py, px + pw, py + ph, Theme.CYAN);
        g.fill(px + 1, py + 28, px + pw - 1, py + 29, Theme.BORDER);
        int segX = px + 1 + (ticks * 2) % Math.max(1, pw - 72);
        g.fill(segX, py + 27, Math.min(px + pw - 1, segX + 70), py + 30, Theme.CYAN);

        // --- header
        Theme.iconBox(g, textRenderer, px + 8, py + 6, "E", Theme.CYAN);
        Theme.text(g, textRenderer, "E.D.I.T.H", px + 32, py + 6, Theme.CYAN);
        Theme.text(g, textRenderer, "SYS // ASSISTANT  v1.0", px + 32, py + 17, Theme.DIM);
        boolean hClose = Theme.hit(mx, my, px + pw - 26, py + 6, px + pw - 8, py + 24);
        Theme.box(g, px + pw - 26, py + 6, px + pw - 8, py + 24, hClose ? Theme.ROW_HOVER : Theme.ROW, Theme.BORDER);
        Theme.textCenter(g, textRenderer, "x", px + pw - 17, py + 11, Theme.TEXT);

        // --- tabs
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int ty = py + 36 + i * 26;
            boolean sel = tabs[i] == tab, hov = Theme.hit(mx, my, px + 8, ty, px + 100, ty + 22);
            g.fill(px + 8, ty, px + 100, ty + 22, sel ? Theme.ROW_HOVER : hov ? Theme.ROW : 0x00000000);
            if (sel) g.fill(px + 8, ty, px + 10, ty + 22, Theme.CYAN);
            Theme.text(g, textRenderer, tabs[i].icon, px + 17, ty + 7, sel ? Theme.CYAN : Theme.DIM);
            Theme.text(g, textRenderer, tabs[i].label, px + 32, ty + 7, sel ? Theme.CYAN : Theme.TEXT);
        }

        // --- content
        switch (tab) {
            case CHAT -> renderChat(g, mx, my, delta);
            case SENSORS -> renderSensors(g, mx, my);
            case MEMORY, LOG -> renderList(g, mx, my);
            case CORE -> renderCore(g, mx, my, delta);
        }

        // --- footer
        int fy = py + ph - 24;
        g.fill(px + 1, fy - 1, px + pw - 1, fy, Theme.EDGE);
        boolean ready = c.ready();
        g.fill(px + 10, fy + 9, px + 15, fy + 14, ready ? Theme.GREEN : Theme.AMBER);
        String st = ready ? "ACTIVE" : "NO API KEY";
        Theme.text(g, textRenderer, st, px + 20, fy + 8, ready ? Theme.GREEN : Theme.AMBER);
        Theme.text(g, textRenderer, "[I] toggle menu", px + 28 + textRenderer.getWidth(st), fy + 8, Theme.DIM);

        String mode = c.serverReading ? "SERVER LINK" : "CLIENT ONLY";
        int mw = textRenderer.getWidth(mode) + 14;
        chip[0] = px + pw - 74 - mw; chip[1] = fy + 4; chip[2] = chip[0] + mw; chip[3] = fy + 20;
        boolean hChip = Theme.hit(mx, my, chip[0], chip[1], chip[2], chip[3]);
        int mc = c.serverReading ? Theme.GREEN : Theme.AMBER;
        Theme.box(g, chip[0], chip[1], chip[2], chip[3], hChip ? Theme.ROW_HOVER : Theme.ROW, mc);
        Theme.textCenter(g, textRenderer, mode, (chip[0] + chip[2]) / 2, fy + 8, mc);

        boolean hDone = Theme.hit(mx, my, px + pw - 66, fy + 4, px + pw - 8, fy + 20);
        Theme.box(g, px + pw - 66, fy + 4, px + pw - 8, fy + 20, hDone ? Theme.ROW_HOVER : Theme.ROW, Theme.BORDER);
        Theme.textCenter(g, textRenderer, "DONE", px + pw - 37, fy + 8, Theme.CYAN);
    }

    private String chipLabel(int i) {
        AiClient.Img m = EdithClient.ATTACH.get(i);
        return "IMG" + (i + 1) + " " + m.w() + "x" + m.h() + " [x]";
    }

    private int[] chipBounds(int i) {
        int x = cx + 4;
        for (int k = 0; k < i; k++) x += textRenderer.getWidth(chipLabel(k)) + 16;
        return new int[]{x, x + textRenderer.getWidth(chipLabel(i)) + 10};
    }

    private void renderChat(DrawContext g, int mx, int my, float delta) {
        ChatLog log = EdithClient.LOG;
        int chips = EdithClient.ATTACH.isEmpty() ? 0 : 16;
        int logH = ch - 28 - chips;
        int total = log.layout(textRenderer, cw - 8);
        int max = Math.max(0, total - logH);
        if (log.version() != seenVersion) { seenVersion = log.version(); stick = true; }
        if (stick) scroll = max;
        scroll = Math.max(0, Math.min(scroll, max));
        if (scroll >= max) stick = true;
        log.draw(g, textRenderer, cx + 4, cy, cw - 8, logH, scroll);
        scrollbar(g, cx + cw - 3, cy, logH, total, scroll);

        int iy = cy + ch - 22;
        for (int i = 0; i < EdithClient.ATTACH.size(); i++) {
            int[] r = chipBounds(i);
            boolean hv = Theme.hit(mx, my, r[0], iy - 15, r[1], iy - 2);
            Theme.box(g, r[0], iy - 15, r[1], iy - 2, hv ? Theme.ROW_HOVER : Theme.FIELD, hv ? Theme.RED : Theme.GREEN);
            Theme.text(g, textRenderer, chipLabel(i), r[0] + 5, iy - 12, hv ? Theme.RED : Theme.GREEN);
        }
        Theme.box(g, cx, iy, cx + cw, iy + 22, Theme.ROW, input.isFocused() ? Theme.BORDER : Theme.EDGE);
        Theme.text(g, textRenderer, ">", cx + 6, iy + 7, Theme.CYAN);
        input.render(g, mx, my, delta);

        boolean hShot = Theme.hit(mx, my, cx + cw - 100, iy + 3, cx + cw - 56, iy + 19);
        Theme.box(g, cx + cw - 100, iy + 3, cx + cw - 56, iy + 19, hShot ? Theme.ROW_HOVER : Theme.FIELD, Theme.BORDER);
        Theme.textCenter(g, textRenderer, "SHOT", cx + cw - 78, iy + 7, Theme.CYAN);

        boolean hov = Theme.hit(mx, my, cx + cw - 52, iy + 3, cx + cw - 4, iy + 19);
        Theme.box(g, cx + cw - 52, iy + 3, cx + cw - 4, iy + 19, hov ? Theme.ROW_HOVER : Theme.FIELD, pending ? Theme.EDGE : Theme.BORDER);
        Theme.textCenter(g, textRenderer, pending ? "..." : "SEND", cx + cw - 28, iy + 7, pending ? Theme.DIM : Theme.CYAN);
    }

    private void renderSensors(DrawContext g, int mx, int my) {
        EdithConfig c = EdithClient.CONFIG;
        int total = toggles.size() * 22;
        int max = Math.max(0, total - ch);
        scroll = Math.max(0, Math.min(scroll, max));
        g.enableScissor(cx, cy, cx + cw, cy + ch);
        for (int i = 0; i < toggles.size(); i++) {
            Toggle t = toggles.get(i);
            int y = cy + i * 22 - scroll;
            boolean locked = t.server() && !c.serverReading;
            boolean on = t.get().get() && !locked;
            boolean hov = Theme.hit(mx, my, cx, y, cx + cw, y + 20) && !locked;
            g.fill(cx, y, cx + cw, y + 20, on ? Theme.GREEN_BG : hov ? Theme.ROW_HOVER : Theme.ROW);
            if (on) g.fill(cx, y, cx + 2, y + 20, Theme.GREEN);
            Theme.iconBox(g, textRenderer, cx + 6, y + 2, t.icon(), locked ? Theme.EDGE : Theme.CYAN);
            Theme.text(g, textRenderer, t.name(), cx + 28, y + 6, locked ? Theme.DIM : Theme.TEXT);
            String s = locked ? "LOCKED" : on ? "ON" : "OFF";
            int col = locked ? Theme.DIM : on ? Theme.GREEN : Theme.RED;
            Theme.textRight(g, textRenderer, s, cx + cw - 8, y + 6, col);
            if (!locked) g.fill(cx + cw - 20 - textRenderer.getWidth(s), y + 7, cx + cw - 14 - textRenderer.getWidth(s), y + 13, col);
        }
        g.disableScissor();
        scrollbar(g, cx + cw - 3, cy, ch, total, scroll);
    }

    private void renderList(DrawContext g, int mx, int my) {
        boolean mem = tab == Tab.MEMORY;
        String head = mem ? WorldMemory.qa.size() + " Q&A notes  |  " + WorldMemory.key()
                : WorldMemory.EVENTS.size() + " events (newest first)";
        Theme.text(g, textRenderer, textRenderer.trimToWidth(head, cw - 70), cx + 4, cy + 5, Theme.DIM);
        boolean hov = Theme.hit(mx, my, cx + cw - 52, cy + 1, cx + cw - 2, cy + 17);
        Theme.box(g, cx + cw - 52, cy + 1, cx + cw - 2, cy + 17, hov ? Theme.ROW_HOVER : Theme.FIELD, Theme.BORDER);
        Theme.textCenter(g, textRenderer, "CLEAR", cx + cw - 27, cy + 5, Theme.CYAN);

        List<Line> lines = lines();
        int top = cy + 22, h = ch - 22, total = lines.size() * 10;
        int max = Math.max(0, total - h);
        scroll = Math.max(0, Math.min(scroll, max));
        g.enableScissor(cx, top, cx + cw, top + h);
        for (int i = 0; i < lines.size(); i++) {
            int y = top + i * 10 - scroll;
            if (y + 10 < top || y > top + h) continue;
            g.drawText(textRenderer, lines.get(i).text(), cx + 4, y, lines.get(i).color(), EdithClient.CONFIG.textShadow);
        }
        g.disableScissor();
        scrollbar(g, cx + cw - 3, top, h, total, scroll);
    }

    private List<Line> lines() {
        if (cacheVer == WorldMemory.version && cacheW == cw && cacheTab == tab) return cache;
        List<Line> out = new ArrayList<>();
        int w = cw - 14;
        if (tab == Tab.MEMORY) {
            for (int i = WorldMemory.qa.size() - 1; i >= 0; i--) {
                WorldMemory.QA q = WorldMemory.qa.get(i);
                wrap("Q " + q.asker + ": " + q.question, w, Theme.CYAN, out);
                for (String a : q.answers) wrap("  A " + a, w, Theme.TEXT, out);
                out.add(new Line(OrderedText.EMPTY, 0));
            }
            if (out.isEmpty()) wrap("Nothing noted yet. With server reading + chat enabled, E.D.I.T.H records questions "
                    + "players ask in chat and the replies that follow, per server.", w, Theme.DIM, out);
        } else {
            Iterator<WorldMemory.Event> it = WorldMemory.EVENTS.descendingIterator();
            while (it.hasNext()) {
                WorldMemory.Event e = it.next();
                int col = switch (e.kind) {
                    case "chat" -> Theme.TEXT;
                    case "scoreboard", "stat" -> Theme.AMBER;
                    case "menu", "tab", "tracker", "actionbar" -> Theme.CYAN;
                    default -> Theme.GREEN;
                };
                wrap(TIME.format(Instant.ofEpochMilli(e.time)) + " " + e.kind + " " + e.text, w, col, out);
            }
            if (out.isEmpty()) wrap("No events yet. Play around with the sensors enabled.", w, Theme.DIM, out);
        }
        cache = out; cacheVer = WorldMemory.version; cacheW = cw; cacheTab = tab;
        return out;
    }

    private void wrap(String s, int w, int col, List<Line> out) {
        for (OrderedText t : textRenderer.wrapLines(Text.literal(s), w)) out.add(new Line(t, col));
    }

    private void renderCore(DrawContext g, int mx, int my, float delta) {
        EdithConfig c = EdithClient.CONFIG;
        String[] names = {"Provider", "Model", "API key", "Endpoint", "Text shadow", "Chat history"};
        String[] icons = {"P", "M", "K", "U", "A", "X"};
        for (int i = 0; i < names.length; i++) {
            int y = rowY(i);
            boolean hov = Theme.hit(mx, my, cx, y, cx + cw, y + 22);
            boolean on = i == 4 && c.textShadow;
            g.fill(cx, y, cx + cw, y + 22, on ? Theme.GREEN_BG : hov ? Theme.ROW_HOVER : Theme.ROW);
            if (on) g.fill(cx, y, cx + 2, y + 22, Theme.GREEN);
            Theme.iconBox(g, textRenderer, cx + 6, y + 3, icons[i], Theme.CYAN);
            Theme.text(g, textRenderer, names[i], cx + 28, y + 7, Theme.TEXT);
        }
        Theme.textRight(g, textRenderer, "< " + ("openai".equals(c.provider) ? "OPENAI-COMPATIBLE" : "ANTHROPIC") + " >", cx + cw - 8, rowY(0) + 7, Theme.CYAN);
        Theme.textRight(g, textRenderer, c.textShadow ? "ON" : "OFF", cx + cw - 8, rowY(4) + 7, c.textShadow ? Theme.GREEN : Theme.RED);
        Theme.textRight(g, textRenderer, "[ CLEAR ]", cx + cw - 8, rowY(5) + 7, Theme.AMBER);
        TextFieldWidget[] fs = {model, apiKey, endpoint};
        for (int i = 0; i < 3; i++) {
            int y = rowY(i + 1), fx = cx + cw - 180;
            Theme.box(g, fx, y + 3, cx + cw - 4, y + 19, Theme.FIELD, fs[i].isFocused() ? Theme.BORDER : Theme.EDGE);
            fs[i].setX(fx + 8);
            fs[i].render(g, mx, my, delta);
        }
        int y = rowY(6) + 2;
        for (OrderedText t : textRenderer.wrapLines(Text.literal(
                "Keys are saved in plain text in config/edith.json. Use provider OPENAI-COMPATIBLE with an endpoint "
                        + "such as http://localhost:11434/v1/chat/completions for a local model. Match the model id to the provider."),
                cw - 8)) {
            g.drawText(textRenderer, t, cx + 4, y, Theme.DIM, false);
            y += 10;
        }
    }

    private void scrollbar(DrawContext g, int x, int y, int h, int total, int scroll) {
        if (total <= h) return;
        int th = Math.max(12, h * h / total);
        int ty = y + (int) ((long) (h - th) * scroll / Math.max(1, total - h));
        g.fill(x, y, x + 2, y + h, Theme.EDGE);
        g.fill(x, ty, x + 2, ty + th, Theme.CYAN);
    }

    // ================================================================= input
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        EdithConfig c = EdithClient.CONFIG;
        int fy = py + ph - 24;

        if (Theme.hit(mx, my, px + pw - 26, py + 6, px + pw - 8, py + 24)
                || Theme.hit(mx, my, px + pw - 66, fy + 4, px + pw - 8, fy + 20)) { close(); return true; }
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int ty = py + 36 + i * 26;
            if (Theme.hit(mx, my, px + 8, ty, px + 100, ty + 22)) { setTab(tabs[i]); return true; }
        }
        if (Theme.hit(mx, my, chip[0], chip[1], chip[2], chip[3])) {
            c.serverReading = !c.serverReading;
            if (!c.serverReading) WorldMemory.clearLive();
            return true;
        }

        if (super.mouseClicked(mx, my, button)) return true;   // text fields
        setFocused(null);

        switch (tab) {
            case CHAT -> {
                int iy = cy + ch - 22;
                if (Theme.hit(mx, my, cx + cw - 52, iy + 3, cx + cw - 4, iy + 19)) { submit(); return true; }
                if (Theme.hit(mx, my, cx + cw - 100, iy + 3, cx + cw - 56, iy + 19)) {
                    if (EdithClient.ATTACH.size() >= 4) EdithClient.LOG.add(ChatLog.Role.SYS, "Attachment limit reached (4).");
                    else { EdithClient.shotDelay = 3; close(); }   // hide the menu for a few ticks, capture, reopen
                    return true;
                }
                for (int i = 0; i < EdithClient.ATTACH.size(); i++) {
                    int[] r = chipBounds(i);
                    if (Theme.hit(mx, my, r[0], iy - 15, r[1], iy - 2)) { EdithClient.ATTACH.remove(i); return true; }
                }
                if (Theme.hit(mx, my, cx, iy, cx + cw, iy + 22)) { setFocused(input); return true; }
            }
            case SENSORS -> {
                if (Theme.hit(mx, my, cx, cy, cx + cw, cy + ch)) {
                    int i = (int) ((my - cy + scroll) / 22);
                    if (i >= 0 && i < toggles.size()) {
                        Toggle t = toggles.get(i);
                        if (!(t.server() && !c.serverReading)) t.set().accept(!t.get().get());
                    }
                    return true;
                }
            }
            case MEMORY, LOG -> {
                if (Theme.hit(mx, my, cx + cw - 52, cy + 1, cx + cw - 2, cy + 17)) {
                    if (tab == Tab.MEMORY) WorldMemory.qa.clear(); else WorldMemory.EVENTS.clear();
                    WorldMemory.version++;
                    return true;
                }
            }
            case CORE -> {
                for (int i = 0; i < 6; i++) {
                    if (!Theme.hit(mx, my, cx, rowY(i), cx + cw, rowY(i) + 22)) continue;
                    switch (i) {
                        case 0 -> c.provider = "openai".equals(c.provider) ? "anthropic" : "openai";
                        case 1 -> setFocused(model);
                        case 2 -> setFocused(apiKey);
                        case 3 -> setFocused(endpoint);
                        case 4 -> c.textShadow = !c.textShadow;
                        case 5 -> { EdithClient.LOG.clear(); EdithClient.HISTORY.clear(); }
                    }
                    return true;
                }
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (Theme.hit(mx, my, cx, cy, cx + cw, cy + ch)) {
            scroll -= (int) (v * 16);
            if (v > 0) stick = false;
            if (scroll < 0) scroll = 0;
            return true;
        }
        return super.mouseScrolled(mx, my, h, v);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        Element f = getFocused();
        boolean typing = f instanceof TextFieldWidget tf && tf.isFocused();
        if (!typing && EdithClient.OPEN_KEY.matchesKey(key, scan)) { close(); return true; }
        if (typing && tab == Tab.CHAT && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) { submit(); return true; }
        return super.keyPressed(key, scan, mods);
    }
}
