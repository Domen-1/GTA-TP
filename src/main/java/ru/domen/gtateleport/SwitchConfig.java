package ru.domen.gtateleport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

/**
 * config/gta_teleport.json, also edited in game (/gtatp, Mod Menu). Missing fields keep their defaults, out of range
 * values are pulled back in.
 */
public final class SwitchConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("gta_teleport.json");
	private static SwitchConfig instance = new SwitchConfig();

	/** Turns the whole mod on and off. */
	public boolean enabled = true;
	/** Only spectators get the switch; with false any game mode does. */
	public boolean spectatorOnly = true;
	/** Teleports shorter than this (in blocks) stay instant. */
	public double minDistance = 24.0;
	/** 1 is the default pace, 2 is twice as fast (/gtatp speed). */
	public double speed = 1.0;
	/** How high the camera goes over the ground, in blocks; 0 picks it from the render distance (/gtatp height). */
	public int height = 0;
	/** Clouds over the parts of the world that aren't drawn: auto (off with an Iris shader pack), on or off. */
	public String clouds = "auto";
	/** Volume of all the switch sounds, on top of the master volume. */
	public double volume = 1.0;
	/** The booms of the jumps and the landing, relative to {@link #volume}. */
	public double jumpVolume = 1.0;
	/** The whoosh of the flight across. */
	public double panVolume = 1.0;
	/** The wind while the camera is up. */
	public double windVolume = 1.0;
	/** Colour of the flashes: auto, michael, franklin, trevor or neutral. */
	public String tint = "auto";
	/** Colour grading, flashes, motion blur and the clouds. */
	public boolean screenEffects = true;
	/** Hides the HUD and chat while the camera is in the air. */
	public boolean hideHud = true;

	public static SwitchConfig get() {
		return instance;
	}

	public static void load() {
		SwitchConfig loaded = null;
		if (Files.isRegularFile(FILE)) {
			try {
				loaded = GSON.fromJson(Files.readString(FILE), SwitchConfig.class);
			} catch (IOException | JsonParseException e) {
				GtaTeleportClient.LOGGER.warn("Can't read {}, using the defaults", FILE, e);
			}
		}
		instance = loaded != null ? loaded : new SwitchConfig();
		instance.clamp();
		instance.save();
	}

	public void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(this));
		} catch (IOException e) {
			GtaTeleportClient.LOGGER.warn("Can't write {}", FILE, e);
		}
	}

	private void clamp() {
		minDistance = Mth.clamp(minDistance, 0.0, 100000.0);
		speed = Mth.clamp(speed, 0.25, 4.0);
		volume = Mth.clamp(volume, 0.0, 2.0);
		jumpVolume = Mth.clamp(jumpVolume, 0.0, 2.0);
		panVolume = Mth.clamp(panVolume, 0.0, 2.0);
		windVolume = Mth.clamp(windVolume, 0.0, 2.0);
		tint = tint == null ? "auto" : tint.toLowerCase(Locale.ROOT);
		height = Mth.clamp(height, 0, 2000);
		clouds = clouds == null ? "auto" : clouds.toLowerCase(Locale.ROOT);
	}
}
