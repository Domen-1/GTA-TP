package ru.domen.gtateleport.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.domen.gtateleport.ChunkHold;
import ru.domen.gtateleport.SwitchCamera;

/**
 * Every hook sits right after the handler's hop to the main thread, so it runs once, on the main thread, in packet
 * order. The held handlers are called again later with the same packet.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	private static final String ON_MAIN_THREAD = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

	private ClientPacketListener self() {
		return (ClientPacketListener) (Object) this;
	}

	@Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER))
	private void gtaTeleport$beforeTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		SwitchCamera.beforeTeleport();
	}

	@Inject(method = "handleMovePlayer", at = @At("TAIL"))
	private void gtaTeleport$afterTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		SwitchCamera.afterTeleport();
	}

	@Inject(method = "handleRespawn", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER))
	private void gtaTeleport$respawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
		SwitchCamera.onWorldChange(true);
	}

	@Inject(method = "handleLogin", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER))
	private void gtaTeleport$login(ClientboundLoginPacket packet, CallbackInfo ci) {
		SwitchCamera.onWorldChange(false);
	}

	@Inject(method = "handleConfigurationStart", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER))
	private void gtaTeleport$configuration(ClientboundStartConfigurationPacket packet, CallbackInfo ci) {
		SwitchCamera.onWorldChange(false);
	}

	@Inject(method = "handleAddEntity", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER))
	private void gtaTeleport$addEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
		ChunkHold.noteAdded(packet.getId());
	}

	@Inject(method = "handleRemoveEntities", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER), cancellable = true)
	private void gtaTeleport$holdRemoveEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
		if (ChunkHold.deferRemoval(packet, self()::handleRemoveEntities)) {
			ci.cancel();
		}
	}

	@Inject(method = "handleLevelChunkWithLight", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER), cancellable = true)
	private void gtaTeleport$holdChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
		if (ChunkHold.defer(() -> self().handleLevelChunkWithLight(packet))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleForgetLevelChunk", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER), cancellable = true)
	private void gtaTeleport$holdForgetChunk(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
		if (ChunkHold.defer(() -> self().handleForgetLevelChunk(packet))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleSetChunkCacheCenter", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER), cancellable = true)
	private void gtaTeleport$holdChunkCenter(ClientboundSetChunkCacheCenterPacket packet, CallbackInfo ci) {
		if (ChunkHold.defer(() -> self().handleSetChunkCacheCenter(packet))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleSetChunkCacheRadius", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER), cancellable = true)
	private void gtaTeleport$holdChunkRadius(ClientboundSetChunkCacheRadiusPacket packet, CallbackInfo ci) {
		if (ChunkHold.defer(() -> self().handleSetChunkCacheRadius(packet))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleChunksBiomes", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER), cancellable = true)
	private void gtaTeleport$holdBiomes(ClientboundChunksBiomesPacket packet, CallbackInfo ci) {
		if (ChunkHold.defer(() -> self().handleChunksBiomes(packet))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleLightUpdatePacket", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER), cancellable = true)
	private void gtaTeleport$holdLight(ClientboundLightUpdatePacket packet, CallbackInfo ci) {
		if (ChunkHold.defer(() -> self().handleLightUpdatePacket(packet))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleChunkBlocksUpdate", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER), cancellable = true)
	private void gtaTeleport$holdSectionBlocks(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
		if (ChunkHold.defer(() -> self().handleChunkBlocksUpdate(packet))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleBlockUpdate", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER), cancellable = true)
	private void gtaTeleport$holdBlock(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
		if (ChunkHold.defer(() -> self().handleBlockUpdate(packet))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleBlockEntityData", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER), cancellable = true)
	private void gtaTeleport$holdBlockEntity(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
		if (ChunkHold.defer(() -> self().handleBlockEntityData(packet))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleBlockEvent", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER), cancellable = true)
	private void gtaTeleport$holdBlockEvent(ClientboundBlockEventPacket packet, CallbackInfo ci) {
		if (ChunkHold.defer(() -> self().handleBlockEvent(packet))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleBlockDestruction", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER), cancellable = true)
	private void gtaTeleport$holdBlockDestruction(ClientboundBlockDestructionPacket packet, CallbackInfo ci) {
		if (ChunkHold.defer(() -> self().handleBlockDestruction(packet))) {
			ci.cancel();
		}
	}
}
