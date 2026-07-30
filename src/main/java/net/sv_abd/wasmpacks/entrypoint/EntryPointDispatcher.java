package net.sv_abd.wasmpacks.entrypoint;

import net.minecraft.resources.Identifier;
import net.sv_abd.wasmpacks.WasmPacks;
import net.sv_abd.wasmpacks.loader.EntryPointDefinition;
import net.sv_abd.wasmpacks.loader.EntryPointLoader;
import net.sv_abd.wasmpacks.loader.LoadedWasmModule;
import net.sv_abd.wasmpacks.loader.WasmCodeLoader;

import java.util.Map;

/**
 * After both {@link WasmCodeLoader} and {@link EntryPointLoader} have finished
 * a reload cycle, this class walks all entry points and:
 * <ol>
 *   <li>Resolves the referenced wasm module.</li>
 *   <li>Looks up the entry point type handler in {@link EntryPointTypeRegistry}.</li>
 *   <li>Calls {@link IEntryPointType#register} to connect the entry point to the game.</li>
 * </ol>
 *
 * Called from the {@code AddServerReloadListenersEvent} listener in
 * {@link WasmPacks} after both loaders are applied.
 */
public final class EntryPointDispatcher {

    private EntryPointDispatcher() {}

    /**
     * Dispatch all loaded entry points to their respective type handlers.
     * Call this after both loaders have finished their {@code apply()} phase.
     */
    public static void dispatch(WasmCodeLoader wasmLoader, EntryPointLoader entryPointLoader) {
        // First, signal all type handlers to clear their previous state
        for (IEntryPointType type : EntryPointTypeRegistry.getAll().values()) {
            type.onReload();
        }

        Map<Identifier, EntryPointDefinition> entryPoints = entryPointLoader.getEntryPoints();
        int registered = 0;
        int skipped = 0;

        for (Map.Entry<Identifier, EntryPointDefinition> entry : entryPoints.entrySet()) {
            Identifier entryPointId = entry.getKey();
            EntryPointDefinition definition = entry.getValue();

            // Resolve wasm module
            LoadedWasmModule module = wasmLoader.getModule(definition.wasmModule());
            if (module == null) {
                WasmPacks.LOGGER.warn("[WasmPacks] Entry point {} references unknown wasm module '{}' - skipping. " +
                        "Make sure the module file exists at data/{}/wasmpacks/wasm_code/{}.wasm",
                        entryPointId,
                        definition.wasmModule(),
                        definition.wasmModule().getNamespace(),
                        definition.wasmModule().getPath());
                skipped++;
                continue;
            }

            // Resolve type handler
            IEntryPointType typeHandler = EntryPointTypeRegistry.get(definition.type());
            if (typeHandler == null) {
                WasmPacks.LOGGER.warn("[WasmPacks] Entry point {} has unknown type '{}' - skipping. " +
                        "Available types: {}",
                        entryPointId,
                        definition.type(),
                        EntryPointTypeRegistry.getAll().keySet());
                skipped++;
                continue;
            }

            // Register with the type handler
            try {
                typeHandler.register(entryPointId, definition, module);
                registered++;
            } catch (Exception e) {
                WasmPacks.LOGGER.error("[WasmPacks] Failed to register entry point {}: {}", entryPointId, e.getMessage(), e);
                skipped++;
            }
        }

        WasmPacks.LOGGER.info("[WasmPacks] Dispatched {} entry point(s) ({} skipped)", registered, skipped);
    }
}
