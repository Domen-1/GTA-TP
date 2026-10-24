package ru.domen.gtateleport;

import net.minecraft.util.Mth;

/** Easing curves on 0..1. */
final class Ease {
	private Ease() {}

	/** A camera jump: shoots off at four times the average speed and settles softly. */
	static double jump(double t) {
		double u = 1.0 - clamp01(t);
		return 1.0 - u * u * u * u;
	}

	/** The last drop onto the player: quick start, long gentle landing. */
	static double land(double t) {
		double u = 1.0 - clamp01(t);
		return 1.0 - u * u * u;
	}

	static double outCubic(double t) {
		double u = 1.0 - clamp01(t);
		return 1.0 - u * u * u;
	}

	/** Symmetric ease in and out; a higher power means a slower start and a faster middle. */
	static double inOutPow(double t, double power) {
		t = clamp01(t);
		return t < 0.5 ? 0.5 * Math.pow(2.0 * t, power) : 1.0 - 0.5 * Math.pow(2.0 - 2.0 * t, power);
	}

	static double smooth(double t) {
		t = clamp01(t);
		return t * t * (3.0 - 2.0 * t);
	}

	static double smooth(double edge0, double edge1, double x) {
		return smooth((x - edge0) / (edge1 - edge0));
	}

	static double clamp01(double t) {
		return Mth.clamp(t, 0.0, 1.0);
	}

	/** Moves {@code value} toward {@code target} so that it gets ~63% of the way in {@code tau} seconds. */
	static double approach(double value, double target, double dt, double tau) {
		return value + (target - value) * (1.0 - Math.exp(-dt / tau));
	}
}
