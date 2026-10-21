package com.edith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiny, forgiving reflection helper. Minecraft 26.x ships unobfuscated, so names are readable, but
 * some getters move between versions. Anything read through here fails soft (returns null) instead of
 * crashing the game or the build.
 */
public final class R {
    private static final Map<String, Method> CACHE = new ConcurrentHashMap<>();
    private static final Method NONE;

    static {
        Method m = null;
        try {
            m = Object.class.getMethod("hashCode");
        } catch (Exception ignored) {
        }
        NONE = m;
    }

    public static Object call(Object o, String name, Object... args) {
        if (o == null) return null;
        try {
            StringBuilder k = new StringBuilder(o.getClass().getName()).append('#').append(name);
            for (Object a : args) k.append(',').append(a == null ? "null" : a.getClass().getName());
            String key = k.toString();
            Method m = CACHE.get(key);
            if (m == null) {
                m = find(o.getClass(), name, args);
                CACHE.put(key, m == null ? NONE : m);
            }
            if (m == null || m == NONE) return null;
            m.setAccessible(true);
            return m.invoke(o, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** true if such a method exists (used to detect optional rendering features) */
    public static boolean can(Object o, String name, Object... args) {
        if (o == null) return false;
        try {
            return find(o.getClass(), name, args) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Method find(Class<?> c, String name, Object[] args) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) if (matches(m, name, args)) return m;
        }
        for (Method m : c.getMethods()) if (matches(m, name, args)) return m;
        return null;
    }

    private static boolean matches(Method m, String name, Object[] args) {
        if (!m.getName().equals(name) || m.getParameterCount() != args.length) return false;
        Class<?>[] pt = m.getParameterTypes();
        for (int i = 0; i < pt.length; i++) {
            if (args[i] == null) {
                if (pt[i].isPrimitive()) return false;
            } else if (!box(pt[i]).isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        if (c == boolean.class) return Boolean.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        return c;
    }

    public static Object field(Object o, String name) {
        if (o == null) return null;
        try {
            for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
                try {
                    Field f = k.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** Component -> plain string (also accepts plain strings). */
    public static String text(Object o) {
        if (o == null) return "";
        if (o instanceof net.minecraft.network.chat.Component c) return c.getString();
        return String.valueOf(o);
    }

    /** "ResourceKey[minecraft:dimension / minecraft:overworld]" -> "minecraft:overworld" */
    public static String keyName(Object resourceKey) {
        String s = str(resourceKey);
        int i = s.lastIndexOf('/');
        if (i >= 0) s = s.substring(i + 1);
        return s.replace("]", "").trim();
    }

    private R() {}
}
