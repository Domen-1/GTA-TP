package ru.domen.gtateleport.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.domen.gtateleport.SwitchCamera;

/**
 * The render distance fog counts height too, so from high up the ground right under the camera would be all fog.
 * While the camera is up, the fog starts below the ground instead. Done before the method returns: Sodium copies the
 * fog values for its own shaders on return.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	@Inject(method = "setupFog", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/fog/FogData;renderDistanceEnd:F",
			opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
	private void gtaTeleport$fogBelowTheCamera(CallbackInfoReturnable<FogData> cir, @Local FogData fog) {
		float height = (float) SwitchCamera.heightAboveGround();
		if (height > 0.0F) {
			float span = fog.renderDistanceEnd - fog.renderDistanceStart;
			fog.renderDistanceStart = Math.max(fog.renderDistanceStart, height + 32.0F);
			fog.renderDistanceEnd = Math.max(fog.renderDistanceEnd, fog.renderDistanceStart + span);
			float environmentalSpan = Math.max(fog.environmentalEnd - fog.environmentalStart, 64.0F);
			fog.environmentalStart = Math.max(fog.environmentalStart, height);
			fog.environmentalEnd = Math.max(fog.environmentalEnd, fog.environmentalStart + environmentalSpan);
		}
	}
}
