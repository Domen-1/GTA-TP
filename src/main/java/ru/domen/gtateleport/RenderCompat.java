package ru.domen.gtateleport;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * Distant Horizons and Iris, through reflection so neither is needed to build or run.
 *
 * Distant Horizons draws its far terrain into its own colour and depth textures and copies only the colour into the
 * picture, so to Minecraft's depth buffer that terrain is still empty sky. Its depth texture comes from its public
 * API ({@code DhApi.Delayed.renderProxy.getDhDepthTextureBlazeWrapper()}).
 */
final class RenderCompat {
	static final boolean DISTANT_HORIZONS = FabricLoader.getInstance().isModLoaded("distanthorizons");
	private static final boolean IRIS = FabricLoader.getInstance().isModLoaded("iris");

	private static @Nullable Field renderProxy;
	private static @Nullable Method depthWrapper;
	private static @Nullable Field resultPayload;
	private static @Nullable Method wrappedObject;
	private static @Nullable Method irisInstance;
	private static @Nullable Method irisShaderPackInUse;
	private static boolean distantHorizonsBroken;
	private static boolean irisBroken;

	private RenderCompat() {}

	/** The Distant Horizons depth texture of the current frame, or null without Distant Horizons. */
	static @Nullable GpuTextureView distantHorizonsDepth() {
		if (!DISTANT_HORIZONS || distantHorizonsBroken) {
			return null;
		}
		try {
			if (renderProxy == null) {
				renderProxy = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed").getField("renderProxy");
				depthWrapper = Class.forName("com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy")
						.getMethod("getDhDepthTextureBlazeWrapper");
				resultPayload = Class.forName("com.seibel.distanthorizons.api.objects.DhApiResult").getField("payload");
				wrappedObject = Class.forName("com.seibel.distanthorizons.api.interfaces.IDhApiUnsafeWrapper")
						.getMethod("getWrappedMcObject");
			}
			Object proxy = renderProxy.get(null);
			if (proxy == null) {
				return null;
			}
			Object wrapper = resultPayload.get(depthWrapper.invoke(proxy));
			if (wrapper == null) {
				return null;
			}
			// texture, view, sampler
			return wrappedObject.invoke(wrapper) instanceof Object[] parts && parts.length > 1 && parts[1] instanceof GpuTextureView view
					&& !view.isClosed() ? view : null;
		} catch (InvocationTargetException e) {
			// Not ready yet (no world, no textures): try again next frame.
			return null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			distantHorizonsBroken = true;
			GtaTeleportClient.LOGGER.warn("Can't read the Distant Horizons depth, clouds stay off with it", e);
			return null;
		}
	}

	/** True when Distant Horizons is there but its depth can't be read: the clouds would cover its terrain. */
	static boolean distantHorizonsBlind() {
		return DISTANT_HORIZONS && distantHorizonsBroken;
	}

	/** An Iris shader pack draws the world into its own buffers; Minecraft's depth can't tell sky from ground then. */
	static boolean shaderPackInUse() {
		if (!IRIS || irisBroken) {
			return false;
		}
		try {
			if (irisInstance == null) {
				Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				irisInstance = api.getMethod("getInstance");
				irisShaderPackInUse = api.getMethod("isShaderPackInUse");
			}
			return (boolean) irisShaderPackInUse.invoke(irisInstance.invoke(null));
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			irisBroken = true;
			GtaTeleportClient.LOGGER.warn("Can't ask Iris about shader packs", e);
			return false;
		}
	}
}
