package net.sv_abd.wasmpacks.loader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.sv_abd.wasmpacks.WasmPacks;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Reader;
import java.util.*;

/**
 * Scans all datapacks for files matching:
 *   data/<namespace>/wasmpacks/simple_items/<path>.json
 *
 * Each JSON file is parsed into a {@link SimpleItemDefinition}. The map is
 * keyed by the logical item id derived from the file path, same convention
 * as {@link SimpleBlockLoader}.
 *
 * Standalone simple items (not auto-generated BlockItems from simple_blocks)
 * are declared here. See {@link SimpleBlockLoader} for the same "resolved
 * once at world load, not per /reload" caveat — it applies equally here.
 */
public class SimpleItemLoader extends SimplePreparableReloadListener<@NotNull Map<Identifier, SimpleItemDefinition>> {

    public static final Identifier ID = Identifier.fromNamespaceAndPath(WasmPacks.MOD_ID, "simple_item_loader");

    private static final String DIRECTORY = "wasmpacks/simple_items";
    private static final String EXTENSION = ".json";

    private static final int DEFAULT_MAX_STACK_SIZE = 64;
    private static final int DEFAULT_MAX_DURABILITY = 0;
    private static final String DEFAULT_RARITY = "common";
    private static final boolean DEFAULT_FIRE_RESISTANT = false;

    /** Known rarity keys. Resolved to actual game rarity at registration time. */
    public static final Set<String> KNOWN_RARITIES = Set.of("common", "uncommon", "rare", "epic");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Map<Identifier, SimpleItemDefinition> definitions = Collections.emptyMap();

    // -------------------------------------------------------------------------
    // SimplePreparableReloadListener contract
    // -------------------------------------------------------------------------

    @Override
    protected Map<Identifier, SimpleItemDefinition> prepare(ResourceManager manager, @NotNull ProfilerFiller profiler) {
        Map<Identifier, SimpleItemDefinition> result = new HashMap<>();

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
            Identifier itemId = Identifier.fromNamespaceAndPath(fileId.getNamespace(), trimmedPath);

            try (Reader reader = resource.openAsReader()) {
                JsonElement jsonElement = GSON.fromJson(reader, JsonElement.class);
                if (!jsonElement.isJsonObject()) {
                    WasmPacks.LOGGER.error("[WasmPacks] Simple item file {} is not a JSON object, skipping", fileId);
                    continue;
                }
                JsonObject json = jsonElement.getAsJsonObject();
                SimpleItemDefinition def = parseDefinition(fileId, json);
                if (def != null) {
                    result.put(itemId, def);
                    WasmPacks.LOGGER.debug("[WasmPacks] Loaded simple item: {} -> {}", itemId, def);
                }
            } catch (IOException e) {
                WasmPacks.LOGGER.error("[WasmPacks] Failed to read simple item file {}: {}", fileId, e.getMessage());
            } catch (JsonParseException e) {
                WasmPacks.LOGGER.error("[WasmPacks] Failed to parse JSON in simple item file {}: {}", fileId, e.getMessage());
            }
        }

