package ru.domen.gtateleport.mixin;

import net.minecraft.client.gui.spectator.PlayerMenuItem;
import net.minecraft.client.gui.spectator.SpectatorMenu;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.domen.gtateleport.SwitchCamera;

/** Remembers whom the spectator menu teleports to: the flash colour is that player's character. */
@Mixin(PlayerMenuItem.class)
public abstract class PlayerMenuItemMixin {
	@Shadow
	@Final
	private PlayerInfo playerInfo;

	@Inject(method = "selectItem", at = @At("HEAD"))
	private void gtaTeleport$rememberTarget(SpectatorMenu menu, CallbackInfo ci) {
		SwitchCamera.noteMenuTarget(this.playerInfo.getProfile().id());
	}
}
