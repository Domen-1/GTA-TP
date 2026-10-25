package ru.domen.gtateleport;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The GTA V character switch for teleports. The camera turns to look straight down and leaves the player's eyes in a
 * few sharp jumps high up into the sky (each with a flash and a boom), flies over to the destination, waits up there
 * until the new place is loaded and drops back down in the same jumps. The player is at the destination the whole
 * time; only the camera travels.
 */
public final class SwitchCamera {
	private static final double PREP_TIME = 0.18;
	private static final double JUMP_TIME = 0.42;
	private static final double HOLD_TIME = 0.1;
	private static final double LAST_HOLD_TIME = 0.05;
	private static final double LAND_TIME = 0.6;
	private static final double OUTRO_TIME = 0.4;
	private static final double MIN_HOVER = 0.1;
	private static final double MAX_HOVER = 4.0;
	private static final float STRAIGHT_DOWN = 90.0F;

	private enum Phase { PREP, ASCENT, PAN, HOVER, DESCENT, OUTRO }

	public record Pose(Vec3 pos, float yaw, float pitch) {
		public static Pose of(Camera camera) {
			return new Pose(camera.position(), camera.yRot(), camera.xRot());
		}
	}

	private static @Nullable Flight flight;
	/** Read by the section culling, which may run on a render worker. */
	private static volatile boolean airborne;
	private static @Nullable Pose cameraBefore;
	private static @Nullable Vec3 playerBefore;
	private static @Nullable ClientLevel levelBefore;
	private static @Nullable UUID menuTarget;
	private static long menuTargetMillis;
	private static double fixedFrameStep;

	private SwitchCamera() {}

	public static boolean isActive() {
		return flight != null;
	}

	/** While the camera is away from the player, the player can't move or look around. */
	public static boolean locksInput() {
		return flight != null && flight.phase != Phase.OUTRO;
	}

	public static boolean hidesHud() {
		return locksInput() && SwitchConfig.get().hideHud;
	}

	/**
	 * While the camera is up, sections far below it must still be drawn: vanilla and Sodium skip the ones further
	 * below the camera than the render distance.
	 */
	public static boolean liftsHeightLimit() {
		return airborne;
	}

	/** How far the camera is above the ground, 0 while it is with the player. */
	public static double heightAboveGround() {
		Flight running = flight;
		return running != null && running.phase != Phase.OUTRO ? Math.max(running.pose.pos.y - running.referenceGround(), 0.0) : 0.0;
	}

	public static float fovScale() {
		return flight == null ? 1.0F : (float) flight.fovScale;
	}

	public static float windLevel() {
		return flight == null ? 0.0F : (float) flight.wind;
	}

	/** Seconds since the switch started (in switch time, so ×speed), -1 without one. */
	public static double flightTime() {
		return flight == null ? -1.0 : flight.clock;
	}

	/**
	 * Advances the switch by a fixed step every frame instead of by real time (0 goes back to real time). For
	 * recording videos frame by frame in the client game test, where frames take as long as a screenshot does.
	 */
	public static void setFixedFrameStep(double seconds) {
		fixedFrameStep = seconds;
	}

	static @Nullable Flight current() {
		return flight;
	}

	/** For logs and tests: the phase and where the camera is. */
	public static String describe() {
		Flight running = flight;
		if (running == null) {
			return "idle, held packets: " + ChunkHold.pending();
		}
		return String.format(Locale.ROOT, "%s %.2fs, camera %.0f %.0f %.0f, clouds %.2f, flash %.2f, held packets: %d",
				running.phase, running.phaseTime, running.pose.pos.x, running.pose.pos.y, running.pose.pos.z,
				running.voidFill, running.flash, ChunkHold.pending()) + running.trace();
	}

	/** The spectator menu tells which player we fly to, before the server answers with the teleport. */
	public static void noteMenuTarget(UUID target) {
		menuTarget = target;
		menuTargetMillis = Util.getMillis();
	}

