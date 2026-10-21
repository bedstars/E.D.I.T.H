package dev.edith.ui;

import dev.edith.EdithClient;
import dev.edith.ai.CraftParser;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Message history + layout + rendering (wrapped text and crafting-grid widgets). */
public final class ChatLog {
    public enum Role { USER, EDITH, SYS }

    private record Msg(Role role, String text) {}
    private interface Row { int h(); void draw(DrawContext c, TextRenderer tr, int x, int y, int w); }

    private final List<Msg> msgs = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private boolean dirty = true, thinking;
    private int width = -1, total, version;

    public int version() { return version; }
    public void add(Role r, String t) { msgs.add(new Msg(r, t)); dirty = true; version++; }
    public void clear() { msgs.clear(); dirty = true; version++; }
    public void setThinking(boolean b) { if (thinking != b) { thinking = b; dirty = true; version++; } }

    /** Returns total content height for the given width. */
    public int layout(TextRenderer tr, int w) {
        if (!dirty && w == width) return total;
        dirty = false; width = w; rows.clear();
        for (Msg m : msgs) {
            String name = "SYS //"; int labelCol = Theme.AMBER, bodyCol = Theme.DIM;
            if (m.role == Role.USER) { name = "YOU >"; labelCol = Theme.GREEN; bodyCol = Theme.TEXT; }
            else if (m.role == Role.EDITH) { name = "E.D.I.T.H //"; labelCol = Theme.CYAN; bodyCol = Theme.TEXT; }
            rows.add(textRow(Text.literal(name).asOrderedText(), labelCol));

            List<CraftParser.Segment> segs = m.role == Role.EDITH
                    ? CraftParser.parse(m.text) : List.of(new CraftParser.Segment(m.text, null));
            for (CraftParser.Segment s : segs) {
                if (s.craft() != null) { rows.add(craftRow(s.craft())); continue; }
                for (String para : strip(s.text()).split("\n")) {
                    if (para.isBlank()) continue;
                    for (OrderedText ot : tr.wrapLines(Text.literal(para), w - 10)) rows.add(textRow(ot, bodyCol));
                }
            }
            rows.add(gap(5));
        }
        if (thinking) rows.add(new Row() {
            public int h() { return 12; }
            public void draw(DrawContext c, TextRenderer t, int x, int y, int w) {
                int d = (int) ((System.currentTimeMillis() / 350) % 4);
                c.drawText(t, "E.D.I.T.H is thinking" + ".".repeat(d), x + 4, y, Theme.DIM, false);
            }
        });
        total = 0;
        for (Row r : rows) total += r.h();
        return total;
    }

    public void draw(DrawContext c, TextRenderer tr, int x, int y, int w, int h, int scroll) {
        layout(tr, w);
        c.enableScissor(x, y, x + w, y + h);
        int yy = y - scroll;
        for (Row r : rows) {
            int rh = r.h();
            if (yy + rh >= y && yy <= y + h) r.draw(c, tr, x, yy, w);
            yy += rh;
        }
        c.disableScissor();
    }

    // ---------------------------------------------------------------- rows
    private static String strip(String s) {
        return s.replace("**", "").replace("__", "").replace("`", "")
                .replaceAll("(?m)^#+\\s*", "").replaceAll("(?m)^\\s*[-*]\\s+", "\u2022 ");
    }

    private static Row gap(int h) {
        return new Row() { public int h() { return h; } public void draw(DrawContext c, TextRenderer tr, int x, int y, int w) {} };
    }

    private static Row textRow(OrderedText t, int col) {
        return new Row() {
            public int h() { return 10; }
            public void draw(DrawContext c, TextRenderer tr, int x, int y, int w) {
                c.drawText(tr, t, x + 4, y, col, EdithClient.CONFIG.textShadow);
            }
        };
    }

    static ItemStack stack(String id) {
        if (id == null || id.isBlank()) return ItemStack.EMPTY;
        Identifier i = Identifier.tryParse(id.startsWith("#") ? id.substring(1) : id);
        if (i == null) return new ItemStack(Items.BARRIER);
        Item it = Registries.ITEM.get(i);
        return it == Items.AIR ? new ItemStack(Items.BARRIER) : new ItemStack(it);
    }

    private static String nameOf(String id) {
        ItemStack s = stack(id);
        return s.getItem() == Items.BARRIER ? id : s.getName().getString();
    }

    private static Row craftRow(CraftParser.Craft cr) {
        return new Row() {
            public int h() { return 68; }
            public void draw(DrawContext c, TextRenderer tr, int x, int y, int w) {
                int gx = x + 6, gy = y + 4;
                for (int i = 0; i < 9; i++) {
                    int sx = gx + (i % 3) * 20, sy = gy + (i / 3) * 20;
                    Theme.box(c, sx, sy, sx + 18, sy + 18, Theme.FIELD, 0xFF1F5560);
                    ItemStack st = stack(cr.grid().get(i));
                    if (st.isEmpty()) c.drawText(tr, String.valueOf(i + 1), sx + 6, sy + 5, 0xFF2C4650, false);
                    else c.drawItem(st, sx + 1, sy + 1);
                }
                c.drawText(tr, "->", gx + 63, gy + 26, Theme.CYAN, false);
                int rx = gx + 80, ry = gy + 20;
                Theme.box(c, rx, ry, rx + 18, ry + 18, Theme.FIELD, Theme.BORDER);
                ItemStack res = stack(cr.result());
                if (!res.isEmpty()) {
                    res.setCount(cr.count());
                    c.drawItem(res, rx + 1, ry + 1);
                    c.drawStackOverlay(tr, res, rx + 1, ry + 1);
                }
                // legend: which slots hold which item
                Map<String, List<Integer>> g = new LinkedHashMap<>();
                for (int i = 0; i < 9; i++) {
                    String id = cr.grid().get(i);
                    if (id != null && !id.isBlank()) g.computeIfAbsent(id, k -> new ArrayList<>()).add(i + 1);
                }
                int lx = gx + 108, ly = gy, maxW = Math.max(40, w - (lx - x) - 6);
                c.drawText(tr, tr.trimToWidth("= " + cr.count() + "x " + nameOf(cr.result()), maxW), lx, ly, Theme.CYAN, false);
                ly += 11;
                int n = 0;
                for (Map.Entry<String, List<Integer>> e : g.entrySet()) {
                    if (n++ >= 5) break;
                    StringBuilder slots = new StringBuilder();
                    for (int s : e.getValue()) { if (slots.length() > 0) slots.append(','); slots.append(s); }
                    c.drawText(tr, tr.trimToWidth(slots + ": " + nameOf(e.getKey()), maxW), lx, ly, Theme.TEXT, false);
                    ly += 10;
                }
            }
        };
    }
}
