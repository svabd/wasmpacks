package net.sv_abd.wasmpacks.mixin;

import net.minecraft.core.MappedRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code MappedRegistry}'s private {@code frozen} field.
 *
 * BuiltInRegistries.BLOCK and .ITEM (both MappedRegistry instances) are frozen
 * during mod loading, before any world/data pack is known. To let data packs
 * register additional simple blocks/items at world load, we need to briefly
 * flip this flag off, register, then flip it back on immediately.
 *
 * This is the same technique several other mods (e.g. Create's own
 * MappedRegistryAccessor, seen alongside NeoForge's own internal accessor of
 * the same shape) already ship for the same reason — NeoForge itself uses an
 * equivalent unfreeze/refreeze cycle internally to run RegisterEvent. We are
 * not doing anything NeoForge's own registry lifecycle doesn't already do;
 * we're just doing it a second time, later, in a narrower window.
 *
 * RISK NOTE: the field name "frozen" is based on this shape being stable
 * across MC versions going back to at least 1.18. It has not been verified
 * against the actual 26.2 MappedRegistry source — if the field was renamed,
 * this mixin will fail to apply at startup with a clear
 * "No candidates were found" error rather than silently doing nothing, so a
 * failure here is loud and safe, not silent.
 */
@Mixin(MappedRegistry.class)
public interface MappedRegistryAccessor {

    @Accessor("frozen")
    boolean wasmpacks$isFrozen();

    @Accessor("frozen")
    void wasmpacks$setFrozen(boolean frozen);
}
