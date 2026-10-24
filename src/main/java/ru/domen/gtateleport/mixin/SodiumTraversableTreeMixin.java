package ru.domen.gtateleport.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.domen.gtateleport.SwitchCamera;

/** Sodium checks the height limit a second time when it walks the visible sections. Applied only with Sodium. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.tree.TraversableTree", remap = false)
public abstract class SodiumTraversableTreeMixin {
	@Inject(method = "cylindricalDistanceTest", at = @At("HEAD"), cancellable = true, require = 0)
	private static void gtaTeleport$noHeightLimit(float dx, float dy, float dz, float distanceLimit, CallbackInfoReturnable<Boolean> cir) {
		if (SwitchCamera.liftsHeightLimit()) {
			cir.setReturnValue(dx * dx + dz * dz < distanceLimit * distanceLimit);
		}
	}
}
