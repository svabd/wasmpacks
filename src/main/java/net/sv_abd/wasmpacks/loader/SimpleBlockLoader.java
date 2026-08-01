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
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Scans all datapacks for files matching:
 *   data/<namespace>/wasmpacks/simple_blocks/<path>.json
 *
 * Each JSON file is parsed into a {@link SimpleBlockDefinition}. The map is
 * keyed by the logical block id derived from the file path, e.g.:
 *   data/mymod/wasmpacks/simple_blocks/pebble.json
 *     -> Identifier("mymod", "pebble")
 *
 * NOTE: unlike wasm_code/entry_points, this loader's results are NOT meant to
 * be applied on every /reload. Block/Item are static registries that freeze
 * during mod loading; adding entries to them requires briefly unfreezing the
 * registry, which is only safe to do once, at world load, before any client
 * is connected. This class only does the parsing/bookkeeping side — the
 * actual registration step (and the "once per world load, not per /reload"
 * enforcement) lives in the registration/unfreeze code that consumes
 * {@link #getDefinitions()}.
 */
public class SimpleBlockLoader extends SimplePreparableReloadListener<@NotNull Map<Identifier, SimpleBlockDefinition>> {

    public static final Identifier ID = Identifier.fromNamespaceAndPath(WasmPacks.MOD_ID, "simple_block_loader");

    private static final String DIRECTORY = "wasmpacks/simple_blocks";
    private static final String EXTENSION = ".json";

    private static final float DEFAULT_HARDNESS = 1.5f;
    private static final boolean DEFAULT_REQUIRES_TOOL = false;
    private static final int DEFAULT_LUMINANCE = 0;
    private static final boolean DEFAULT_BLOCK_ITEM = true;
    private static final String DEFAULT_SOUND = "stone";
    private static final String DEFAULT_MAP_COLOR = "stone";

    /** Known sound/map-color keys. Kept here so bad JSON is caught at parse time. */
    public static final Set<String> KNOWN_SOUNDS = Set.of(
            "stone", "wood", "gravel", "grass", "metal", "glass", "wool", "sand", "snow", "ladder", "anvil", "netherrack"
    );
    public static final Set<String> KNOWN_MAP_COLORS = Set.of(
            "stone", "wood", "grass", "sand", "metal", "water", "ice", "snow", "dirt", "color_black", "terracotta"
    );

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Live registry updated after each reload. */
    private Map<Identifier, SimpleBlockDefinition> definitions = Collections.emptyMap();

    // -------------------------------------------------------------------------
    // SimplePreparableReloadListener contract
    // -------------------------------------------------------------------------

    @Override
    protected Map<Identifier, SimpleBlockDefinition> prepare(ResourceManager manager, @NotNull ProfilerFiller profiler) {
        Map<Identifier, SimpleBlockDefinition> result = new HashMap<>();

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
            Identifier blockId = Identifier.fromNamespaceAndPath(fileId.getNamespace(), trimmedPath);

            try (Reader reader = resource.openAsReader()) {
                JsonElement jsonElement = GSON.fromJson(reader, JsonElement.class);
                if (!jsonElement.isJsonObject()) {
                    WasmPacks.LOGGER.error("[WasmPacks] Simple block file {} is not a JSON object, skipping", fileId);
                    continue;
                }
                JsonObject json = jsonElement.getAsJsonObject();
                SimpleBlockDefinition def = parseDefinition(fileId, json);
                if (def != null) {
                    result.put(blockId, def);
                    WasmPacks.LOGGER.debug("[WasmPacks] Loaded simple block: {} -> {}", blockId, def);
                }
            } catch (IOException e) {
                WasmPacks.LOGGER.error("[WasmPacks] Failed to read simple block file {}: {}", fileId, e.getMessage());
            } catch (JsonParseException e) {
                WasmPacks.LOGGER.error("[WasmPacks] Failed to parse JSON in simple block file {}: {}", fileId, e.getMessage());
            }
        }

        WasmPacks.LOGGER.info("[WasmPacks] Prepared {} simple block definition(s)", result.size());
        return result;
    }

    @Override
    protected void apply(Map<Identifier, SimpleBlockDefinition> prepared, @NotNull ResourceManager manager, @NotNull ProfilerFiller profiler) {
        this.definitions = Collections.unmodifiableMap(prepared);
        WasmPacks.LOGGER.info("[WasmPacks] Applied {} simple block definition(s)", this.definitions.size());
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    private static SimpleBlockDefinition parseDefinition(Identifier fileId, JsonObject json) {
        if (!json.has("texture")) {
            WasmPacks.LOGGER.error("[WasmPacks] Simple block {} is missing required field 'texture'", fileId);
            return null;
        }
        String textureStr = json.get("texture").getAsString();
        Identifier texture;
        try {
            texture = Identifier.parse(textureStr);
        } catch (Exception e) {
            WasmPacks.LOGGER.error("[WasmPacks] Simple block {} has invalid 'texture' Identifier '{}': {}",
                    fileId, textureStr, e.getMessage());
            return null;
        }

        float hardness = DEFAULT_HARDNESS;
        if (json.has("hardness")) {
            hardness = json.get("hardness").getAsFloat();
            if (hardness < 0f) {
                WasmPacks.LOGGER.error("[WasmPacks] Simple block {} has negative 'hardness'", fileId);
                return null;
            }
        }

        float resistance = hardness;
        if (json.has("resistance")) {
            resistance = json.get("resistance").getAsFloat();
            if (resistance < 0f) {
                WasmPacks.LOGGER.error("[WasmPacks] Simple block {} has negative 'resistance'", fileId);
                return null;
            }
        }

        String sound = DEFAULT_SOUND;
        if (json.has("sound")) {
            sound = json.get("sound").getAsString();
            if (!KNOWN_SOUNDS.contains(sound)) {
                WasmPacks.LOGGER.error("[WasmPacks] Simple block {} has unknown 'sound' key '{}'. Known: {}",
                        fileId, sound, KNOWN_SOUNDS);
                return null;
            }
        }

        String mapColor = DEFAULT_MAP_COLOR;
        if (json.has("map_color")) {
            mapColor = json.get("map_color").getAsString();
            if (!KNOWN_MAP_COLORS.contains(mapColor)) {
                WasmPacks.LOGGER.error("[WasmPacks] Simple block {} has unknown 'map_color' key '{}'. Known: {}",
                        fileId, mapColor, KNOWN_MAP_COLORS);
                return null;
            }
        }

        boolean requiresTool = json.has("requires_tool")
                ? json.get("requires_tool").getAsBoolean()
                : DEFAULT_REQUIRES_TOOL;

        int luminance = DEFAULT_LUMINANCE;
        if (json.has("luminance")) {
            luminance = json.get("luminance").getAsInt();
            if (luminance < 0 || luminance > 15) {
                WasmPacks.LOGGER.error("[WasmPacks] Simple block {} has 'luminance' {} outside 0-15", fileId, luminance);
                return null;
            }
        }

        boolean blockItem = json.has("block_item")
                ? json.get("block_item").getAsBoolean()
                : DEFAULT_BLOCK_ITEM;

        return new SimpleBlockDefinition(texture, hardness, resistance, sound, mapColor, requiresTool, luminance, blockItem);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public Map<Identifier, SimpleBlockDefinition> getDefinitions() {
        return definitions;
    }

    public SimpleBlockDefinition getDefinition(Identifier id) {
        return definitions.get(id);
    }
}
