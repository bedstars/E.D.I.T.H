package com.edith.mixin;

import com.edith.Senses;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets E.D.I.T.H see blocks the server removes/places around you. require = 0 means a renamed method is skipped quietly. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleBlockUpdate", at = @At("HEAD"), require = 0)
    private void edith$blockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        Senses.onBlockUpdate(packet.getPos(), packet.getBlockState());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("HEAD"), require = 0)
    private void edith$sectionUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        packet.runUpdates(Senses::onBlockUpdate);
    }
}
