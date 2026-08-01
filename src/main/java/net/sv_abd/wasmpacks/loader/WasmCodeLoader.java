package net.sv_abd.wasmpacks.loader;

import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.sv_abd.wasmpacks.WasmPacks;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Scans all datapacks for files matching:
 *   data/<namespace>/wasmpacks/wasm_code/<path>.wasm
 *
 * Each file is parsed into a Chicory WasmModule (validation + type-checking
 * happens here during the off-thread prepare phase). The resulting map is
 * keyed by an Identifier derived from the file path, e.g.:
 *   data/mypack/wasmpacks/wasm_code/myfunctions.wasm
 *     -> Identifier("mypack", "myfunctions")
 *
 * Modules are NOT instantiated here; instantiation happens per-invocation
 * inside the entry point layer so each call gets its own isolated linear memory.
 */
public class WasmCodeLoader extends SimplePreparableReloadListener<@NotNull Map<Identifier, LoadedWasmModule>> {

    public static final Identifier ID = Identifier.fromNamespaceAndPath(WasmPacks.MOD_ID, "wasm_code_loader");

    /** The directory prefix inside each namespace's data folder. */
    private static final String DIRECTORY = "wasmpacks/wasm_code";
    private static final String EXTENSION = ".wasm";

    /** The live registry — updated on the main thread after each reload. */
    private Map<Identifier, LoadedWasmModule> modules = Collections.emptyMap();

    // -------------------------------------------------------------------------
    // SimplePreparableReloadListener contract
    // -------------------------------------------------------------------------

    /**
     * OFF THREAD: scan and parse all .wasm files. Exceptions here log a warning
     * and skip the offending file rather than crashing the whole reload.
     */
    @Override
    protected Map<Identifier, LoadedWasmModule> prepare(ResourceManager manager, @NotNull ProfilerFiller profiler) {
        Map<Identifier, LoadedWasmModule> result = new HashMap<>();

        Map<Identifier, Resource> resources = manager.listResources(
                DIRECTORY,
                id -> id.getPath().endsWith(EXTENSION)
        );

        for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
            Identifier fileId = entry.getKey();
            Resource resource = entry.getValue();

            // Derive logical key: strip directory prefix and .wasm suffix
            // e.g.  "wasmpacks/wasm_code/mymod/myfuncs.wasm"  ->  "mymod:myfuncs"  (if namespace == mymod)
            // Actually the namespace stays as the file namespace; path becomes the trimmed path.
            String rawPath = fileId.getPath(); // "wasmpacks/wasm_code/<rest>.wasm"
            String trimmedPath = rawPath
                    .substring(DIRECTORY.length() + 1)           // strip "wasmpacks/wasm_code/"
                    .substring(0, rawPath.length() - DIRECTORY.length() - 1 - EXTENSION.length()); // strip ".wasm"
            Identifier moduleId = Identifier.fromNamespaceAndPath(fileId.getNamespace(), trimmedPath);

            try (InputStream stream = resource.open()) {
                byte[] bytes = stream.readAllBytes();
                WasmModule wasmModule = Parser.parse(new java.io.ByteArrayInputStream(bytes));
                result.put(moduleId, new LoadedWasmModule(wasmModule, bytes.length));
                WasmPacks.LOGGER.debug("[WasmPacks] Loaded wasm module: {} ({} bytes)", moduleId, bytes.length);
            } catch (IOException e) {
                WasmPacks.LOGGER.error("[WasmPacks] Failed to read wasm file {}: {}", fileId, e.getMessage());
            } catch (Exception e) {
                WasmPacks.LOGGER.error("[WasmPacks] Failed to parse wasm module {} - is it a valid .wasm binary? Error: {}",
                        fileId, e.getMessage());
            }
        }

        WasmPacks.LOGGER.info("[WasmPacks] Prepared {} wasm module(s)", result.size());
        return result;
    }

    /**
     * MAIN THREAD: swap in the freshly prepared map.
     */
    @Override
    protected void apply(Map<Identifier, LoadedWasmModule> prepared, @NotNull ResourceManager manager, @NotNull ProfilerFiller profiler) {
        this.modules = Collections.unmodifiableMap(prepared);
        WasmPacks.LOGGER.info("[WasmPacks] Applied {} wasm module(s)", this.modules.size());
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns the current loaded modules. Never null, may be empty. */
    public Map<Identifier, LoadedWasmModule> getModules() {
        return modules;
    }

    /** Returns the module with the given id, or null if not found. */
    public LoadedWasmModule getModule(Identifier id) {
        return modules.get(id);
    }
}
