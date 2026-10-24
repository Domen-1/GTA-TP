package ru.domen.gtateleport;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The character colours of the GTA V switch: blue Michael, green Franklin, orange Trevor. */
public enum Tint {
	MICHAEL(0x5FB0E4),
	FRANKLIN(0x62CF76),
	TREVOR(0xF39C45),
	NEUTRAL(0xC4CED8);

	private static final Tint[] CHARACTERS = {MICHAEL, FRANKLIN, TREVOR};
	public final int rgb;

	Tint(int rgb) {
		this.rgb = rgb;
	}

	/** Every player always gets the same character. */
	public static Tint of(UUID player) {
		return CHARACTERS[Math.floorMod(player.hashCode(), CHARACTERS.length)];
	}

	public static @Nullable Tint byName(String name) {
		return switch (name) {
			case "michael" -> MICHAEL;
			case "franklin" -> FRANKLIN;
			case "trevor" -> TREVOR;
			case "neutral", "none" -> NEUTRAL;
			default -> null;
		};
	}
}
