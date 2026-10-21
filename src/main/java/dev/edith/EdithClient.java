package dev.edith;

import dev.edith.ai.AiClient;
import dev.edith.ai.ImageUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import dev.edith.sense.Sensors;
import dev.edith.ui.ChatLog;
import dev.edith.ui.EdithScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class EdithClient implements ClientModInitializer {
    public static EdithConfig CONFIG;
    public static KeyBinding OPEN_KEY, SHOT_KEY;
    /** Images waiting to be sent with the next message (max 4). */
    public static final List<AiClient.Img> ATTACH = new ArrayList<>();
    /** Ticks until the in-menu SHOT button captures the screen and reopens the menu (-1 = idle). */
    public static int shotDelay = -1;

    public static boolean attach(AiClient.Img i) {
        if (ATTACH.size() >= 4) return false;
        ATTACH.add(i);
        return true;
    }

    private static void quickShot(MinecraftClient mc, boolean toast) {
        String msg;
        try {
            msg = attach(ImageUtil.captureScreen(mc)) ? "Screenshot attached (" + ATTACH.size() + "/4). Press I to ask about it."
                                                      : "Attachment limit reached (4).";
        } catch (Exception e) { msg = "Screenshot failed: " + e.getMessage(); }
        if (toast && mc.player != null) mc.player.sendMessage(Text.literal("E.D.I.T.H: " + msg), true);
    }
    public static final ChatLog LOG = new ChatLog();
    /** Plain conversation history (no world_state blocks; those are only attached to the newest message). */
    public static final List<AiClient.Turn> HISTORY = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        CONFIG = EdithConfig.load();
        OPEN_KEY = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.edith.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_I, "category.edith"));
        SHOT_KEY = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.edith.shot", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, "category.edith"));
        Sensors.register();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (OPEN_KEY.wasPressed()) {
                if (mc.currentScreen == null) mc.setScreen(new EdithScreen());
            }
            while (SHOT_KEY.wasPressed()) {
                if (mc.currentScreen == null) quickShot(mc, true);
            }
            if (shotDelay > 0) shotDelay--;
            else if (shotDelay == 0) {
                shotDelay = -1;
                quickShot(mc, false);
                mc.setScreen(new EdithScreen());
            }
        });
        LOG.add(ChatLog.Role.SYS, "E.D.I.T.H online. Add your API key in the CORE tab, then ask me anything.");
    }
}
