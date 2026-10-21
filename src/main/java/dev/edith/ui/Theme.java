package dev.edith.ui;

import dev.edith.EdithClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/** Cyber/terminal look: dark navy panels, cyan edges, green "active" rows. */
public final class Theme {
    private Theme() {}

    public static final int BG = 0xF0081119, ROW = 0xFF0E1A23, ROW_HOVER = 0xFF15293A, FIELD = 0xFF0A141B;
    public static final int BORDER = 0xFF1AA9B5, EDGE = 0xFF1B2D38;
    public static final int CYAN = 0xFF35F0F0, TEXT = 0xFFE6F2F5, DIM = 0xFF8CA2AB;
    public static final int GREEN = 0xFF3DFF8F, GREEN_BG = 0xFF0F3324, RED = 0xFFFF5C6C, AMBER = 0xFFFFC857;

    public static boolean hit(double mx, double my, int x1, int y1, int x2, int y2) {
        return mx >= x1 && mx < x2 && my >= y1 && my < y2;
    }

    public static void box(DrawContext g, int x1, int y1, int x2, int y2, int fill, int border) {
        g.fill(x1, y1, x2, y2, border);
        g.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, fill);
    }

    /** Bright L-shaped corner brackets. */
    public static void brackets(DrawContext g, int x1, int y1, int x2, int y2, int col) {
        int l = 9;
        g.fill(x1 - 1, y1 - 1, x1 + l, y1 + 1, col);   g.fill(x1 - 1, y1 - 1, x1 + 1, y1 + l, col);
        g.fill(x2 - l, y1 - 1, x2 + 1, y1 + 1, col);   g.fill(x2 - 1, y1 - 1, x2 + 1, y1 + l, col);
        g.fill(x1 - 1, y2 - 1, x1 + l, y2 + 1, col);   g.fill(x1 - 1, y2 - l, x1 + 1, y2 + 1, col);
        g.fill(x2 - l, y2 - 1, x2 + 1, y2 + 1, col);   g.fill(x2 - 1, y2 - l, x2 + 1, y2 + 1, col);
    }

    public static void text(DrawContext g, TextRenderer tr, String s, int x, int y, int col) {
        g.drawText(tr, s, x, y, col, EdithClient.CONFIG.textShadow);
    }

    public static void textRight(DrawContext g, TextRenderer tr, String s, int xRight, int y, int col) {
        text(g, tr, s, xRight - tr.getWidth(s), y, col);
    }

    public static void textCenter(DrawContext g, TextRenderer tr, String s, int cx, int y, int col) {
        text(g, tr, s, cx - tr.getWidth(s) / 2, y, col);
    }

    public static void iconBox(DrawContext g, TextRenderer tr, int x, int y, String glyph, int col) {
        box(g, x, y, x + 16, y + 16, ROW, col);
        textCenter(g, tr, glyph, x + 8, y + 4, col);
    }
}
