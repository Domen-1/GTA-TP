package ru.domen.gtateleport;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Controls → GTA Teleport: hold for a plain teleport (also cuts a running switch short), toggle the mod, settings. */
final class SwitchKeys {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(GtaTeleportClient.MOD_ID, "main"));
	private static final KeyMapping INSTANT = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.gta_teleport.instant", InputConstants.Type.KEYBOARD, InputConstants.KEY_LALT, CATEGORY));
	private static final KeyMapping TOGGLE = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.gta_teleport.toggle", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
	private static final KeyMapping SETTINGS = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.gta_teleport.settings", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));

	private SwitchKeys() {}

	static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(SwitchKeys::tick);
	}

	/** Read straight from the keyboard: the teleport may come while the chat with the command is still closing. */
	static boolean instantHeld() {
		InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(INSTANT);
		return key.getType() == InputConstants.Type.KEYBOARD && key.getValue() != InputConstants.UNKNOWN.getValue()
				&& InputConstants.isKeyDown(key.getValue());
	}

	private static void tick(Minecraft mc) {
		while (TOGGLE.consumeClick()) {
			SwitchConfig config = SwitchConfig.get();
			config.enabled = !config.enabled;
			config.save();
			mc.gui.hud.setOverlayMessage(Component.translatable(config.enabled ? "gta_teleport.toggle.on" : "gta_teleport.toggle.off"), false);
		}
		while (SETTINGS.consumeClick()) {
			mc.gui.setScreen(new SwitchConfigScreen(null));
		}
		if (SwitchCamera.locksInput() && instantHeld()) {
			SwitchCamera.skip();
		}
	}
}
