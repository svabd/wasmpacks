package net.sv_abd.wasmpacks.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.sv_abd.wasmpacks.WasmPacks;
import org.jetbrains.annotations.NotNull;

/**
 * Serverbound payload sent by the client once it has finished processing
 * {@link WasmPacksSyncPayload} (registered the definitions locally). Carries
 * no data — it's a pure "I'm done" signal.
 * <p>
 * WHY THIS EXISTS: {@code IPayloadContext.finishCurrentTask} is a
 * SERVER-authoritative operation — calling it from the client-side handler
 * throws {@code UnsupportedOperationException("Attempted to complete a
 * configuration task on the client!")}, since the client has no authority
 * over the server's own task-tracking state for a connection. The client can
 * only ask the server to finish the task, by sending something back; the
 * server is the one that actually calls {@code finishCurrentTask} once it
 * receives that ack. See {@link WasmPacksSyncAckPayloadHandler} for the
 * server-side half of this handshake.
 */
public record WasmPacksSyncAckPayload() implements CustomPacketPayload {

    public static final Type<@NotNull WasmPacksSyncAckPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(WasmPacks.MOD_ID, "sync_ack"));

    /** No fields to encode — this payload is just a marker. */
    public static final StreamCodec<@NotNull FriendlyByteBuf, @NotNull WasmPacksSyncAckPayload> CODEC =
            StreamCodec.unit(new WasmPacksSyncAckPayload());

    @Override
    public @NotNull Type<? extends @NotNull CustomPacketPayload> type() {
        return TYPE;
    }
}
