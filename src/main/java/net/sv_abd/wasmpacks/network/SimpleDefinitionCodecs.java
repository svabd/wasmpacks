package net.sv_abd.wasmpacks.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.sv_abd.wasmpacks.loader.SimpleBlockDefinition;
import net.sv_abd.wasmpacks.loader.SimpleItemDefinition;
import org.jetbrains.annotations.NotNull;

/**
 * Hand-written {@link StreamCodec}s for {@link SimpleBlockDefinition} and
 * {@link SimpleItemDefinition}.
 * <p>
 * Typed against plain {@code FriendlyByteBuf}, NOT {@code RegistryFriendlyByteBuf}
 * — none of these fields (Identifier, floats, strings, booleans, an int) need
 * registry access to encode/decode. This matters beyond style:
 * {@code PayloadRegistrar.configurationToClient} requires a
 * {@code StreamCodec<? super FriendlyByteBuf, T>}. {@code RegistryFriendlyByteBuf}
 * is a SUBTYPE of {@code FriendlyByteBuf} (narrower, not broader), so a codec
 * declared for it does NOT satisfy a {@code ? super FriendlyByteBuf} bound —
 * only {@code FriendlyByteBuf} itself (or something broader) does. Since
 * {@code RegistryFriendlyByteBuf} IS-A {@code FriendlyByteBuf}, this codec
 * still works fine at runtime even when actually handed a
 * {@code RegistryFriendlyByteBuf} instance.
 * <p>
 * Written manually (field-by-field read/write) rather than via
 * {@code StreamCodec.composite(...)} because {@code SimpleBlockDefinition} has
 * 8 fields, past every {@code composite} overload's arity seen so far.
 * <p>
 * RISK NOTE: assumes {@code Identifier.STREAM_CODEC} exists and is itself
 * typed loosely enough to satisfy this bound, and that {@code FriendlyByteBuf}
 * exposes the usual primitive read/write methods
 * ({@code readUtf}/{@code writeUtf}, {@code readVarInt}/{@code writeVarInt},
 * {@code readFloat}/{@code writeFloat}, {@code readBoolean}/{@code writeBoolean}).
 * These have been stable for a very long time, but have not been verified
 * against real 26.2 sources.
 */
public final class SimpleDefinitionCodecs {

    private SimpleDefinitionCodecs() {}

    public static final StreamCodec<FriendlyByteBuf, SimpleBlockDefinition> BLOCK =
            new StreamCodec<>() {
                @Override
                public SimpleBlockDefinition decode(FriendlyByteBuf buf) {
                    Identifier texture = Identifier.STREAM_CODEC.decode(buf);
                    float hardness = buf.readFloat();
                    float resistance = buf.readFloat();
                    String sound = buf.readUtf();
                    String mapColor = buf.readUtf();
                    boolean requiresTool = buf.readBoolean();
                    int luminance = buf.readVarInt();
                    boolean blockItem = buf.readBoolean();
                    return new SimpleBlockDefinition(
                            texture, hardness, resistance, sound, mapColor, requiresTool, luminance, blockItem);
                }

                @Override
                public void encode(FriendlyByteBuf buf, SimpleBlockDefinition def) {
                    Identifier.STREAM_CODEC.encode(buf, def.texture());
                    buf.writeFloat(def.hardness());
                    buf.writeFloat(def.resistance());
                    buf.writeUtf(def.sound());
                    buf.writeUtf(def.mapColor());
                    buf.writeBoolean(def.requiresTool());
                    buf.writeVarInt(def.luminance());
                    buf.writeBoolean(def.blockItem());
                }
            };

    public static final StreamCodec<@NotNull FriendlyByteBuf, @NotNull SimpleItemDefinition> ITEM =
            new StreamCodec<>() {
                @Override
                public SimpleItemDefinition decode(FriendlyByteBuf buf) {
                    Identifier texture = Identifier.STREAM_CODEC.decode(buf);
                    int maxStackSize = buf.readVarInt();
                    int maxDurability = buf.readVarInt();
                    String rarity = buf.readUtf();
                    boolean fireResistant = buf.readBoolean();

                    return new SimpleItemDefinition(texture, maxStackSize, maxDurability, rarity, fireResistant);
                }

                @Override
                public void encode(FriendlyByteBuf buf, SimpleItemDefinition def) {
                    Identifier.STREAM_CODEC.encode(buf, def.texture());
                    buf.writeVarInt(def.maxStackSize());
                    buf.writeVarInt(def.maxDurability());
                    buf.writeUtf(def.rarity());
                    buf.writeBoolean(def.fireResistant());
                }
            };
}