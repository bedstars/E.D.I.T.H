package com.edith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Version shims. Minecraft 26.2 moved/renamed a few things that 26.1.2 still has as public fields
 * (Minecraft#screen, Options#hideGui). We resolve them reflectively once, so the same source
 * compiles and runs on both versions.
 */
public final class Compat {
    private static final String[] SCREEN_NAMES = {"screen", "currentScreen", "getScreen", "screen()"};
    private static final String[] HIDE_NAMES = {"hideGui", "hideGui()", "isHideGui", "getHideGui"};

    private static final Accessor SCREEN_ON_MC = new Accessor(SCREEN_NAMES);
    private static final Accessor SCREEN_ON_GUI = new Accessor(SCREEN_NAMES);
    private static final Accessor HIDE_ON_OPTIONS = new Accessor(HIDE_NAMES);

    /** The currently open screen, or null. */
    public static Screen screen(Minecraft mc) {
        Object o = SCREEN_ON_MC.get(mc);
        if (o == null && mc != null) o = SCREEN_ON_GUI.get(mc.gui);
        return o instanceof Screen s ? s : null;
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

    private Compat() {}
}