	/** Right before a teleport packet moves the player: what the screen shows now is where the flight starts. */
	public static void beforeTeleport() {
		Minecraft mc = Minecraft.getInstance();
		Camera camera = mc.gameRenderer.mainCamera();
		if (mc.player != null && camera.isInitialized()) {
			cameraBefore = Pose.of(camera);
			playerBefore = mc.player.position();
			levelBefore = mc.level;
		} else {
			cameraBefore = null;
		}
	}

	public static void afterTeleport() {
		Pose camera = cameraBefore;
		cameraBefore = null;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		SwitchConfig config = SwitchConfig.get();
		if (camera == null || player == null || mc.level == null || mc.level != levelBefore || !config.enabled) {
			return;
		}
		if (SwitchKeys.instantHeld()) {
			skip();
			return;
		}
		double moved = player.position().distanceTo(playerBefore);
		Flight running = flight;
		if (running != null && running.phase.ordinal() < Phase.DESCENT.ordinal()) {
			if (moved > 0.5) {
				running.retarget(player);
			}
			return;
		}
		if (moved < Math.max(config.minDistance, 1.0) || !eligible(mc, player, config)) {
			return;
		}
		flight = new Flight(mc, player, camera);
		ChunkHold.start(mc.level);
	}

	/** Cuts straight to the player, like a plain teleport. */
	public static void skip() {
		if (flight != null) {
			stop();
			ChunkHold.flush();
		}
	}

	/** A respawn, a new login or a reconfiguration: the flight can't continue in the new world. */
	public static void onWorldChange(boolean replayHeldPackets) {
		stop();
		if (replayHeldPackets) {
			ChunkHold.flush();
		} else {
			ChunkHold.discard();
		}
	}

