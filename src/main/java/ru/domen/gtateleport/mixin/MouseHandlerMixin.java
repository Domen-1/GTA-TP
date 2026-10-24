package ru.domen.gtateleport.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.domen.gtateleport.SwitchCamera;

/** Mouse movement during the flight is dropped, so the player lands looking where the server turned them. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void gtaTeleport$freezeLook(double mousea, CallbackInfo ci) {
		if (SwitchCamera.locksInput()) {
			ci.cancel();
		}
	}
}
