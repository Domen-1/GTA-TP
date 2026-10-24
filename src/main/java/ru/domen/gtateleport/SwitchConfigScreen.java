package ru.domen.gtateleport;

import com.mojang.serialization.Codec;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** The settings, built from vanilla option widgets like the sound settings screen. Saved when it closes. */
public final class SwitchConfigScreen extends OptionsSubScreen {
	public SwitchConfigScreen(@Nullable Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("gta_teleport.config.title"));
	}

	@Override
	protected void addOptions() {
		SwitchConfig config = SwitchConfig.get();
		this.list.addSmall(
				toggle("enabled", config.enabled, value -> config.enabled = value),
				toggle("spectator_only", config.spectatorOnly, value -> config.spectatorOnly = value));
		this.list.addSmall(
				slider("speed", 5, 80, (int) Math.round(config.speed / 0.05), value -> config.speed = value * 0.05,
						value -> Component.literal(String.format("×%.2f", value * 0.05))),
				slider("height", 0, 100, config.height / 10, value -> config.height = value * 10,
						value -> value == 0 ? Component.translatable("gta_teleport.config.auto") : Component.translatable("gta_teleport.config.blocks", value * 10)));
		this.list.addSmall(
				slider("min_distance", 0, 200, (int) Math.round(config.minDistance), value -> config.minDistance = value,
						value -> Component.translatable("gta_teleport.config.blocks", value)),
				choice("clouds", List.of("auto", "on", "off"), config.clouds, value -> config.clouds = value));
		this.list.addSmall(
				choice("tint", List.of("auto", "michael", "franklin", "trevor", "neutral"), config.tint, value -> config.tint = value),
				toggle("screen_effects", config.screenEffects, value -> config.screenEffects = value));
		this.list.addSmall(toggle("hide_hud", config.hideHud, value -> config.hideHud = value));

		this.list.addHeader(Component.translatable("gta_teleport.config.sound"));
		this.list.addBig(volume("volume", config.volume, value -> config.volume = value));
		this.list.addSmall(
				volume("jump_volume", config.jumpVolume, value -> config.jumpVolume = value),
				volume("pan_volume", config.panVolume, value -> config.panVolume = value));
		this.list.addSmall(volume("wind_volume", config.windVolume, value -> config.windVolume = value));
	}

	@Override
	public void removed() {
		SwitchConfig.get().save();
	}

	private static String key(String name) {
		return "gta_teleport.config." + name;
	}

	private static OptionInstance<Boolean> toggle(String name, boolean value, Consumer<Boolean> setter) {
		return OptionInstance.createBoolean(key(name), value, setter::accept);
	}

	private static OptionInstance<Integer> slider(String name, int min, int max, int value, Consumer<Integer> setter, IntFunction<Component> label) {
		return new OptionInstance<>(key(name), OptionInstance.cachedConstantTooltip(Component.translatable(key(name) + ".tooltip")),
				(caption, current) -> Options.genericValueLabel(caption, label.apply(current)),
				new OptionInstance.IntRange(min, max), Math.clamp(value, min, max), setter::accept);
	}

	/** 0..200 %, stored as 0..2. */
	private static OptionInstance<Integer> volume(String name, double value, Consumer<Double> setter) {
		return slider(name, 0, 200, (int) Math.round(value * 100.0), percent -> setter.accept(percent / 100.0),
				percent -> percent == 0 ? Component.translatable("options.off") : Component.literal(percent + "%"));
	}

	private static OptionInstance<String> choice(String name, List<String> values, String value, Consumer<String> setter) {
		return new OptionInstance<>(key(name), OptionInstance.cachedConstantTooltip(Component.translatable(key(name) + ".tooltip")),
				// The cycle button puts the caption in front by itself.
				(caption, current) -> Component.translatable(key(name) + "." + current),
				new OptionInstance.Enum<>(values, Codec.STRING), values.contains(value) ? value : values.getFirst(), setter::accept);
	}
}