	/**
	 * Called by the camera every frame right after it followed the player. Returns the pose to show instead, or null
	 * to keep the vanilla one.
	 */
	public static @Nullable Pose frame(Pose vanilla, double seconds) {
		Flight running = flight;
		if (running == null) {
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		if (!running.stillValid(mc)) {
			stop();
			ChunkHold.flush();
			return null;
		}
		Pose pose = running.frame(vanilla, mc.isPaused() ? 0.0 : fixedFrameStep > 0.0 ? fixedFrameStep : seconds);
		if (running.finished) {
			flight = null;
		}
		airborne = flight != null && pose != null;
		return pose;
	}

	/** How long the held packets may be replayed this frame. */
	public static long drainBudgetNanos() {
		Flight running = flight;
		if (running == null) {
			return 8_000_000L;
		}
		return running.phase == Phase.HOVER ? 10_000_000L : 3_000_000L;
	}

	private static void stop() {
		flight = null;
		airborne = false;
		SwitchSounds.stopAll();
	}

	private static boolean eligible(Minecraft mc, LocalPlayer player, SwitchConfig config) {
		return (!config.spectatorOnly || player.isSpectator())
				&& mc.getCameraEntity() == player
				&& !player.isPassenger()
				&& player.tickCount > 40;
	}

	private static Tint chooseTint(Minecraft mc, LocalPlayer self) {
		Tint fixed = Tint.byName(SwitchConfig.get().tint);
		if (fixed != null) {
			return fixed;
		}
		if (menuTarget != null && Util.getMillis() - menuTargetMillis < 5000L) {
			return Tint.of(menuTarget);
		}
		AbstractClientPlayer nearest = null;
		double best = 9.0;
		for (AbstractClientPlayer other : mc.level.players()) {
			double distance = other.distanceToSqr(self.position());
			if (other != self && distance < best) {
				best = distance;
				nearest = other;
			}
		}
		return nearest != null ? Tint.of(nearest.getUUID()) : Tint.MICHAEL;
	}

	static float lerpYaw(float from, float to, double t) {
		return from + Mth.wrapDegrees(to - from) * (float) t;
	}

	static Pose lerp(Pose from, Pose to, double t) {
		return new Pose(from.pos.lerp(to.pos, t), lerpYaw(from.yaw, to.yaw, t), (float) Mth.lerp(t, from.pitch, to.pitch));
	}

	static final class Flight {
		private final Minecraft mc;
		private final ClientLevel level;
		private final LocalPlayer player;
		private final Pose start;
		private final double groundStart;
		private final double renderBlocks;
		private final double top;
		/** Top of the Nether's bedrock roof; Double.MAX_VALUE where there is none. */
		private final double roof;
		private final boolean checkMeshes;
		private final long fadeMillis;
		private final int stages;
		private final double[] heights;
		private final double speed;
		final Tint tint;

		private Vec3 dest;
		private float destYaw;
		private double groundDest;
		private double groundDestSmooth;
		private boolean groundDestKnown;
		private float heading;

		private Phase phase = Phase.PREP;
		private double phaseTime;
		private double clock;
		boolean finished;

		private List<@Nullable Pose> targets = List.of();
		private int move;
		private double moveTime;
		private Pose moveFrom;

		private Pose panFrom;
		private double panT;
		private double panEased;
		private double panDuration;
		private double panPower;
		private double panArc;
		private double releaseAt;
		private boolean released;
		private double readyTime;
		private double lastDt;

		private Pose pose;
		private @Nullable Pose previous;
		private final List<double[]> pulses = new ArrayList<>();
		double grade;
		double flash;
		double voidFill;
		double radial;
		double directional;
		double directionX;
		double directionY = 1.0;
		/** Offset of the cloud layer, in whole cloud patterns (0..1, it repeats). */
		double cloudX;
		double cloudY;
		double fovScale = 1.0;
		double wind;

		Flight(Minecraft mc, LocalPlayer player, Pose start) {
			this.mc = mc;
			this.level = mc.level;
			this.player = player;
			this.start = start;
			this.pose = start;
			this.moveFrom = start;
			this.panFrom = start;
			this.dest = player.getEyePosition();
			this.destYaw = player.getYRot();
			this.renderBlocks = mc.options.getEffectiveRenderDistance() * 16.0;
			// With Distant Horizons the whole map is drawn, without it the loaded chunks show as an island in the clouds.
			int height = SwitchConfig.get().height;
			this.top = height > 0 ? height : RenderCompat.DISTANT_HORIZONS ? 420.0 : Mth.clamp(this.renderBlocks * 1.2, 100.0, 500.0);
			this.roof = this.level.dimensionType().hasCeiling()
					? this.level.getMinY() + this.level.dimensionType().logicalHeight()
					: Double.MAX_VALUE;
			this.groundStart = surface(start.pos, start.pos.y);
			this.groundDest = this.dest.y;
			this.groundDestSmooth = this.dest.y;
			this.fadeMillis = (long) (mc.options.chunkSectionFadeInTime().get() * 1000.0);
			// Renderers that replace the vanilla section storage report nothing as compiled; then only chunks count.
			this.checkMeshes = mc.levelRenderer.isSectionCompiledAndVisible(BlockPos.containing(start.pos.x, this.groundStart - 1.0, start.pos.z), this.fadeMillis)
					|| mc.levelRenderer.isSectionCompiledAndVisible(BlockPos.containing(start.pos), this.fadeMillis);
			this.tint = chooseTint(mc, player);
			this.speed = SwitchConfig.get().speed;

			double distance = start.pos.distanceTo(this.dest);
			this.stages = distance < 60.0 ? 1 : distance < 260.0 ? 2 : 3;
			double low = Math.max(14.0, this.top * 0.12);
			this.heights = switch (this.stages) {
				case 1 -> new double[] {Mth.clamp(distance * 0.6, 14.0, 40.0)};
				case 2 -> new double[] {low, Mth.clamp(distance * 0.9, low * 2.5, this.top)};
				default -> new double[] {low, this.top * 0.4, this.top};
			};
			this.heading = headingFrom(start.pos);

			SwitchSounds.play(SwitchSounds.PREP, 1.0F, 0.9F);
			pulse(0.3);
		}

		boolean stillValid(Minecraft mc) {
			return mc.level == this.level && mc.player == this.player && mc.getCameraEntity() == this.player
					&& (!SwitchConfig.get().spectatorOnly || this.player.isSpectator());
		}

		/** Another teleport while still on the way up or in the sky: fly to the new place instead. */
		void retarget(LocalPlayer player) {
			this.dest = player.getEyePosition();
			this.destYaw = player.getYRot();
			this.groundDestKnown = false;
			this.groundDest = this.dest.y;
			switch (this.phase) {
				case PREP, ASCENT -> {
					this.heading = headingFrom(this.start.pos);
					if (this.phase == Phase.ASCENT) {
						this.targets = ascentTargets();
					}
				}
				case PAN, HOVER -> {
					this.heading = headingFrom(this.pose.pos);
					startPan();
				}
				default -> {
				}
			}
		}

		@Nullable Pose frame(Pose vanilla, double seconds) {
			double dt = seconds * this.speed;
			this.lastDt = dt;
			this.clock += dt;
			this.phaseTime += dt;
			this.dest = vanilla.pos;
			this.destYaw = vanilla.yaw;
			updateGroundDest(dt);

			Pose next = switch (this.phase) {
				case PREP -> prep();
				case ASCENT, DESCENT -> moves(vanilla, dt);
				case PAN -> pan();
				case HOVER -> hover();
				case OUTRO -> outro();
			};
			updateEffects(next, dt);
			this.previous = next;
			if (next != null) {
				this.pose = next;
			}
			return next;
		}

		private Pose prep() {
			if (this.phaseTime >= PREP_TIME) {
				beginMoves(Phase.ASCENT, ascentTargets());
			}
			return this.pose;
		}

		private @Nullable Pose moves(Pose vanilla, double dt) {
			this.moveTime += dt;
			boolean last = this.move == this.targets.size() - 1;
			boolean landing = this.phase == Phase.DESCENT && last;
			Pose to = this.targets.get(this.move);
			if (to == null) {
				to = vanilla;
			}
			double moveDuration = landing ? LAND_TIME : JUMP_TIME;
			double holdDuration = landing ? 0.0 : last ? LAST_HOLD_TIME : HOLD_TIME;
			Pose result;
			if (this.moveTime < moveDuration) {
				double t = this.moveTime / moveDuration;
				result = lerp(this.moveFrom, to, landing ? Ease.land(t) : Ease.jump(t));
			} else {
				// The camera coasts on a little after each jump instead of stopping dead.
				double coast = landing ? 0.0 : 0.035 * Ease.smooth((this.moveTime - moveDuration) / holdDuration);
				result = new Pose(to.pos.add(to.pos.subtract(this.moveFrom.pos).scale(coast)), to.yaw, to.pitch);
			}
			if (this.moveTime >= moveDuration + holdDuration) {
				this.pose = result;
				if (!last) {
					this.move++;
					this.moveTime = 0.0;
					this.moveFrom = result;
					onJump();
				} else if (this.phase == Phase.ASCENT) {
					startPan();
				} else {
					startOutro();
					return null;
				}
			}
			return result;
		}

		private Pose pan() {
			this.panT = Math.min(1.0, this.phaseTime / this.panDuration);
			this.panEased = Ease.inOutPow(this.panT, this.panPower);
			Pose p = lerp(this.panFrom, panTarget(), this.panEased);
			p = new Pose(p.pos.add(0.0, this.panArc * Math.sin(Math.PI * this.panEased), 0.0), p.yaw, p.pitch);
			if (!this.released && this.panT >= this.releaseAt) {
				release();
			}
			if (this.panT >= 1.0) {
				this.phase = Phase.HOVER;
				this.phaseTime = 0.0;
				this.readyTime = 0.0;
			}
			return p;
		}

		private Pose hover() {
			if (!this.released) {
				release();
			}
			Pose above = panTarget();
			double sink = this.top * 0.025 * (1.0 - Math.exp(-this.phaseTime / 1.2));
			Pose p = new Pose(above.pos.subtract(0.0, sink, 0.0), above.yaw, above.pitch);
			// Ready once the ground under the camera is loaded and done fading in, and nothing is left on hold.
			if (destinationReady(p)) {
				this.readyTime += this.lastDt;
			} else {
				this.readyTime = 0.0;
			}
			double settle = this.checkMeshes ? Mth.clamp(this.fadeMillis / 1000.0 * 0.8, 0.1, 0.6) : 0.35;
			if (this.phaseTime >= MIN_HOVER && this.readyTime >= settle || this.phaseTime >= MAX_HOVER) {
				this.pose = p;
				beginMoves(Phase.DESCENT, descentTargets());
			}
			return p;
		}

		private @Nullable Pose outro() {
			if (this.phaseTime >= OUTRO_TIME) {
				this.finished = true;
			}
			return null;
		}

		private void beginMoves(Phase phase, List<@Nullable Pose> targets) {
			this.phase = phase;
			this.phaseTime = 0.0;
			this.targets = targets;
			this.move = 0;
			this.moveTime = 0.0;
			this.moveFrom = this.pose;
			onJump();
		}

		private void onJump() {
			boolean landing = this.phase == Phase.DESCENT && this.move == this.targets.size() - 1;
			float pitch = this.phase == Phase.ASCENT ? 1.0F - 0.07F * this.move : landing ? 1.04F : 0.86F + 0.07F * this.move;
			SwitchSounds.play(SwitchSounds.JUMP, pitch, landing ? 0.8F : 1.0F);
			pulse(this.phase == Phase.ASCENT ? 1.0 : 0.85);
			if (this.phase == Phase.ASCENT && this.move > 0) {
				SwitchSounds.startWind();
			}
		}

		private void startPan() {
			this.phase = Phase.PAN;
			this.phaseTime = 0.0;
			this.panT = 0.0;
			this.panEased = 0.0;
			this.panFrom = this.pose;
			Pose to = panTarget();
			double horizontal = Math.hypot(to.pos.x - this.panFrom.pos.x, to.pos.z - this.panFrom.pos.z);
			this.panDuration = Mth.clamp(0.55 + 0.32 * Math.log1p(horizontal / 150.0), 0.5, 1.7);
			this.panPower = Mth.lerp(Ease.smooth(150.0, 3000.0, horizontal), 3.0, 5.0);
			this.panArc = Mth.clamp((horizontal - 2.0 * this.top) * 0.03, 0.0, this.top * 0.2);
			// Close by, the old chunks that go away are off screen anyway; far away the camera has left them behind.
			this.releaseAt = horizontal < this.renderBlocks * 0.5 ? 0.0 : 0.45;
			SwitchSounds.play(SwitchSounds.PAN, (float) Mth.clamp(1.3 / this.panDuration, 0.75, 1.4), 1.0F);
			SwitchSounds.startWind();
			pulse(0.45);
		}

		private void startOutro() {
			this.phase = Phase.OUTRO;
			this.phaseTime = 0.0;
			SwitchSounds.play(SwitchSounds.LAND, 1.0F, 0.9F);
			pulse(0.6);
		}

		private void release() {
			this.released = true;
			ChunkHold.release();
		}

		/** Straight down over the start, turning towards the destination on the way up. */
		private List<@Nullable Pose> ascentTargets() {
			List<@Nullable Pose> list = new ArrayList<>();
			for (int i = 0; i < this.stages; i++) {
				float yaw = this.stages == 1 ? this.heading : lerpYaw(this.start.yaw, this.heading, i / (this.stages - 1.0));
				double y = altitude(this.groundStart, this.start.pos.y, this.heights[i], i == this.stages - 1);
				list.add(new Pose(new Vec3(this.start.pos.x, y, this.start.pos.z), yaw, STRAIGHT_DOWN));
			}
			return list;
		}

		/** The highest stage over the destination, where the flight across ends. */
		private Pose panTarget() {
			double y = altitude(this.groundDestSmooth, this.dest.y, this.heights[this.stages - 1], true);
			return new Pose(new Vec3(this.dest.x, y, this.dest.z), this.heading, STRAIGHT_DOWN);
		}

		/**
		 * The stages below the top over the destination, turning to the player's heading, then the player's own camera
		 * (null): the last drop swings the camera from straight down into the player's view.
		 */
		private List<@Nullable Pose> descentTargets() {
			List<@Nullable Pose> list = new ArrayList<>();
			for (int i = this.stages - 2; i >= 0; i--) {
				float yaw = lerpYaw(this.heading, this.destYaw, (this.stages - 1.0 - i) / (this.stages - 1.0));
				double y = altitude(this.groundDest, this.dest.y, this.heights[i], false);
				list.add(new Pose(new Vec3(this.dest.x, y, this.dest.z), yaw, STRAIGHT_DOWN));
			}
			list.add(null);
			return list;
		}

		/**
		 * A stage {@code above} blocks over the ground. A player flying high still sees the camera rise. Under the
		 * Nether roof the camera stays below it, unless one end of the teleport is on top of the roof: then the top
		 * stage is over the roof, the flight across goes there and the camera breaks through the bedrock in a jump.
		 */
		private double altitude(double ground, double eyeY, double above, boolean top) {
			double y = Math.max(ground + above, eyeY + above * 0.3);
			if (!underRoof(eyeY)) {
				return y;
			}
			if (top && (!underRoof(this.start.pos.y) || !underRoof(this.dest.y))) {
				return this.roof + above;
			}
			return Math.min(y, Math.max(this.roof - 6.0, eyeY));
		}

		private boolean underRoof(double eyeY) {
			return eyeY < this.roof;
		}

		private float headingFrom(Vec3 from) {
			double dx = this.dest.x - from.x;
			double dz = this.dest.z - from.z;
			if (dx * dx + dz * dz < 4.0) {
				return this.start.yaw;
			}
			return (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
		}

		private double surface(Vec3 pos, double fallback) {
			int x = Mth.floor(pos.x);
			int z = Mth.floor(pos.z);
			if (!this.level.hasChunk(x >> 4, z >> 4)) {
				return fallback;
			}
			// Under the Nether roof the heightmap is the roof: the ground is where the player stands.
			return underRoof(pos.y) ? pos.y - 1.62 : this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		}

		private void updateGroundDest(double dt) {
			int x = Mth.floor(this.dest.x);
			int z = Mth.floor(this.dest.z);
			if (this.level.hasChunk(x >> 4, z >> 4)) {
				this.groundDest = surface(this.dest, this.dest.y);
				this.groundDestKnown = true;
			} else if (!this.groundDestKnown) {
				this.groundDest = this.dest.y;
			}
			this.groundDestSmooth = dt > 0.0 ? Ease.approach(this.groundDestSmooth, this.groundDest, dt, 0.35) : this.groundDestSmooth;
		}

		private double referenceGround() {
			return switch (this.phase) {
				case PREP, ASCENT -> this.groundStart;
				case PAN -> Mth.lerp(this.panEased, this.groundStart, this.groundDestSmooth);
				default -> this.groundDestSmooth;
			};
		}

		/** The destination is loaded and drawn under the camera, and nothing is left in the hold. */
		private boolean destinationReady(Pose camera) {
			if (ChunkHold.pending() > 0 || !this.groundDestKnown) {
				return false;
			}
			// Measured against ground a bit below the lowest known point, so the patch is never smaller than the picture.
			// The longer it takes, the less it takes: the last stragglers at the edges are under the clouds anyway.
			double ground = Math.min(this.groundDest, this.dest.y - 1.6) - 8.0;
			double required = Math.max(0.88 - 0.14 * Math.max(this.phaseTime - 0.5, 0.0), 0.6);
			return visibleLoaded(camera, camera.pos.y - ground, null) >= required;
		}

		/** For {@link #describe()}: the readiness grid while waiting over the destination ('#' ready, 'm' no mesh). */
		private String trace() {
			if (this.phase != Phase.HOVER) {
				return "";
			}
			StringBuilder grid = new StringBuilder(" ");
			double ground = Math.min(this.groundDest, this.dest.y - 1.6) - 8.0;
			visibleLoaded(this.pose, this.pose.pos.y - ground, grid);
			return grid.toString();
		}

		/**
		 * Share of the ground in the picture that is loaded and, when we can tell, already has its mesh: a 5x5 grid
		 * over the patch the camera sees from {@code height} above the ground, lined up with the screen and no wider
		 * than the loaded chunks reach.
		 */
		private double visibleLoaded(Pose camera, double height, @Nullable StringBuilder trace) {
			height = Math.max(height, 8.0);
			double yaw = camera.yaw * Mth.DEG_TO_RAD;
			double forwardX = -Math.sin(yaw);
			double forwardZ = Math.cos(yaw);
			double pitch = Math.max(camera.pitch, 25.0F) * Mth.DEG_TO_RAD;
			double ahead = Math.min(height * Math.cos(pitch) / Math.sin(pitch), this.renderBlocks * 0.5);
			double centerX = camera.pos.x + forwardX * ahead;
			double centerZ = camera.pos.z + forwardZ * ahead;
			double tanHalfFov = Math.tan(this.mc.options.fov().get() * Mth.DEG_TO_RAD * 0.5);
			double aspect = (double) this.mc.getWindow().getWidth() / Math.max(this.mc.getWindow().getHeight(), 1);
			// The very corners compile last (they are barely in view), so the grid stops a bit short of them.
			double halfDepth = Math.min(height * tanHalfFov * 0.85, this.renderBlocks * 0.7);
			double halfWidth = Math.min(halfDepth * aspect, this.renderBlocks * 0.7);

			int loaded = 0;
			for (int i = -2; i <= 2; i++) {
				for (int j = -2; j <= 2; j++) {
					double side = i * 0.5 * halfWidth;
					double along = j * 0.5 * halfDepth;
					int x = Mth.floor(centerX + forwardX * along + forwardZ * side);
					int z = Mth.floor(centerZ + forwardZ * along - forwardX * side);
					if (!this.level.hasChunk(x >> 4, z >> 4)) {
						if (trace != null) {
							trace.append('.');
						}
						continue;
					}
					// The client only gets the motion blocking heightmap; an empty column has nothing to draw.
					int top = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
					if (this.checkMeshes && top > this.level.getMinY()) {
						BlockPos surface = new BlockPos(x, Math.min(top, this.level.getMaxY()), z);
						if (!this.mc.levelRenderer.isSectionCompiledAndVisible(surface, this.fadeMillis)) {
							if (trace != null) {
								trace.append('m');
							}
							continue;
						}
					}
					if (trace != null) {
						trace.append('#');
					}
					loaded++;
				}
				if (trace != null) {
					trace.append('|');
				}
			}
			return loaded / 25.0;
		}

		private void pulse(double amplitude) {
			this.pulses.add(new double[] {this.clock, amplitude});
		}

		private void updateEffects(@Nullable Pose next, double dt) {
			double flash = 0.0;
			for (Iterator<double[]> it = this.pulses.iterator(); it.hasNext(); ) {
				double[] pulse = it.next();
				double age = this.clock - pulse[0];
				if (age > 1.5) {
					it.remove();
				} else {
					flash += pulse[1] * (age < 0.035 ? age / 0.035 : Math.exp(-(age - 0.035) / 0.13));
				}
			}
			this.flash = Math.min(flash, 1.0);

			this.grade = switch (this.phase) {
				case PREP -> Ease.outCubic(this.phaseTime / PREP_TIME);
				case OUTRO -> 1.0 - Ease.smooth(this.phaseTime / OUTRO_TIME);
				default -> 1.0;
			};

			if (dt <= 0.0) {
				return;
			}
			double radialTarget = 0.0;
			double directionalTarget = 0.0;
			double sweepX = 0.0;
			double sweepY = 0.0;
			if (next != null && this.previous != null) {
				Vec3 velocity = next.pos.subtract(this.previous.pos).scale(1.0 / dt);
				double height = Math.max(next.pos.y - referenceGround(), 8.0);
				Quaternionf rotation = new Quaternionf().rotationYXZ((float) Math.PI - next.yaw * Mth.DEG_TO_RAD, -next.pitch * Mth.DEG_TO_RAD, 0.0F);
				Vector3f forward = rotation.transform(new Vector3f(0.0F, 0.0F, -1.0F));
				Vector3f up = rotation.transform(new Vector3f(0.0F, 1.0F, 0.0F));
				Vector3f left = rotation.transform(new Vector3f(-1.0F, 0.0F, 0.0F));
				double along = velocity.x * forward.x + velocity.y * forward.y + velocity.z * forward.z;
				double screenX = -(velocity.x * left.x + velocity.y * left.y + velocity.z * left.z) / height;
				double screenY = (velocity.x * up.x + velocity.y * up.y + velocity.z * up.z) / height;
				// Turning sweeps the picture too.
				screenX += Mth.wrapDegrees(next.yaw - this.previous.yaw) * Mth.DEG_TO_RAD / dt;
				screenY -= (next.pitch - this.previous.pitch) * Mth.DEG_TO_RAD / dt;
				double sweep = Math.hypot(screenX, screenY);
				radialTarget = Mth.clamp(Math.abs(along) / height * 0.075, 0.0, 1.0);
				directionalTarget = Mth.clamp(sweep * 0.04, 0.0, 1.0);
				if (sweep > 1.0E-3) {
					this.directionX = screenX / sweep;
					this.directionY = screenY / sweep;
					// The clouds drift the other way; capped, so at full speed they streak instead of flickering.
					double cloudSpeed = Math.min(sweep * 0.45, 0.8);
					sweepX = this.directionX * cloudSpeed;
					sweepY = this.directionY * cloudSpeed;
				}
			}
			this.cloudX = Mth.frac(this.cloudX + sweepX * dt);
			this.cloudY = Mth.frac(this.cloudY + sweepY * dt);
			this.radial = Ease.approach(this.radial, radialTarget, dt, 0.035);
			this.directional = Ease.approach(this.directional, directionalTarget, dt, 0.035);

			double fov = switch (this.phase) {
				case PREP -> 1.0 - 0.035 * Ease.outCubic(this.phaseTime / PREP_TIME);
				case OUTRO -> 1.0;
				default -> 1.0 + 0.1 * this.radial + 0.05 * this.directional;
			};
			this.fovScale = Mth.clamp(fov, 0.9, 1.15);

			// Looking down, any sky in the picture is world that isn't drawn: the shader puts clouds there.
			boolean flying = this.phase != Phase.PREP && this.phase != Phase.OUTRO;
			double voidTarget = flying && next != null && SwitchEffects.cloudsAllowed() ? Ease.smooth(50.0, 75.0, next.pitch) : 0.0;
			this.voidFill = Ease.approach(this.voidFill, voidTarget, dt, 0.08);

			boolean landing = this.phase == Phase.DESCENT && this.move == this.targets.size() - 1;
			boolean high = this.phase == Phase.PAN || this.phase == Phase.HOVER
					|| this.phase == Phase.ASCENT && this.move > 0 || this.phase == Phase.DESCENT && !landing;
			double windTarget = high ? Mth.clamp(0.3 + 0.9 * Math.max(this.radial, this.directional), 0.0, 1.0) : 0.0;
			this.wind = Ease.approach(this.wind, windTarget, dt, 0.3);
		}
	}
}
