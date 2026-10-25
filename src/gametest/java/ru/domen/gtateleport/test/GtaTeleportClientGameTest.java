package ru.domen.gtateleport.test;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.domen.gtateleport.ChunkHold;
import ru.domen.gtateleport.SwitchCamera;
import ru.domen.gtateleport.SwitchConfigScreen;

/**
 * A spectator in a normal singleplayer world is teleported far, medium and near; every few ticks of each switch is
 * screenshotted to build/run/clientGameTest/screenshots with the phase in the log.
 */
public class GtaTeleportClientGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("gta_teleport-test");

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GtaTeleportRecording.ENABLED) {
			return;
		}
		context.getInput().resizeWindow(1280, 720);
		context.runOnClient(mc -> mc.options.renderDistance().set(10));
		try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false).create()) {
			// The full check wants a square of chunks, the server sends a circle: only wait for what arrived to be drawn.
			context.waitTicks(100);
			world.getConnection().waitForChunksRender(false, 2400);
			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("weather clear");
			world.getServer().runCommand("gamemode spectator @a");
			world.getServer().runCommand("tp @a ~ ~12 ~ 40 25");
			context.waitTicks(60);
			world.getConnection().waitForChunksRender(false, 2400);
			if (FabricLoader.getInstance().isModLoaded("distanthorizons")) {
				// Distant Horizons builds its far terrain around the player in the background.
				context.waitTicks(1200);
			}

			fly(context, world, "far", "execute as @a at @s run tp @s ~1500 ~ ~700 -60 20", 3);
			fly(context, world, "medium", "execute as @a at @s run tp @s ~-180 ~ ~90 120 15", 2);
			fly(context, world, "near", "execute as @a at @s run tp @s ~35 ~ ~-20 0 10", 2);
			nether(context, world);
			plainTeleports(context, world);
			settingsScreen(context);
			LOG.info("GTA Teleport client test done");
		}
	}

	/**
	 * The Nether: from the top of the bedrock roof down inside, inside to inside, and back up onto the roof. Over the
	 * roof the camera has to rise; inside it has to stay under the roof.
	 */
	private static void nether(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0.5 129 0.5 0 20");
		context.waitFor(mc -> mc.level != null && mc.level.dimension() == Level.NETHER, 600);
		context.waitTicks(80);
		world.getConnection().waitForChunksRender(false, 2400);
		fly(context, world, "nether-roof-down", insideNether(world, 400, -250, 60.0F), 2);
		fly(context, world, "nether-inside", insideNether(world, -200, -150, 120.0F), 2);
		fly(context, world, "nether-up-roof", "execute in minecraft:the_nether run tp @a 150.5 129 100.5 0 20", 2);
	}

	/** A teleport to an open spot inside the Nether, under the roof. */
	private static String insideNether(TestSingleplayerContext world, int x, int z, float yaw) {
		return world.getServer().computeOnServer(server -> {
			ServerLevel nether = server.getLevel(Level.NETHER);
			nether.getChunk(x >> 4, z >> 4);
			int y = 70;
			for (int candidate = 110; candidate > 32; candidate--) {
				BlockPos pos = new BlockPos(x, candidate, z);
				if (nether.getBlockState(pos).isAir() && nether.getBlockState(pos.above()).isAir() && !nether.getBlockState(pos.below()).isAir()) {
					y = candidate;
					break;
				}
			}
			return String.format(Locale.ROOT, "execute in minecraft:the_nether run tp @a %d.5 %d %d.5 %.1f 20", x, y, z, yaw);
		});
	}

	/** Holding the plain teleport key (Left Alt) skips the switch, pressing it mid-flight cuts the flight short. */
	private static void plainTeleports(ClientGameTestContext context, TestSingleplayerContext world) {
		context.waitTicks(20);
		context.getInput().holdKey(InputConstants.KEY_LALT);
		world.getServer().runCommand("execute as @a at @s run tp @s ~300 ~ ~ 0 10");
		context.waitTicks(10);
		boolean flew = context.computeOnClient(mc -> SwitchCamera.isActive());
		context.getInput().releaseKey(InputConstants.KEY_LALT);
		if (flew) {
			throw new AssertionError("a teleport with Left Alt held must not play the switch");
		}
		context.takeScreenshot("plain-teleport");

		world.getServer().runCommand("execute as @a at @s run tp @s ~-300 ~ ~ 0 10");
		context.waitFor(mc -> SwitchCamera.isActive(), 100);
		context.waitTicks(8);
		context.getInput().holdKey(InputConstants.KEY_LALT);
		context.waitTicks(2);
		context.getInput().releaseKey(InputConstants.KEY_LALT);
		if (context.computeOnClient(mc -> SwitchCamera.isActive() || ChunkHold.pending() > 0)) {
			throw new AssertionError("Left Alt during the switch must cut straight to the player");
		}
		context.takeScreenshot("skipped-switch");
		LOG.info("plain teleport and skip work");
	}

	/** The settings screen, in Russian. */
	private static void settingsScreen(ClientGameTestContext context) {
		AtomicReference<CompletableFuture<Void>> reload = new AtomicReference<>();
		context.runOnClient(mc -> {
			mc.options.languageCode = "ru_ru";
			mc.getLanguageManager().setSelected("ru_ru");
			reload.set(mc.reloadResourcePacks());
		});
		context.waitFor(mc -> reload.get().isDone() && mc.gui.overlay() == null, 1200);
		context.waitTicks(20);
		context.runOnClient(mc -> mc.options.guiScale().set(2));
		context.setScreen(() -> new SwitchConfigScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("settings");
		context.setScreen(() -> null);
	}

	private static void fly(ClientGameTestContext context, TestSingleplayerContext world, String name, String command, int every) {
		context.waitTicks(20);
		context.takeScreenshot(name + "-00-before");
		world.getServer().runCommand(command);
		context.waitFor(mc -> SwitchCamera.isActive(), 100);
		long start = System.nanoTime();
		for (int i = 1; i <= 120 && SwitchCamera.isActive(); i++) {
			context.waitTicks(every);
			String state = context.computeOnClient(mc -> SwitchCamera.describe());
			LOG.info("{} {} +{}ms: {}", name, i, (System.nanoTime() - start) / 1_000_000, state);
			context.takeScreenshot(String.format("%s-%02d", name, i));
		}
		context.waitFor(mc -> !SwitchCamera.isActive() && ChunkHold.pending() == 0, 400);
		context.waitTicks(10);
		context.takeScreenshot(name + "-99-after");
	}
}
