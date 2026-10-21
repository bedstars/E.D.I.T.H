package com.edith;

import net.minecraft.client.DeltaTracker;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Small pill at the top of the screen while E.D.I.T.H is thinking, or when an answer arrived while the menu was closed. */
public final class EdithHud {
    private static final int BG = 0xE00B0F14, ACCENT = 0xFF2DE2E6, ACCENT_DIM = 0xFF17606A, GREEN = 0xFF3DFF8A, AMBER = 0xFFFFC857;

    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (hideGui(mc) || screen(mc) instanceof EdithScreen) return;
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

    // ---- version shims (26.2 moved Minecraft#screen and Options#hideGui) ----
    private static final String[] SCREEN_NAMES = {"screen", "currentScreen", "getScreen", "screen()"};
    private static final String[] HIDE_NAMES = {"hideGui", "hideGui()", "isHideGui", "getHideGui"};

    private static final Accessor SCREEN_ON_MC = new Accessor(SCREEN_NAMES);
    private static final Accessor SCREEN_ON_GUI = new Accessor(SCREEN_NAMES);
    private static final Accessor HIDE_ON_OPTIONS = new Accessor(HIDE_NAMES);

    /** The currently open screen, or null. */
    public static net.minecraft.client.gui.screens.Screen screen(Minecraft mc) {
        Object o = SCREEN_ON_MC.get(mc);
        if (o == null && mc != null) o = SCREEN_ON_GUI.get(mc.gui);
        return o instanceof net.minecraft.client.gui.screens.Screen s ? s : null;
    }

    /** true when the player hid the HUD (F1). */
    public static boolean hideGui(Minecraft mc) {
        if (mc == null) return false;
        return Boolean.TRUE.equals(HIDE_ON_OPTIONS.get(mc.options));
    }

    /** Finds a field or a no-arg method by trying several names; caches what worked per target class. */
    private static final class Accessor {
        private final String[] names;
        private Class<?> cls;
        private Field field;
        private Method method;
        private boolean resolved;

        Accessor(String[] names) {
            this.names = names;
        }

        synchronized Object get(Object target) {
            if (target == null) return null;
            try {
                if (!resolved || cls != target.getClass()) resolve(target.getClass());
                if (field != null) return field.get(target);
                if (method != null) return method.invoke(target);
            } catch (Throwable ignored) {
            }
            return null;
        }

        private void resolve(Class<?> c) {
            cls = c;
            field = null;
            method = null;
            resolved = true;
            for (String raw : names) {
                boolean isMethod = raw.endsWith("()") || raw.startsWith("get") || raw.startsWith("is");
                String n = raw.endsWith("()") ? raw.substring(0, raw.length() - 2) : raw;
                for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                    try {
                        if (!isMethod) {
                            Field f = k.getDeclaredField(n);
                            f.setAccessible(true);
                            field = f;
                            return;
                        }
                    } catch (NoSuchFieldException ignored) {
                    }
                    try {
                        Method m = k.getDeclaredMethod(n);
                        m.setAccessible(true);
                        method = m;
                        return;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
        }
    }

    private EdithHud() {}
}