        WasmPacks.LOGGER.info("[WasmPacks] Prepared {} simple item definition(s)", result.size());
        return result;
    }

    @Override
    protected void apply(Map<Identifier, SimpleItemDefinition> prepared, @NotNull ResourceManager manager, @NotNull ProfilerFiller profiler) {
        this.definitions = Collections.unmodifiableMap(prepared);
        WasmPacks.LOGGER.info("[WasmPacks] Applied {} simple item definition(s)", this.definitions.size());
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    private static SimpleItemDefinition parseDefinition(Identifier fileId, JsonObject json) {
        if (!json.has("texture")) {
            WasmPacks.LOGGER.error("[WasmPacks] Simple item {} is missing required field 'texture'", fileId);
            return null;
        }
        String textureStr = json.get("texture").getAsString();
        Identifier texture;
        try {
            texture = Identifier.parse(textureStr);
        } catch (Exception e) {
            WasmPacks.LOGGER.error("[WasmPacks] Simple item {} has invalid 'texture' Identifier '{}': {}",
                    fileId, textureStr, e.getMessage());
            return null;
        }

        int maxStackSize = DEFAULT_MAX_STACK_SIZE;
        if (json.has("max_stack_size")) {
            maxStackSize = json.get("max_stack_size").getAsInt();
            if (maxStackSize < 1 || maxStackSize > 64) {
                WasmPacks.LOGGER.error("[WasmPacks] Simple item {} has 'max_stack_size' {} outside 1-64", fileId, maxStackSize);
                return null;
            }
        }

        int maxDurability = DEFAULT_MAX_DURABILITY;
        if (json.has("max_durability")) {
            maxDurability = json.get("max_durability").getAsInt();
            if (maxDurability < 0) {
                WasmPacks.LOGGER.error("[WasmPacks] Simple item {} has negative 'max_durability'", fileId);
                return null;
            }
            if (maxDurability > 0 && maxStackSize != 1) {
                WasmPacks.LOGGER.error(
                        "[WasmPacks] Simple item {} declares 'max_durability' > 0 but 'max_stack_size' != 1 "
                                + "(durable items must be non-stackable)", fileId);
                return null;
            }
        }

        String rarity = DEFAULT_RARITY;
        if (json.has("rarity")) {
            rarity = json.get("rarity").getAsString();
            if (!KNOWN_RARITIES.contains(rarity)) {
                WasmPacks.LOGGER.error("[WasmPacks] Simple item {} has unknown 'rarity' key '{}'. Known: {}",
                        fileId, rarity, KNOWN_RARITIES);
                return null;
            }
        }

        boolean fireResistant = json.has("fire_resistant")
                ? json.get("fire_resistant").getAsBoolean()
                : DEFAULT_FIRE_RESISTANT;

        DataComponentMap dataComponents = null;

        if (json.has("components")) {
            JsonElement componentsEl = json.get("components");
            if (componentsEl.isJsonObject()) {
                dataComponents = parseDataComponents(fileId, componentsEl.getAsJsonObject());
            } else {
                WasmPacks.LOGGER.error("[WasmPacks] Simple item {} has non-object 'components' field", fileId);
            }
        }

        return new SimpleItemDefinition(texture, maxStackSize, maxDurability, rarity, fireResistant, dataComponents);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public Map<Identifier, SimpleItemDefinition> getDefinitions() {
        return definitions;
    }

    public SimpleItemDefinition getDefinition(Identifier id) {
        return definitions.get(id);
    }

    private static DataComponentMap parseDataComponents(Identifier fileId, JsonObject componentsObj) {
        DataComponentMap.Builder builder = DataComponentMap.builder();

        for (Map.Entry<String, JsonElement> entry : componentsObj.entrySet()) {
            String key = entry.getKey();
            Identifier componentId;
            try {
                componentId = Identifier.parse(key);
            } catch (Exception e) {
                WasmPacks.LOGGER.error("[WasmPacks] Invalid component identifier '{}' in {}", key, fileId);
                continue;
            }

            // Retrieve type from registry
            DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(componentId);
            if (type == null) {
                WasmPacks.LOGGER.error("[WasmPacks] Unknown DataComponentType '{}' in {}", componentId, fileId);
                continue;
            }

            // Decode value using type's codec and add directly to builder
            parseAndSetComponent(builder, type, entry.getValue(), componentId, fileId);
        }

        return builder.build();
    }

    private static <T> void parseAndSetComponent(DataComponentMap.Builder builder, DataComponentType<T> type, JsonElement json, Identifier componentId, Identifier fileId) {
        type.codec().parse(com.mojang.serialization.JsonOps.INSTANCE, json)
                .resultOrPartial(err -> WasmPacks.LOGGER.error("[WasmPacks] Failed to parse component '{}' in {}: {}", componentId, fileId, err))
                .ifPresent(val -> builder.set(type, val));
    }
}
