package com.edith;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Small pill at the top of the screen while E.D.I.T.H is thinking, or when an answer arrived while the menu was closed. */
public final class EdithHud {
    private static final int BG = 0xE00B0F14, ACCENT = 0xFF2DE2E6, ACCENT_DIM = 0xFF17606A, GREEN = 0xFF3DFF8A, AMBER = 0xFFFFC857;

    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen instanceof EdithScreen) return;
        long now = System.currentTimeMillis();
        String label;
        int dot;
        if (Brain.busy) {
            int n = (int) ((now / 350) % 4);
            label = "E.D.I.T.H  thinking" + ".".repeat(n);
            dot = AMBER;
        } else if (Brain.unread && now - Brain.readyAt < 12_000) {
            label = "E.D.I.T.H  answer ready - press I";
            dot = GREEN;
        } else {
            return;
        }
        Font font = mc.font;
        int w = font.width(label) + 22;
        int x = (mc.getWindow().getGuiScaledWidth() - w) / 2;
        int y = 6;
        g.fill(x - 1, y - 1, x + w + 1, y + 15, ACCENT_DIM);
        g.fill(x, y, x + w, y + 14, BG);
        g.fill(x, y, x + 2, y + 14, ACCENT);
        boolean blink = (now / 400) % 2 == 0;
        g.fill(x + 7, y + 4, x + 13, y + 10, blink ? dot : 0xFF1F5F3A);
        g.text(font, label, x + 17, y + 3, ACCENT, false);
    }

    private EdithHud() {}
}
