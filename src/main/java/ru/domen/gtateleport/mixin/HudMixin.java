package ru.domen.gtateleport.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.domen.gtateleport.SwitchCamera;

/** No HUD while the camera is in the air, as if F1 was pressed. */
@Mixin(Hud.class)
public abstract class HudMixin {
	@ModifyExpressionValue(method = "extractRenderState", at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/Hud;isHidden:Z"))
	private boolean gtaTeleport$hideHud(boolean hidden) {
		return hidden || SwitchCamera.hidesHud();
	}

	@ModifyReturnValue(method = "isHidden", at = @At("RETURN"))
	private boolean gtaTeleport$reportHidden(boolean hidden) {
		return hidden || SwitchCamera.hidesHud();
	}
}
