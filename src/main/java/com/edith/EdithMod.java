package com.edith;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;

/** E.D.I.T.H - in-game AI helper. Press I. */
public final class EdithMod implements ClientModInitializer {
    public static final String MOD_ID = "edith";

    private static int openDelay = 0;
    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        EdithConfig.load();

        // HUD pill ("thinking..." / "answer ready")
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "hud"),
                EdithHud::render);

        // Key bind (default: I)
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.edith.open", InputConstants.KEY_I, category));

        // /edith  (open)   /edith clear  (wipe the conversation)
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("edith")
                        .executes(ctx -> {
                            openDelay = 2; // opening straight from chat gets overwritten when chat closes
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
                                .executes(ctx -> {
                                    Brain.clear();
                                    return Command.SINGLE_SUCCESS;
                                }))));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Senses.tick(client);
            Memory.tick();
            while (openKey.consumeClick()) {
                openScreen(new EdithScreen());
            }
            if (openDelay > 0 && --openDelay == 0) {
                openScreen(new EdithScreen());
            }
        });

        // chat / system messages (system chat is how most servers send chat)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> Senses.onMessage(message, null, overlay));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> Senses.onMessage(message, sender, false));

        // your own breaking / placing
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> Senses.onLocalBreak(pos, state));
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            Senses.onUse(player, world, hand, hit);
            return InteractionResult.PASS;
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            Memory.onDisconnect();
            Senses.reset();
        });
    }

    private static void openScreen(net.minecraft.client.gui.screens.Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        for (String name : new String[]{"setScreenAndShow", "setScreen"}) {
            try {
                mc.getClass().getMethod(name, net.minecraft.client.gui.screens.Screen.class).invoke(mc, screen);
                return;
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                e.printStackTrace();
                return;
            }
        }
    }
}
