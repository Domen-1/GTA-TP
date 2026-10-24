package ru.domen.gtateleport.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import ru.domen.gtateleport.SwitchCamera;

/**
 * Records the switch for the README and the release. Only with the GTA_TELEPORT_RECORD environment variable set:
 * flies a few teleports frame by frame (the switch runs on a fixed step, so every screenshot is 1/30 s of it) and
 * writes the frames, their times and the sounds played to build/run/clientGameTest/recordings/&lt;name&gt;.
 */
public class GtaTeleportRecording implements FabricClientGameTest {
	static final boolean ENABLED = System.getenv("GTA_TELEPORT_RECORD") != null;
	/** A waited tick and a screenshot each draw a frame: two steps make 1/30 s per recorded frame. */
	private static final double STEP = 1.0 / 60.0;
	private static final double FRAME = 1.0 / 30.0;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!ENABLED) {
			return;
		}
		context.getInput().resizeWindow(1280, 720);
		context.runOnClient(mc -> {
			mc.options.renderDistance().set(12);
			if (!mc.gui.hud.isHidden()) {
				mc.gui.hud.toggle();
			}
		});
		try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false).create()) {
			context.waitTicks(100);
			world.getConnection().waitForChunksRender(false, 2400);
			world.getServer().runCommand("time set 6000");
			world.getServer().runCommand("gamerule advance_time false");
			world.getServer().runCommand("weather clear");
			world.getServer().runCommand("gamemode spectator @a");
			world.getServer().runCommand(over(world, 0, 0, 30.0F, 30.0F));
			if (FabricLoader.getInstance().isModLoaded("distanthorizons")) {
				context.waitTicks(3000);
			}
			context.waitTicks(100);
			world.getConnection().waitForChunksRender(false, 2400);

			record(context, world, "far", over(world, 650, -350, -40.0F, 25.0F));
			record(context, world, "medium", over(world, -160, 110, 140.0F, 20.0F));
			record(context, world, "near", over(world, 30, -25, 20.0F, 20.0F));
		}
	}

	/** A teleport by (dx, dz) to 14 blocks over the ground there, so the switch never ends inside a mountain. */
	private static String over(TestSingleplayerContext world, int dx, int dz, float yaw, float pitch) {
		return world.getServer().computeOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			int x = player.getBlockX() + dx;
			int z = player.getBlockZ() + dz;
			// An unloaded chunk reports the bottom of the world: generate it first.
			player.level().getChunk(x >> 4, z >> 4);
			int y = player.level().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 14;
			return String.format(Locale.ROOT, "tp @a %d.5 %d %d.5 %.1f %.1f", x, y, z, yaw, pitch);
		});
	}

	private static void record(ClientGameTestContext context, TestSingleplayerContext world, String name, String command) {
		Path dir = FabricLoader.getInstance().getGameDir().resolve("recordings").resolve(name);
		List<String> frames = new ArrayList<>();
		List<String> sounds = new ArrayList<>();
		SoundEventListener listener = (sound, events, range) -> {
			if (sound.getSound() != null && sound.getIdentifier().getNamespace().equals("gta_teleport")) {
				sounds.add(String.format(Locale.ROOT, "%.4f,%s,%.3f,%.3f", Math.max(SwitchCamera.flightTime(), 0.0),
						sound.getSound().getLocation(), sound.getPitch(), sound.getVolume()));
			}
		};
		context.waitTicks(60);
		world.getConnection().waitForChunksRender(false, 1200);
		context.runOnClient(mc -> {
			mc.gui.hud.getChat().clearMessages(false);
			mc.getSoundManager().addListener(listener);
		});

		int preRoll = 15;
		for (int i = 0; i < preRoll; i++) {
			context.waitTick();
			shot(context, dir, frames, (i - preRoll) * FRAME, 0.0F);
		}
		SwitchCamera.setFixedFrameStep(STEP);
		world.getServer().runCommand(command);
		context.waitFor(mc -> SwitchCamera.isActive(), 100);
		while (context.computeOnClient(mc -> SwitchCamera.isActive())) {
			context.waitTick();
			double time = context.computeOnClient(mc -> SwitchCamera.flightTime());
			float wind = context.computeOnClient(mc -> SwitchCamera.windLevel());
			if (time >= 0.0) {
				shot(context, dir, frames, time, wind);
			}
		}
		SwitchCamera.setFixedFrameStep(0.0);
		double end = Double.parseDouble(frames.getLast().split(",")[1]);
		for (int i = 1; i <= 24; i++) {
			context.waitTick();
			shot(context, dir, frames, end + i * FRAME, 0.0F);
		}
		context.runOnClient(mc -> mc.getSoundManager().removeListener(listener));
		try {
			Files.write(dir.resolve("frames.csv"), frames);
			Files.write(dir.resolve("sounds.csv"), sounds);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void shot(ClientGameTestContext context, Path dir, List<String> frames, double time, float wind) {
		String file = String.format(Locale.ROOT, "f%04d", frames.size());
		context.takeScreenshot(TestScreenshotOptions.of(file).withDestinationDir(dir).disableCounterPrefix());
		frames.add(String.format(Locale.ROOT, "%s.png,%.4f,%.3f", file, time, wind));
	}
}
