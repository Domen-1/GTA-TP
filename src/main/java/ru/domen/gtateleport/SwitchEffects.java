package ru.domen.gtateleport;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * The switch look: colour grading, the flash at every jump, motion blur and clouds over whatever the world doesn't
 * draw. It is a post effect (assets/gta_teleport/post_effect/switch.json). Post chain uniforms are fixed when the
 * chain loads, so the values of the current frame reach the shaders as the pixels of a tiny texture: 0 grading, flash,
 * unused, vignette; 1 radial blur, directional blur, blur direction; 2 character colour and cloud amount; 3 cloud
 * offset.
 */
public final class SwitchEffects {
	public static final Identifier POST_EFFECT = Identifier.fromNamespaceAndPath(GtaTeleportClient.MOD_ID, "switch");
	/** The post chain asks for "gta_teleport:switch_params", which it looks up as this texture. */
	private static final Identifier PARAMS = Identifier.fromNamespaceAndPath(GtaTeleportClient.MOD_ID, "textures/effect/switch_params.png");
	/** "gta_teleport:far_depth": the Distant Horizons depth, or a single empty pixel without it. */
	private static final Identifier FAR_DEPTH = Identifier.fromNamespaceAndPath(GtaTeleportClient.MOD_ID, "textures/effect/far_depth.png");
	private static final BorrowedTexture FAR_DEPTH_TEXTURE = new BorrowedTexture();
	private static @Nullable DynamicTexture params;
	private static @Nullable DynamicTexture noDepth;

	private SwitchEffects() {}

	static void init(Minecraft mc) {
		params = new DynamicTexture(() -> "GTA Teleport switch parameters", 4, 1, true);
		mc.getTextureManager().register(PARAMS, params);
		params.upload();
		noDepth = new DynamicTexture(() -> "GTA Teleport empty depth", 1, 1, true);
		noDepth.upload();
		FAR_DEPTH_TEXTURE.borrow(noDepth.getTextureView());
		mc.getTextureManager().register(FAR_DEPTH, FAR_DEPTH_TEXTURE);
	}

	/**
	 * Whether clouds may fill the empty parts of the picture. Not with an Iris shader pack (Minecraft's depth says
	 * nothing there), nor with a Distant Horizons whose depth can't be read (its terrain would be covered).
	 */
	static boolean cloudsAllowed() {
		return switch (SwitchConfig.get().clouds) {
			case "off" -> false;
			case "on" -> true;
			default -> !RenderCompat.shaderPackInUse() && !RenderCompat.distantHorizonsBlind();
		};
	}

	/** Writes this frame's values; false when nothing should be drawn. */
	public static boolean update() {
		SwitchCamera.Flight flight = SwitchCamera.current();
		if (params == null || noDepth == null || flight == null || !SwitchConfig.get().screenEffects) {
			return false;
		}
		GpuTextureView farDepth = RenderCompat.distantHorizonsDepth();
		FAR_DEPTH_TEXTURE.borrow(farDepth != null ? farDepth : noDepth.getTextureView());

		NativeImage pixels = params.getPixels();
		pixels.setPixel(0, 0, pack(flight.grade, flight.flash, 0.0, flight.grade * 0.6));
		pixels.setPixel(1, 0, pack(flight.radial, flight.directional, flight.directionX * 0.5 + 0.5, flight.directionY * 0.5 + 0.5));
		pixels.setPixel(2, 0, channel(flight.voidFill) << 24 | flight.tint.rgb);
		// Smooth scrolling needs more than 8 bits: x in red and green, y in blue and alpha.
		int cloudX = (int) (flight.cloudX * 65536.0) & 0xFFFF;
		int cloudY = (int) (flight.cloudY * 65536.0) & 0xFFFF;
		pixels.setPixel(3, 0, (cloudY & 0xFF) << 24 | (cloudX >> 8) << 16 | (cloudX & 0xFF) << 8 | cloudY >> 8);
		params.upload();
		return true;
	}

	/** Four 0..1 values as the R, G, B and A of one pixel. */
	private static int pack(double r, double g, double b, double a) {
		return channel(a) << 24 | channel(r) << 16 | channel(g) << 8 | channel(b);
	}

	private static int channel(double value) {
		return Mth.clamp((int) Math.round(value * 255.0), 0, 255);
	}

	/** A texture owned by someone else (Distant Horizons): it is only pointed at, never closed. */
	private static final class BorrowedTexture extends AbstractTexture {
		void borrow(GpuTextureView view) {
			this.textureView = view;
			this.texture = view.texture();
		}

		@Override
		public void close() {
			this.textureView = null;
			this.texture = null;
		}
	}
}
