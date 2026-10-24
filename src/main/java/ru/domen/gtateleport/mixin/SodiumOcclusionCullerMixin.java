package ru.domen.gtateleport.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.domen.gtateleport.SwitchCamera;

/** The same height limit in Sodium's culling. Applied only when Sodium is there. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller", remap = false)
public abstract class SodiumOcclusionCullerMixin {
	@Inject(method = "testDistance", at = @At("HEAD"), cancellable = true, require = 0)
	private static void gtaTeleport$noHeightLimit(float xzThreshold, float yThreshold, float maxDistance, CallbackInfoReturnable<Boolean> cir) {
		if (SwitchCamera.liftsHeightLimit()) {
			cir.setReturnValue(xzThreshold < maxDistance * maxDistance);
		}
	}
}
