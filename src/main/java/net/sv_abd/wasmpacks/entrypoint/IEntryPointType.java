package net.sv_abd.wasmpacks.entrypoint;

import net.minecraft.resources.Identifier;
import net.sv_abd.wasmpacks.loader.EntryPointDefinition;
import net.sv_abd.wasmpacks.loader.LoadedWasmModule;

/**
 * An entry point type defines HOW a wasm export is connected to the game.
 *
 * To add a new entry point type from another mod or from within this mod:
 * <ol>
 *   <li>Implement this interface.</li>
 *   <li>Register it with {@link EntryPointTypeRegistry#register(String, IEntryPointType)}
 *       before the server starts (e.g. in your mod constructor or a static block).</li>
 *   <li>Reference the type by the registered key in your entry_points JSON.</li>
 * </ol>
 *
 * The built-in type is {@code "mcfunction"} which allows a wasm export to be
 * called as if it were an ordinary .mcfunction file.
 */
public interface IEntryPointType {

    /**
     * Called once after both loaders have finished reloading, for each entry point
     * whose {@code type} matches this handler.
     *
     * Implementations should use this to register the entry point with whatever
     * game system it connects to (e.g. function dispatcher, event bus, etc.).
     *
     * @param id         The logical Identifier of this entry point.
     * @param definition The parsed entry point definition from JSON.
     * @param module     The loaded wasm module referenced by the definition.
     */
    void register(Identifier id, EntryPointDefinition definition, LoadedWasmModule module);

    /**
     * Called when a reload begins, before new data is applied.
     * Use this to un-register anything registered during the previous {@link #register} call.
     */
    void onReload();
}
