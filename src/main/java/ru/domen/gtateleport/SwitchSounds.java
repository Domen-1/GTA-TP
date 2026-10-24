package ru.domen.gtateleport;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.jspecify.annotations.Nullable;

/**
 * The switch sounds. They are played straight from the mod's sounds.json, without registering sound events, so the
 * mod stays client side only and joins any server.
 */
final class SwitchSounds {
	static final Identifier PREP = id("switch.prep");
	static final Identifier JUMP = id("switch.jump");
	static final Identifier PAN = id("switch.pan");
	static final Identifier LAND = id("switch.land");
	static final Identifier WIND = id("switch.wind");

	private static @Nullable Wind wind;

	private SwitchSounds() {}

	// MASTER and not UI: UI sounds keep playing in the pause menu.
	static void play(Identifier sound, float pitch, float volume) {
		SwitchConfig config = SwitchConfig.get();
		float finalVolume = volume * (float) (config.volume * (sound == PAN ? config.panVolume : config.jumpVolume));
		if (finalVolume <= 0.0F) {
			return;
		}
		Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(sound, SoundSource.MASTER, finalVolume, pitch,
				SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true));
	}

	/** The air rushing past while the camera is up; its volume and pitch follow the camera speed. */
	static void startWind() {
		if (wind == null || wind.isStopped()) {
			wind = new Wind();
			Minecraft.getInstance().getSoundManager().play(wind);
		}
	}

	static void stopAll() {
		if (wind != null) {
			Minecraft.getInstance().getSoundManager().stop(wind);
			wind = null;
		}
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(GtaTeleportClient.MOD_ID, path);
	}

	private static final class Wind extends AbstractTickableSoundInstance {
		Wind() {
			super(SoundEvent.createVariableRangeEvent(WIND), SoundSource.MASTER, SoundInstance.createUnseededRandom());
			this.looping = true;
			this.relative = true;
			this.attenuation = Attenuation.NONE;
			this.volume = 0.01F;
		}

		@Override
		public void tick() {
			float level = SwitchCamera.windLevel();
			if (!SwitchCamera.isActive() && level < 0.01F) {
				this.stop();
				return;
			}
			SwitchConfig config = SwitchConfig.get();
			this.volume = Math.max(0.01F, level * 0.8F * (float) (config.volume * config.windVolume));
			this.pitch = 0.7F + 0.55F * level;
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}
	}
}
