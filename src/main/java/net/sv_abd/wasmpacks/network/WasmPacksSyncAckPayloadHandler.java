package net.sv_abd.wasmpacks.network;

import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.sv_abd.wasmpacks.WasmPacks;

/**
 * Server-side half of the sync handshake — see {@link WasmPacksSyncAckPayload}
 * for why this round-trip exists at all. Runs when the SERVER receives the
 * client's "I've registered everything" ack, and is the only place that
 * actually calls {@code finishCurrentTask} — this is the correct side to call
 * it from, unlike the client-side attempt that threw
 * {@code UnsupportedOperationException}.
 */
public final class WasmPacksSyncAckPayloadHandler {

    private WasmPacksSyncAckPayloadHandler() {}

    public static void handle(WasmPacksSyncAckPayload payload, IPayloadContext context) {
        try {
            context.finishCurrentTask(WasmPacksSyncTask.TYPE);
            WasmPacks.LOGGER.debug("[WasmPacks] Client acked sync payload — configuration task finished");
        } catch (Exception e) {
            WasmPacks.LOGGER.error(
                    "[WasmPacks] Failed to finish sync configuration task after receiving client ack — "
                            + "the connecting client will likely hang. Error: {}",
                    e.getMessage(), e);
        }
    }
}
