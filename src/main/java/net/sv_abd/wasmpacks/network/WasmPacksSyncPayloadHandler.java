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

    public static void handle(WasmPacksSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            WasmPacks.LOGGER.debug(
                    "[WasmPacks] Received sync payload: {} block(s), {} item(s) — registering",
                    payload.blocks().size(), payload.items().size());
            SimpleRegistryApplier.applyOrdered(payload.toBlockEntryList(), payload.toItemEntryList());
        }).thenRun(() -> context.finishCurrentTask(WasmPacksSyncTask.TYPE));
    }
}
