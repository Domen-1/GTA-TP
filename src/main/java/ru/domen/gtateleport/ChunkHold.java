package ru.domen.gtateleport;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import org.jspecify.annotations.Nullable;

/**
 * The server moves the player at once, so it starts unloading the old area and streaming the new one while the camera
 * is still climbing over the old one. The packets that would change the old area (chunks, light, blocks, entity
 * removals) wait here in arrival order and are replayed once the camera is high enough not to see it go, a few per
 * frame so the replay doesn't stall the flight.
 */
public final class ChunkHold {
	private static final ArrayDeque<Runnable> QUEUE = new ArrayDeque<>();
	/** When each entity was last added while packets were waiting, so a held removal doesn't remove a newer copy. */
	private static final Int2LongOpenHashMap ADDED_AT = new Int2LongOpenHashMap();
	private static boolean holding;
	private static boolean replaying;
	private static long sequence;
	private static @Nullable ClientLevel level;

	private ChunkHold() {}

	public static void start(ClientLevel currentLevel) {
		if (!isBusy()) {
			level = currentLevel;
		}
		holding = true;
	}

	/** Stops holding new packets; the waiting ones are replayed by {@link #drain}. */
	public static void release() {
		holding = false;
	}

	public static boolean isHolding() {
		return holding;
	}

	/** Packets keep queueing up behind the waiting ones until the queue is empty, so their order never changes. */
	public static boolean isBusy() {
		return holding || !QUEUE.isEmpty();
	}

	public static int pending() {
		return QUEUE.size();
	}

	/** True when the packet was queued and the vanilla handler must not run now. */
	public static boolean defer(Runnable handler) {
		if (replaying || !isBusy()) {
			return false;
		}
		sequence++;
		QUEUE.add(handler);
		return true;
	}

	public static boolean deferRemoval(ClientboundRemoveEntitiesPacket packet, Consumer<ClientboundRemoveEntitiesPacket> handler) {
		if (replaying || !isBusy()) {
			return false;
		}
		long queuedAt = ++sequence;
		QUEUE.add(() -> {
			IntList ids = new IntArrayList();
			for (int id : packet.entityIds()) {
				if (ADDED_AT.get(id) < queuedAt) {
					ids.add(id);
				}
			}
			if (!ids.isEmpty()) {
				handler.accept(new ClientboundRemoveEntitiesPacket(ids));
			}
		});
		return true;
	}

	public static void noteAdded(int entityId) {
		if (isBusy() && !replaying) {
			ADDED_AT.put(entityId, ++sequence);
		}
	}

	public static void drain(long budgetNanos) {
		if (holding) {
			return;
		}
		if (QUEUE.isEmpty()) {
			ADDED_AT.clear();
			return;
		}
		if (Minecraft.getInstance().level != level) {
			discard();
			return;
		}
		long start = System.nanoTime();
		replaying = true;
		try {
			do {
				Runnable handler = QUEUE.poll();
				try {
					handler.run();
				} catch (RuntimeException e) {
					GtaTeleportClient.LOGGER.error("A held packet failed", e);
				}
			} while (!QUEUE.isEmpty() && System.nanoTime() - start < budgetNanos);
		} finally {
			replaying = false;
		}
		if (QUEUE.isEmpty() && !holding) {
			ADDED_AT.clear();
		}
	}

	/** Replays everything now: the packets after this one must find the world as the server left it. */
	public static void flush() {
		holding = false;
		drain(Long.MAX_VALUE);
	}

	/** The world the packets were for is gone. */
	public static void discard() {
		holding = false;
		QUEUE.clear();
		ADDED_AT.clear();
		level = null;
	}
}
