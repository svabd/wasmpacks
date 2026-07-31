package net.sv_abd.wasmpacks.loader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.sv_abd.wasmpacks.WasmPacks;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans all datapacks for files matching:
 *   data/<namespace>/wasmpacks/entry_points/<path>.json
 *
 * Each JSON file is parsed into an {@link EntryPointDefinition}. The map is
 * keyed by the logical entry point id derived from the file path, e.g.:
 *   data/mymod/wasmpacks/entry_points/on_tick.json
 *     -> Identifier("mymod", "on_tick")
 *
 * Entry points reference a wasm module by Identifier and name the
 * export function to invoke. The {@code type} field routes them to the correct
 * {@link net.sv_abd.wasmpacks.entrypoint.IEntryPointType} handler.
 */
public class EntryPointLoader extends SimplePreparableReloadListener<Map<Identifier, EntryPointDefinition>> {

    public static final Identifier ID = Identifier.fromNamespaceAndPath(WasmPacks.MOD_ID, "entry_point_loader");

    private static final String DIRECTORY = "wasmpacks/entry_points";
    private static final String EXTENSION = ".json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Live registry updated after each reload. */
    private Map<Identifier, EntryPointDefinition> entryPoints = Collections.emptyMap();

    // -------------------------------------------------------------------------
    // SimplePreparableReloadListener contract
    // -------------------------------------------------------------------------

    @Override
    protected Map<Identifier, EntryPointDefinition> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<Identifier, EntryPointDefinition> result = new HashMap<>();

        Map<Identifier, Resource> resources = manager.listResources(
                DIRECTORY,
                id -> id.getPath().endsWith(EXTENSION)
        );

        for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
            Identifier fileId = entry.getKey();
            Resource resource = entry.getValue();

            String rawPath = fileId.getPath();
            String trimmedPath = rawPath
                    .substring(DIRECTORY.length() + 1)
                    .substring(0, rawPath.length() - DIRECTORY.length() - 1 - EXTENSION.length());
            Identifier entryPointId = Identifier.fromNamespaceAndPath(fileId.getNamespace(), trimmedPath);

            try (Reader reader = resource.openAsReader()) {
                JsonElement jsonElement = GSON.fromJson(reader, JsonElement.class);
                if (!jsonElement.isJsonObject()) {
                    WasmPacks.LOGGER.error("[WasmPacks] Entry point file {} is not a JSON object, skipping", fileId);
                    continue;
                }
                JsonObject json = jsonElement.getAsJsonObject();
                EntryPointDefinition def = parseDefinition(fileId, json);
                if (def != null) {
                    result.put(entryPointId, def);
                    WasmPacks.LOGGER.debug("[WasmPacks] Loaded entry point: {} -> {}#{} (type={}, args={})",
                            entryPointId, def.wasmModule(), def.export(), def.type(), def.args());
                }
            } catch (IOException e) {
                WasmPacks.LOGGER.error("[WasmPacks] Failed to read entry point file {}: {}", fileId, e.getMessage());
            } catch (JsonParseException e) {
                WasmPacks.LOGGER.error("[WasmPacks] Failed to parse JSON in entry point file {}: {}", fileId, e.getMessage());
            }
        }

        WasmPacks.LOGGER.info("[WasmPacks] Prepared {} entry point(s)", result.size());
        return result;
    }

    @Override
    protected void apply(Map<Identifier, EntryPointDefinition> prepared, ResourceManager manager, ProfilerFiller profiler) {
        this.entryPoints = Collections.unmodifiableMap(prepared);
        WasmPacks.LOGGER.info("[WasmPacks] Applied {} entry point(s)", this.entryPoints.size());
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    private static EntryPointDefinition parseDefinition(Identifier fileId, JsonObject json) {
        if (!json.has("wasm_module")) {
            WasmPacks.LOGGER.error("[WasmPacks] Entry point {} is missing required field 'wasm_module'", fileId);
            return null;
        }
        if (!json.has("export")) {
            WasmPacks.LOGGER.error("[WasmPacks] Entry point {} is missing required field 'export'", fileId);
            return null;
        }
        if (!json.has("type")) {
            WasmPacks.LOGGER.error("[WasmPacks] Entry point {} is missing required field 'type'", fileId);
            return null;
        }

        String wasmModuleStr = json.get("wasm_module").getAsString();
        String export = json.get("export").getAsString();
        String type = json.get("type").getAsString();

        Identifier wasmModuleId;
        try {
            wasmModuleId = Identifier.parse(wasmModuleStr);
        } catch (Exception e) {
            WasmPacks.LOGGER.error("[WasmPacks] Entry point {} has invalid wasm_module Identifier '{}': {}",
                    fileId, wasmModuleStr, e.getMessage());
            return null;
        }

        if (export.isBlank()) {
            WasmPacks.LOGGER.error("[WasmPacks] Entry point {} has blank 'export' field", fileId);
            return null;
        }
        if (type.isBlank()) {
            WasmPacks.LOGGER.error("[WasmPacks] Entry point {} has blank 'type' field", fileId);
            return null;
        }

        // --- Optional: parse args array ---
        List<String> args = new ArrayList<>();
        if (json.has("args")) {
            JsonElement argsEl = json.get("args");
            if (!argsEl.isJsonArray()) {
                WasmPacks.LOGGER.error("[WasmPacks] Entry point {} has non-array 'args' field", fileId);
                return null;
            }
            for (JsonElement el : argsEl.getAsJsonArray()) {
                if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
                    WasmPacks.LOGGER.error("[WasmPacks] Entry point {} has non-string element in 'args'", fileId);
                    return null;
                }
                String argName = el.getAsString();
                if (argName.isBlank()) {
                    WasmPacks.LOGGER.error("[WasmPacks] Entry point {} has blank argument name in 'args'", fileId);
                    return null;
                }
                args.add(argName);
            }
        }

        return new EntryPointDefinition(wasmModuleId, export, type, args);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public Map<Identifier, EntryPointDefinition> getEntryPoints() {
        return entryPoints;
    }

    public EntryPointDefinition getEntryPoint(Identifier id) {
        return entryPoints.get(id);
    }
}
