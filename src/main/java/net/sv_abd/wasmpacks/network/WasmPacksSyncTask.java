package net.sv_abd.wasmpacks.network;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.network.ConfigurationTask;
import net.sv_abd.wasmpacks.WasmPacks;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Configuration-phase task that ships the server's authoritative simple
 * block/item definitions to a connecting client, in the exact order the
 * server itself registered them in (see {@link WasmPacksSyncPayload}).
 * <p>
 * Registered per-connection via a {@code RegisterConfigurationTasksEvent}
 * listener in {@code WasmPacks} — this runs after login but BEFORE the
 * client is allowed to enter the play state, so the client is guaranteed to
 * have finished registering these blocks/items (via
 * {@link net.sv_abd.wasmpacks.registry.SimpleRegistryApplier#applyOrdered})
 * before any world/chunk/entity data that could reference them arrives. The
 * client side of this handshake — decoding the payload, registering, and
 * signaling completion — lives in {@link WasmPacksSyncPayloadHandler}.
 * <p>
 * {@code ConfigurationTask} is vanilla's own interface
 * ({@code net.minecraft.server.network.ConfigurationTask}) — NeoForge hooks
 * mod-added tasks directly into vanilla's configuration-task queue rather
 * than defining a parallel type, so this implements vanilla's interface
 * directly instead of a NeoForge-specific one.
 * <p>
 * CONFIRMED (from decompiled 26.2 source): {@code start} takes
 * {@code Consumer<Packet<?>>}, not {@code Consumer<CustomPacketPayload>} —
 * it's shared with the play-phase {@code ServerCommonPacketListener}, which
 * only knows about raw packets, not payload objects. The payload is wrapped
 * in {@link ClientboundCustomPayloadPacket} before being handed to the
 * sender, same as vanilla/NeoForge do for any custom payload.
 */
public record WasmPacksSyncTask() implements ConfigurationTask {

    public static final Type TYPE = new Type(WasmPacks.MOD_ID + ":sync");

    @Override
    public void start(Consumer<Packet<?>> sender) {
        WasmPacksSyncPayload payload = WasmPacksSyncPayload.buildFromCurrentServerState();
        WasmPacks.LOGGER.debug(
                "[WasmPacks] Sending sync payload to connecting client: {} block(s), {} item(s)",
                payload.blocks().size(), payload.items().size());
        // start() takes a raw Packet<?> sender (shared with the play-phase
        // ServerCommonPacketListener), not a CustomPacketPayload directly — wrap
        // it the same way vanilla/NeoForge wrap any custom payload into an
        // actual packet.
        sender.accept(new ClientboundCustomPayloadPacket(payload));
    }

    @Override
    public @NotNull Type type() {
        return TYPE;
    }
}