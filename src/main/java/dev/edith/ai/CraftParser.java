package dev.edith.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits an AI reply into plain-text segments and crafting-grid segments. */
public final class CraftParser {
    private CraftParser() {}

    public record Craft(String result, int count, List<String> grid) {}
    public record Segment(String text, Craft craft) {}

    private static final Pattern P = Pattern.compile(
            "<craft\\s+result=\"([^\"]+)\"(?:\\s+count=\"(\\d{1,2})\")?\\s*>(.*?)</craft>", Pattern.DOTALL);

    public static List<Segment> parse(String s) {
        List<Segment> out = new ArrayList<>();
        Matcher m = P.matcher(s);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) out.add(new Segment(s.substring(last, m.start()), null));
            String[] parts = m.group(3).split(",", -1);
            List<String> grid = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                String p = i < parts.length ? parts[i].trim() : "";
                grid.add(p.equals("-") ? "" : p);
            }
            int n = m.group(2) == null ? 1 : Math.min(64, Math.max(1, Integer.parseInt(m.group(2))));
            out.add(new Segment("", new Craft(m.group(1).trim(), n, grid)));
            last = m.end();
        }
        if (last < s.length()) out.add(new Segment(s.substring(last), null));
        return out;
    }
}
