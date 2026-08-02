package net.sv_abd.wasmpacks.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.sv_abd.wasmpacks.WasmPacks;
import net.sv_abd.wasmpacks.loader.SimpleBlockDefinition;
import net.sv_abd.wasmpacks.loader.SimpleItemDefinition;
import net.sv_abd.wasmpacks.registry.SimpleRegistryApplier;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Configuration-phase payload sent server -> client, carrying the exact
 * ordered simple block/item definitions the server registered (see
 * {@link SimpleRegistryApplier}). Sent via {@link net.sv_abd.wasmpacks.network.WasmPacksSyncTask}
 * during {@code RegisterConfigurationTasksEvent}, i.e. after login but before
 * the client enters the play state — the same lifecycle stage NeoForge's own
 * registry-sync machinery uses, for the same reason: the client must finish
 * registering these blocks/items (and their block-state network ids) before
 * it can safely receive any packet that might reference them.
 * <p>
 * Order is preserved end-to-end as a {@code List}, never rebuilt from a
 * {@code Map} on either side — see {@link SimpleRegistryApplier} class doc for
 * why order matters here.
 */
public record WasmPacksSyncPayload(List<BlockEntry> blocks, List<ItemEntry> items) implements CustomPacketPayload {

    public record BlockEntry(Identifier id, SimpleBlockDefinition definition) {}
    public record ItemEntry(Identifier id, SimpleItemDefinition definition) {}

    public static final Type<@NotNull WasmPacksSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(WasmPacks.MOD_ID, "sync"));

    public static final StreamCodec<@NotNull FriendlyByteBuf, @NotNull WasmPacksSyncPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public WasmPacksSyncPayload decode(FriendlyByteBuf buf) {
                    int blockCount = buf.readVarInt();
                    List<BlockEntry> blocks = new ArrayList<>(blockCount);
                    for (int i = 0; i < blockCount; i++) {
                        Identifier id = Identifier.STREAM_CODEC.decode(buf);
                        SimpleBlockDefinition def = SimpleDefinitionCodecs.BLOCK.decode(buf);
                        blocks.add(new BlockEntry(id, def));
                    }

                    int itemCount = buf.readVarInt();
                    List<ItemEntry> items = new ArrayList<>(itemCount);
                    for (int i = 0; i < itemCount; i++) {
                        Identifier id = Identifier.STREAM_CODEC.decode(buf);
                        SimpleItemDefinition def = SimpleDefinitionCodecs.ITEM.decode(buf);
                        items.add(new ItemEntry(id, def));
                    }

                    return new WasmPacksSyncPayload(blocks, items);
                }

                @Override
                public void encode(FriendlyByteBuf buf, WasmPacksSyncPayload payload) {
                    buf.writeVarInt(payload.blocks().size());
                    for (BlockEntry entry : payload.blocks()) {
                        Identifier.STREAM_CODEC.encode(buf, entry.id());
                        SimpleDefinitionCodecs.BLOCK.encode(buf, entry.definition());
                    }

                    buf.writeVarInt(payload.items().size());
                    for (ItemEntry entry : payload.items()) {
                        Identifier.STREAM_CODEC.encode(buf, entry.id());
                        SimpleDefinitionCodecs.ITEM.encode(buf, entry.definition());
                    }
                }
            };

    @Override
    public @NotNull Type<? extends @NotNull CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Builds a payload from whatever {@link SimpleRegistryApplier} last
     * actually registered on THIS process (i.e. the server), rather than
     * re-reading the loaders and re-sorting separately — that would risk the
     * payload silently drifting from what's really live in the registries if
     * the two code paths ever diverge.
     */
    public static WasmPacksSyncPayload buildFromCurrentServerState() {
        List<Map.Entry<Identifier, SimpleBlockDefinition>> sortedBlocks = SimpleRegistryApplier.getSortedBlocks();
        List<Map.Entry<Identifier, SimpleItemDefinition>> sortedItems = SimpleRegistryApplier.getSortedItems();

        List<BlockEntry> blocks = new ArrayList<>();
        if (sortedBlocks != null) {
            for (Map.Entry<Identifier, SimpleBlockDefinition> e : sortedBlocks) {
                blocks.add(new BlockEntry(e.getKey(), e.getValue()));
            }
        }

        List<ItemEntry> items = new ArrayList<>();
        if (sortedItems != null) {
            for (Map.Entry<Identifier, SimpleItemDefinition> e : sortedItems) {
                items.add(new ItemEntry(e.getKey(), e.getValue()));
            }
        }

        return new WasmPacksSyncPayload(blocks, items);
    }

    /**
     * Converts this payload back into the {@code List<Map.Entry<...>>} shape
     * {@link SimpleRegistryApplier#applyOrdered} expects, preserving order.
     * Used by the client-side handler.
     */
    public List<Map.Entry<Identifier, SimpleBlockDefinition>> toBlockEntryList() {
        List<Map.Entry<Identifier, SimpleBlockDefinition>> result = new ArrayList<>(blocks.size());
        for (BlockEntry e : blocks) {
            result.add(Map.entry(e.id(), e.definition()));
        }
        return result;
    }

    /** See {@link #toBlockEntryList()}. */
    public List<Map.Entry<Identifier, SimpleItemDefinition>> toItemEntryList() {
        List<Map.Entry<Identifier, SimpleItemDefinition>> result = new ArrayList<>(items.size());
        for (ItemEntry e : items) {
            result.add(Map.entry(e.id(), e.definition()));
        }
        return result;
    }
}