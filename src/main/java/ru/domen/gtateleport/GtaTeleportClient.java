package ru.domen.gtateleport;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GtaTeleportClient implements ClientModInitializer {
	public static final String MOD_ID = "gta_teleport";
	public static final Logger LOGGER = LoggerFactory.getLogger("GTA Teleport");

	@Override
	public void onInitializeClient() {
		SwitchConfig.load();
		SwitchKeys.init();
		ClientLifecycleEvents.CLIENT_STARTED.register(SwitchEffects::init);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(command()));
	}

	/**
	 * /gtatp opens the settings, /gtatp status shows them, /gtatp on|off toggles the mod, /gtatp speed|height|clouds
	 * change those settings, /gtatp reload rereads the config file.
	 */
	private static LiteralArgumentBuilder<FabricClientCommandSource> command() {
		return ClientCommands.literal("gtatp")
				.executes(context -> {
					// After the chat that ran the command has closed.
					Minecraft mc = context.getSource().getClient();
					mc.execute(() -> mc.gui.setScreen(new SwitchConfigScreen(null)));
					return Command.SINGLE_SUCCESS;
				})
				.then(ClientCommands.literal("status").executes(context -> status(context.getSource())))
				.then(ClientCommands.literal("on").executes(context -> toggle(context.getSource(), true)))
				.then(ClientCommands.literal("off").executes(context -> toggle(context.getSource(), false)))
				.then(ClientCommands.literal("speed")
						.then(ClientCommands.argument("speed", DoubleArgumentType.doubleArg(0.25, 4.0)).executes(context -> {
							SwitchConfig.get().speed = DoubleArgumentType.getDouble(context, "speed");
							return saved(context.getSource());
						})))
				.then(ClientCommands.literal("height")
						.then(ClientCommands.literal("auto").executes(context -> {
							SwitchConfig.get().height = 0;
							return saved(context.getSource());
						}))
						.then(ClientCommands.argument("blocks", IntegerArgumentType.integer(20, 2000)).executes(context -> {
							SwitchConfig.get().height = IntegerArgumentType.getInteger(context, "blocks");
							return saved(context.getSource());
						})))
				.then(ClientCommands.literal("clouds").then(clouds("auto")).then(clouds("on")).then(clouds("off")))
				.then(ClientCommands.literal("reload").executes(context -> {
					SwitchConfig.load();
					context.getSource().sendFeedback(Component.translatable("gta_teleport.command.reloaded"));
					return status(context.getSource());
				}));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> clouds(String mode) {
		return ClientCommands.literal(mode).executes(context -> {
			SwitchConfig.get().clouds = mode;
			return saved(context.getSource());
		});
	}

	private static int saved(FabricClientCommandSource source) {
		SwitchConfig.get().save();
		return status(source);
	}

	private static int toggle(FabricClientCommandSource source, boolean enabled) {
		SwitchConfig config = SwitchConfig.get();
		config.enabled = enabled;
		config.save();
		return status(source);
	}

	private static int status(FabricClientCommandSource source) {
		SwitchConfig config = SwitchConfig.get();
		Component height = config.height > 0 ? Component.literal(String.valueOf(config.height)) : Component.translatable("gta_teleport.command.auto");
		source.sendFeedback(Component.translatable(config.enabled ? "gta_teleport.command.on" : "gta_teleport.command.off",
				Math.round(config.minDistance), config.speed, height, config.clouds));
		return Command.SINGLE_SUCCESS;
	}
}
