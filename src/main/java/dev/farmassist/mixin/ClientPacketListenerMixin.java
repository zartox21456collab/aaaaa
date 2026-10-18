package dev.farmassist.mixin;

import dev.farmassist.TrailTracker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    // TAIL : ne s'exécute que sur le thread principal (le début de la méthode renvoie les paquets réseau vers lui)
    @Inject(method = "handleParticleEvent", at = @At("TAIL"))
    private void farmassist$onParticles(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        try {
            var key = BuiltInRegistries.PARTICLE_TYPE.getKey(packet.getParticle().getType());
            TrailTracker.add(key == null ? "?" : key.getPath(), packet.getX(), packet.getY(), packet.getZ());
        } catch (RuntimeException ignored) {
            // particule illisible : ignorée
        }
    }
}
