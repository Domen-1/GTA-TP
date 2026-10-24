package ru.domen.gtateleport.mixin;

import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.domen.gtateleport.ChunkHold;
import ru.domen.gtateleport.SwitchCamera;
import ru.domen.gtateleport.SwitchEffects;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Shadow
	public abstract List<Identifier> getRequestedPostEffects();

	/** Replays a few held packets before the camera moves, on the main thread like vanilla packet handling. */
	@Inject(method = "update", at = @At("HEAD"))
	private void gtaTeleport$replayHeldPackets(DeltaTracker deltaTracker, CallbackInfo ci) {
		ChunkHold.drain(SwitchCamera.drainBudgetNanos());
	}

	@Inject(method = "update", at = @At("TAIL"))
	private void gtaTeleport$requestEffect(DeltaTracker deltaTracker, CallbackInfo ci) {
		if (SwitchEffects.update()) {
			this.getRequestedPostEffects().add(SwitchEffects.POST_EFFECT);
		}
	}
}
