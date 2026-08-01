package net.sv_abd.wasmpacks.mixin;

import net.minecraft.core.IdMapper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code Block}'s private static {@code BLOCK_STATE_REGISTRY} —
 * the {@link IdMapper} that assigns every {@link BlockState} in the game its
 * compact network id.
 *
 * WHY THIS IS NEEDED: unlike {@link MappedRegistryAccessor} (which lets us add
 * an entry to {@code BuiltInRegistries.BLOCK}), adding a {@code Block} object
 * to that registry does NOT automatically give its possible states a network
 * id. That happens exactly once, very early during {@code Bootstrap.bootStrap()},
 * by iterating every block known to {@code BuiltInRegistries.BLOCK} at that
 * point in time and adding each of its states to this mapper. Our simple
 * blocks are registered much later (world load, via a reload listener), so
 * without this accessor their states are never added here — any packet that
 * tries to encode/decode one of these states (e.g.
 * {@code clientbound/minecraft:block_update}) throws
 * {@code EncoderException}/{@code DecoderException}, since the mapper has no
 * id for it.
 *
 * There is intentionally no "unfreeze" step for this mapper — unlike
 * {@code MappedRegistry}, {@code IdMapper} has no frozen flag; it can be
 * appended to at any time via {@link IdMapper#add}. The risk is purely about
 * ORDERING (see {@link net.sv_abd.wasmpacks.registry.SimpleRegistryApplier}):
 * every process (server and every connected client) must call {@code add} for
 * the same states in the same order, or the same state will resolve to a
 * different int on different machines.
 *
 * RISK NOTE: field name "BLOCK_STATE_REGISTRY" is based on this shape being
 * stable across recent MC versions. Not yet verified against real 26.2
 * sources — if renamed, mixin application fails loudly at startup with
 * "No candidates were found" rather than silently doing nothing.
 */
@Mixin(Block.class)
public interface BlockStateIdMapperAccessor {

    @Accessor("BLOCK_STATE_REGISTRY")
    static IdMapper<@NotNull BlockState> wasmpacks$getBlockStateRegistry() {
        throw new AssertionError("Mixin not applied");
    }
}
