package net.sv_abd.wasmpacks.network;

import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.sv_abd.wasmpacks.WasmPacks;
import net.sv_abd.wasmpacks.registry.SimpleRegistryApplier;

/**
 * Receiving side of {@link WasmPacksSyncPayload}. Runs on whatever process
 * received the configuration-phase payload — a real remote client on a
 * dedicated server, OR the loopback "client" of an integrated
 * (singleplayer) server.
 * <p>
 * In the singleplayer case, {@link SimpleRegistryApplier}'s shared
 * {@code APPLIED} guard (see its class doc) means this call will typically
 * no-op, because the integrated server's own reload-triggered
 * {@code SimpleRegistryApplier.apply(...)} call already ran first, in the
 * same JVM/registries. On a real dedicated server + remote client, this is a
 * separate JVM with its own guard, so {@code applyOrdered} actually runs here.
 * <p>
 * This class deliberately contains NO client-only imports (no
 * {@code net.minecraft.client...}) — {@code SimpleRegistryApplier.applyOrdered}
 * only touches {@code BuiltInRegistries}/{@code Block}/{@code Item}, which are
 * common-side classes safe to touch from either physical side. That means
 * this handler can be registered directly without any {@code DistExecutor}
 * indirection.
 * <p>
 * RISK NOTE: the exact shape of {@code IPayloadContext} (in particular,
 * whether task completion is signaled via
 * {@code context.finishCurrentTask(Type)} directly, or requires routing
 * through {@code context.enqueueWork(...)} first to land on the right thread)
 * has not been verified against real 26.2 NeoForge sources. If this doesn't
 * compile, check {@code IPayloadContext}'s current methods — the
 * registration logic itself (the actual fix) is unaffected by however that
 * detail resolves.
 */
public final class WasmPacksSyncPayloadHandler {

    private WasmPacksSyncPayloadHandler() {}

    /**
     * RISK NOTE: {@code context.reply(...)} is my best guess at how
     * {@code IPayloadContext} lets a client-side handler send a payload back
     * to the server — it's the name NeoForge's own docs use for exactly this
     * "client acks something to the server" pattern in other versions, but I
     * have NOT verified it against real 26.2 sources the way the previous
     * fixes in this file were verified (decompiled source / actual compiler
     * errors). Before compiling, it's worth autocompleting on {@code context.}
     * inside this method the same way you did for {@code ConfigurationTask}
     * and {@code RegisterConfigurationTasksEvent} earlier — if it's not
     * {@code reply(...)}, it's most likely something that hands you the
     * underlying {@code Connection} to send a wrapped
     * {@code ServerboundCustomPayloadPacket} through directly (mirroring how
     * {@link WasmPacksSyncTask} had to wrap its payload in
     * {@code ClientboundCustomPayloadPacket} manually).
     */
    public static void handle(WasmPacksSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
                    WasmPacks.LOGGER.debug(
                            "[WasmPacks] Received sync payload: {} block(s), {} item(s) — registering",
                            payload.blocks().size(), payload.items().size());
                    SimpleRegistryApplier.applyOrdered(payload.toBlockEntryList(), payload.toItemEntryList());
                })
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        WasmPacks.LOGGER.error(
                                "[WasmPacks] Failed to register synced simple blocks/items — the client will "
                                        + "likely hang because no ack will be sent. Error: {}",
                                throwable.getMessage(), throwable);
                        return;
                    }
                    try {
                        // Tell the SERVER we're done — the server, not us, is the
                        // one that actually finishes the configuration task. See
                        // WasmPacksSyncAckPayload for why.
                        context.reply(new WasmPacksSyncAckPayload());
                    } catch (Exception e) {
                        WasmPacks.LOGGER.error(
                                "[WasmPacks] Failed to send sync ack to server — client will likely hang on "
                                        + "the loading screen because of this. Error: {}",
                                e.getMessage(), e);
                    }
                });
    }
}