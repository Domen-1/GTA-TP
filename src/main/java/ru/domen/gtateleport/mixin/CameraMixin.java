package ru.domen.gtateleport.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.domen.gtateleport.SwitchCamera;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	private float depthFar;

	/** After the camera followed the player and before the frustum is built from it. */
	@Inject(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V", shift = At.Shift.AFTER))
	private void gtaTeleport$flyCamera(DeltaTracker deltaTracker, CallbackInfo ci) {
		SwitchCamera.Pose pose = SwitchCamera.frame(SwitchCamera.Pose.of((Camera) (Object) this), deltaTracker.getRealtimeDeltaTicks() / 20.0);
		if (pose != null) {
			this.setRotation(pose.yaw(), pose.pitch());
			this.setPosition(pose.pos());
			// The far plane follows the render distance; high up the ground would be behind it.
			this.depthFar = Math.max(this.depthFar, (float) (SwitchCamera.heightAboveGround() * 3.0));
		}
	}

	@ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
	private float gtaTeleport$fov(float fov) {
		return fov * SwitchCamera.fovScale();
	}
}
