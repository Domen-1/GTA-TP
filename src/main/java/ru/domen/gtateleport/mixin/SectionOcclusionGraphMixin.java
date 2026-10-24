package ru.domen.gtateleport.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.domen.gtateleport.SwitchCamera;

/** Vanilla skips sections further below the camera than the render distance; from the sky that is all the ground. */
@Mixin(SectionOcclusionGraph.class)
public abstract class SectionOcclusionGraphMixin {
	@ModifyExpressionValue(method = "getRelativeFrom", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;abs(I)I"))
	private int gtaTeleport$noHeightLimit(int sectionsBelow) {
		return SwitchCamera.liftsHeightLimit() ? 0 : sectionsBelow;
	}
}
