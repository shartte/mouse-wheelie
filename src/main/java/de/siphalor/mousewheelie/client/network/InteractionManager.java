package de.siphalor.mousewheelie.client.network;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.container.SlotActionType;
import net.minecraft.network.Packet;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
public class InteractionManager {
	public static Queue<InteractionEvent> interactionEventQueue = new ConcurrentLinkedQueue<>();

	public static final Waiter DUMMY_WAITER = (TriggerType triggerType) -> true;

	private static Waiter waiter = null;

	public static void push(InteractionEvent interactionEvent) {
		interactionEventQueue.add(interactionEvent);
		if (waiter == null)
			triggerSend(TriggerType.INITIAL);
	}

	public static void pushClickEvent(int containerSyncId, int slotId, int buttonId, SlotActionType slotAction) {
		ClickEvent clickEvent = new ClickEvent(containerSyncId, slotId, buttonId, slotAction);
		push(clickEvent);
	}

	public static void triggerSend(TriggerType triggerType) {
		if (waiter == null || waiter.trigger(triggerType)) {
			do {
				if (interactionEventQueue.isEmpty()) {
					waiter = null;
					break;
				}
			} while ((waiter = interactionEventQueue.remove().send()).trigger(TriggerType.INITIAL));
		}
	}

	public static void setWaiter(Waiter waiter) {
		InteractionManager.waiter = waiter;
	}

	public static void clear() {
		interactionEventQueue.clear();
		waiter = null;
	}

	@FunctionalInterface
	public interface Waiter {
		boolean trigger(TriggerType triggerType);
	}

	public static class GuiConfirmWaiter implements Waiter {
		int triggers;

		public GuiConfirmWaiter(int triggers) {
			this.triggers = triggers;
		}

		@Override
		public boolean trigger(TriggerType triggerType) {
			return triggerType == TriggerType.GUI_CONFIRM && --triggers == 0;
		}
	}

	public enum TriggerType {
		INITIAL, CONTAINER_SLOT_UPDATE, GUI_CONFIRM, HELD_ITEM_CHANGE
	}

	@FunctionalInterface
	public interface InteractionEvent {
		/**
		 * Sends the interaction to the server
		 *
		 * @return the number of inventory packets to wait for
		 */
		Waiter send();
	}

	public static class ClickEvent implements InteractionEvent {
		private final Waiter waiter;
		private final int containerSyncId;
		private final int slotId;
		private final int buttonId;
		private final SlotActionType slotAction;

		public ClickEvent(int containerSyncId, int slotId, int buttonId, SlotActionType slotAction) {
			this(containerSyncId, slotId, buttonId, slotAction, 1);
		}

		public ClickEvent(int containerSyncId, int slotId, int buttonId, SlotActionType slotAction, int awaitedTriggers) {
			this(containerSyncId, slotId, buttonId, slotAction, new GuiConfirmWaiter(awaitedTriggers));
		}

		public ClickEvent(int containerSyncId, int slotId, int buttonId, SlotActionType slotAction, Waiter waiter) {
			this.containerSyncId = containerSyncId;
			this.slotId = slotId;
			this.buttonId = buttonId;
			this.slotAction = slotAction;
			this.waiter = waiter;
		}

		@Override
		public Waiter send() {
			MinecraftClient.getInstance().interactionManager.clickSlot(containerSyncId, slotId, buttonId, slotAction, MinecraftClient.getInstance().player);
			return waiter;
		}
	}

	public static class CallbackEvent implements InteractionEvent {
		private final Supplier<Waiter> callback;

		public CallbackEvent(Supplier<Waiter> callback) {
			this.callback = callback;
		}

		@Override
		public Waiter send() {
			return callback.get();
		}
	}

	public static class PacketEvent implements InteractionEvent {
		private final Packet<?> packet;
		private final Waiter waiter;

		public PacketEvent(Packet<?> packet) {
			this(packet, DUMMY_WAITER);
		}

		public PacketEvent(Packet<?> packet, int triggers) {
			this(packet, new GuiConfirmWaiter(triggers));
		}

		public PacketEvent(Packet<?> packet, Waiter waiter) {
			this.packet = packet;
			this.waiter = waiter;
		}

		@Override
		public Waiter send() {
			MinecraftClient.getInstance().getNetworkHandler().sendPacket(packet);
			return waiter;
		}
	}
}
