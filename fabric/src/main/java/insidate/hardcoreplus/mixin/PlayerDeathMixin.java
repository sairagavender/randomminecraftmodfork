package insidate.hardcoreplus.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import insidate.hardcoreplus.Hardcoreplus;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(ServerPlayerEntity.class)
public class PlayerDeathMixin {
    // Inject into the start of the onDeath method for server players
    @Inject(at = @At("HEAD"), method = "onDeath(Lnet/minecraft/entity/damage/DamageSource;)V")
    private void onDeath(net.minecraft.entity.damage.DamageSource source, CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        MinecraftServer server = self.getServer();

        if (server == null) return;
        // Only run this logic on dedicated servers (leave singleplayer vanilla)
        try {
            if (!server.isDedicated()) {
                Hardcoreplus.LOGGER.debug("[hcp mixin] Not a dedicated server; skipping reset behavior");
                return;
            }
        } catch (Throwable ignored) {
            // If mapping differs or method is unavailable, conservatively do nothing
            return;
        }
        // If we're already processing a mass-death/reset, do nothing (guard against re-entrancy)
        if (Hardcoreplus.PROCESSING.get()) {
            Hardcoreplus.LOGGER.debug("[hcp mixin] Already processing; ignoring onDeath for {}", self.getGameProfile().getName());
            return;
        }

        Hardcoreplus.LOGGER.debug("[hcp mixin] onDeath invoked for {}", self.getGameProfile().getName());

        // SaveProperties / world properties expose whether the world is hardcore
        try {
            boolean isHardcore = false;
            if (server.getSaveProperties() != null) {
                isHardcore = server.getSaveProperties().isHardcore();
            }

            //if (!isHardcore) return; trying to make it work for non-hardcore modes too
            Hardcoreplus.LOGGER.debug("[hcp mixin] World is hardcore, proceeding to mass-kill then reset");

            // Perform the mass-kill using the helper (it uses its own PROCESSING guard)
            try {
                Hardcoreplus.performMassKill(server);
            } catch (Throwable t) {
                Hardcoreplus.LOGGER.warn("[hcp mixin] performMassKill failed", t);
            }

            Hardcoreplus.LOGGER.info("[hcp mixin] All players dead in hardcore world — requesting reset and server stop");
            try {
                Hardcoreplus.requestResetAndStop(server, self.getGameProfile().getName());
            } catch (Throwable t) {
                Hardcoreplus.LOGGER.error("[hcp mixin] requestResetAndStop failed", t);
            }
        } finally {
            // Do not manipulate PROCESSING flag here; it is managed centrally by performMassKill
        }
    }
}
