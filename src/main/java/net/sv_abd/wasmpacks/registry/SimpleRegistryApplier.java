package net.sv_abd.wasmpacks.registry;

import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.sv_abd.wasmpacks.WasmPacks;
import net.sv_abd.wasmpacks.loader.*;
import net.sv_abd.wasmpacks.mixin.BlockStateIdMapperAccessor;
import net.sv_abd.wasmpacks.mixin.MappedRegistryAccessor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns parsed {@link SimpleBlockDefinition}/{@link SimpleItemDefinition}
 * entries into real {@link Block}/{@link Item} instances in
 * {@link BuiltInRegistries#BLOCK}/{@link BuiltInRegistries#ITEM}, AND gives
 * every resulting {@link BlockState} a network id via
 * {@link BlockStateIdMapperAccessor}.
 *
 * <h2>Determinism is the whole game here</h2>
 * Both {@code BuiltInRegistries.BLOCK}'s registration order and
 * {@code Block.BLOCK_STATE_REGISTRY}'s id-assignment order matter for network
 * compatibility: every process that will exchange packets referencing these
 * blocks — the authoritative server AND every connecting client — must call
 * {@link #applyOrdered} with the exact same definitions in the exact same
 * order, or the same logical block will resolve to a different int on
 * different machines and packet
 * en/decoding will fail exactly like the crash this class exists to fix.
 * <p>
 * For that reason:
 * <ul>
 *   <li>{@link #apply(SimpleBlockLoader, SimpleItemLoader)} — the server-side
 *       entry point — sorts entries by {@code Identifier.toString()} before
 *       registering, rather than relying on {@code HashMap} iteration order
 *       (which is not guaranteed stable across JVMs/processes).</li>
 *   <li>The same sorted lists are cached ({@link #getSortedBlocks()} /
 *       {@link #getSortedItems()}) so the multiplayer sync payload can be
 *       built from the EXACT list that was actually registered, not
 *       re-derived separately.</li>
 *   <li>{@link #applyOrdered} is the method clients call (with definitions
 *       decoded from the sync payload, already in server order) — it performs
 *       no sorting of its own, it trusts the order it's given.</li>
 * </ul>
 *
 * <h2>Why one guard is shared across server and client call sites</h2>
 * In singleplayer, the integrated server and its "client" share a single JVM
 * and a single {@code BuiltInRegistries.BLOCK} instance. If the server-side
 * reload-triggered {@link #apply} call registers these blocks, and the
 * client-side sync-payload handler then also calls {@link #applyOrdered} for
 * the same definitions, the second call would throw on duplicate
 * registration. {@link #APPLIED} is a single process-wide guard specifically
 * so this can't double-fire — whichever call site runs first (in practice,
 * the server-side one, since it runs before any configuration task can be
 * sent) wins, and the other silently no-ops. On a real dedicated server +
 * remote client, these are two separate JVMs with two separate
 * {@code AtomicBoolean}s, so each side still runs its own registration
 * exactly once, independently.
 */
public final class SimpleRegistryApplier {

    private SimpleRegistryApplier() {}

    /** See class doc — shared across server-local and client-sync call sites. */
    private static final AtomicBoolean APPLIED = new AtomicBoolean(false);

    /**
     * The exact sorted definition lists last registered by THIS process,
     * whether via {@link #apply} or {@link #applyOrdered} directly. Null
     * until the first successful call. Used by the server to build the
     * multiplayer sync payload from the same data it actually registered,
     * rather than re-deriving it and risking drift.
     */
    private static volatile List<Map.Entry<Identifier, SimpleBlockDefinition>> lastSortedBlocks;
    private static volatile List<Map.Entry<Identifier, SimpleItemDefinition>> lastSortedItems;

    // -------------------------------------------------------------------------
    // Server-side entry point: sorts, then delegates to applyOrdered
    // -------------------------------------------------------------------------

    /**
     * Server-side entry point. Reads the loaders' current definitions,
     * deterministically sorts them by {@code Identifier.toString()}, and
     * registers them. This is the AUTHORITATIVE ordering — whatever this
     * method registers here is also what gets shipped to clients via the
     * sync payload (see {@link #getSortedBlocks()}/{@link #getSortedItems()}),
     * so clients end up with byte-for-byte the same order.
     */
    public static void apply(SimpleBlockLoader blockLoader, SimpleItemLoader itemLoader) {
        List<Map.Entry<Identifier, SimpleBlockDefinition>> sortedBlocks =
                new ArrayList<>(blockLoader.getDefinitions().entrySet());
        sortedBlocks.sort(Comparator.comparing(e -> e.getKey().toString()));

        List<Map.Entry<Identifier, SimpleItemDefinition>> sortedItems =
                new ArrayList<>(itemLoader.getDefinitions().entrySet());
        sortedItems.sort(Comparator.comparing(e -> e.getKey().toString()));

        // Cache regardless of whether applyOrdered actually registers anything
        // this call (APPLIED may already be tripped) — the sync payload should
        // reflect what's really live in the registries right now.
        lastSortedBlocks = sortedBlocks;
        lastSortedItems = sortedItems;

        applyOrdered(sortedBlocks, sortedItems);
    }

    // -------------------------------------------------------------------------
    // Shared, side-agnostic registration — called by server (above) AND by
    // the client's sync-payload handler with server-supplied, already-ordered
    // definitions.
    // -------------------------------------------------------------------------

    /**
     * Registers the given definitions IN THE ORDER GIVEN. Callers are
     * responsible for ensuring that order is deterministic and, for
     * multiplayer correctness, identical to whatever order the authoritative
     * server used. Guarded by {@link #APPLIED} — see class doc.
     */
    public static void applyOrdered(List<Map.Entry<Identifier, SimpleBlockDefinition>> blocks,
                                     List<Map.Entry<Identifier, SimpleItemDefinition>> items) {
        if (!APPLIED.compareAndSet(false, true)) {
            WasmPacks.LOGGER.warn(
                    "[WasmPacks] Simple block/item registration already ran for this process. "
                            + "Data pack changes to simple_blocks/simple_items require a full restart to take "
                            + "effect, not just /reload. Skipping.");
            return;
        }

        // Also cache here, in case applyOrdered is ever called directly
        // (e.g. from the client sync handler) without going through apply().
        lastSortedBlocks = blocks;
        lastSortedItems = items;

        if (blocks.isEmpty() && items.isEmpty()) {
            WasmPacks.LOGGER.info("[WasmPacks] No simple blocks/items declared, nothing to register.");
            return;
        }

        MappedRegistryAccessor blockRegistry = (MappedRegistryAccessor) BuiltInRegistries.BLOCK;
        MappedRegistryAccessor itemRegistry = (MappedRegistryAccessor) BuiltInRegistries.ITEM;

        blockRegistry.wasmpacks$setFrozen(false);
        itemRegistry.wasmpacks$setFrozen(false);
        try {
            int registeredBlocks = 0;
            for (Map.Entry<Identifier, SimpleBlockDefinition> entry : blocks) {
                if (registerBlock(entry.getKey(), entry.getValue())) {
                    registeredBlocks++;
                }
            }
            int registeredItems = 0;
            for (Map.Entry<Identifier, SimpleItemDefinition> entry : items) {
                if (registerItem(entry.getKey(), entry.getValue())) {
                    registeredItems++;
                }
            }
            WasmPacks.LOGGER.info(
                    "[WasmPacks] Simple registry pass complete: {} block(s), {} standalone item(s) registered.",
                    registeredBlocks, registeredItems);
        } finally {
            blockRegistry.wasmpacks$setFrozen(true);
            itemRegistry.wasmpacks$setFrozen(true);
        }
    }

    /**
     * The exact sorted block definitions last registered by this process.
     * Used by the server to build the multiplayer sync payload. May be null
     * if nothing has been registered yet.
     */
    public static List<Map.Entry<Identifier, SimpleBlockDefinition>> getSortedBlocks() {
        return lastSortedBlocks;
    }

    /** See {@link #getSortedBlocks()}. */
    public static List<Map.Entry<Identifier, SimpleItemDefinition>> getSortedItems() {
        return lastSortedItems;
    }

    // -------------------------------------------------------------------------
    // Per-block/item registration (unchanged from the original single-registry
    // version, aside from the added block-state id mapper step)
    // -------------------------------------------------------------------------

    /** Returns true if the block (and its BlockItem, if requested) registered successfully. */
    private static boolean registerBlock(Identifier id, SimpleBlockDefinition def) {
        try {
            SoundType sound = SimpleRegistryResolver.resolveSound(def.sound());
            MapColor mapColor = SimpleRegistryResolver.resolveMapColor(def.mapColor());

            ResourceKey<@NotNull Block> blockKey = ResourceKey.create(Registries.BLOCK, id);

            BlockBehaviour.Properties props = BlockBehaviour.Properties.of()
                    .setId(blockKey)
                    .mapColor(mapColor)
                    .strength(def.hardness(), def.resistance())
                    .sound(sound)
                    .lightLevel(state -> def.luminance());
            if (def.requiresTool()) {
                props = props.requiresCorrectToolForDrops();
            }

            Block block = new Block(props);
            Registry.register(BuiltInRegistries.BLOCK, blockKey, block);

            // 1. Initialize state cache & occlusion shapes
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                state.initCache();
            }

            // 2. Register states with the network ID mapper (single loop)
            IdMapper<BlockState> stateIds = BlockStateIdMapperAccessor.wasmpacks$getBlockStateRegistry();
            int statesAdded = 0;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                stateIds.add(state);
                statesAdded++;
            }

            WasmPacks.LOGGER.debug(
                    "[WasmPacks] Registered simple block: {} ({} block state(s) assigned network ids)",
                    id, statesAdded);

            if (def.blockItem()) {
                ResourceKey<@NotNull Item> itemKey = ResourceKey.create(Registries.ITEM, id);
                Item.Properties itemProps = new Item.Properties()
                    .useBlockDescriptionPrefix()
                    .setId(itemKey);
                BlockItem blockItem = new BlockItem(block, itemProps);
                Registry.register(BuiltInRegistries.ITEM, itemKey, blockItem);

                // Fixed: Added the 7th argument (null or DataComponentMap.EMPTY)
                bindItemComponents(blockItem, id, "block", 64, 0, Rarity.COMMON, null);

                WasmPacks.LOGGER.debug("[WasmPacks] Registered auto BlockItem for: {}", id);
            }
            return true;
        } catch (Exception e) {
            WasmPacks.LOGGER.error("[WasmPacks] Failed to register simple block {}: {}", id, e.getMessage());
            return false;
        }
    }

    private static boolean registerItem(Identifier id, SimpleItemDefinition def) {
        try {
            if (BuiltInRegistries.ITEM.containsKey(id)) {
                WasmPacks.LOGGER.error(
                        "[WasmPacks] Simple item {} collides with an already-registered item, skipping.", id);
                return false;
            }

            ResourceKey<@NotNull Item> itemKey = ResourceKey.create(Registries.ITEM, id);
            Item.Properties props = new Item.Properties()
                    .setId(itemKey)
                    .stacksTo(def.maxStackSize())
                    .rarity(SimpleRegistryResolver.resolveRarity(def.rarity()));
            if (def.maxDurability() > 0) {
                props = props.durability(def.maxDurability());
            }
            if (def.fireResistant()) {
                props = props.fireResistant();
            }

            // Apply custom components directly to properties
            if (def.dataComponents() != null) {
                for (TypedDataComponent<?> component : def.dataComponents()) {
                    applyComponent(props, component);
                }
            }

            Item item = new Item(props);
            Registry.register(BuiltInRegistries.ITEM, itemKey, item);
            bindItemComponents(item, id, "item", def.maxStackSize(), def.maxDurability(),
                    SimpleRegistryResolver.resolveRarity(def.rarity()), def.dataComponents());
            WasmPacks.LOGGER.debug("[WasmPacks] Registered simple item: {}", id);
            return true;
        } catch (Exception e) {
            WasmPacks.LOGGER.error("[WasmPacks] Failed to register simple item {}: {}", id, e.getMessage());
            return false;
        }
    }

    private static void bindItemComponents(Item item, Identifier id, String descriptionPrefix,
                                           int maxStackSize, int maxDurability, Rarity rarity,
                                           DataComponentMap customComponents) {
        try {
            Holder<@NotNull Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(item);
            if (!(holder instanceof Holder.Reference<@NotNull Item> ref)) {
                WasmPacks.LOGGER.warn(
                        "[WasmPacks] Item holder for {} is not a Holder.Reference, cannot bind components", item);
                return;
            }
            String descriptionId = makeDescriptionId(descriptionPrefix, id);
            DataComponentMap.Builder builder = DataComponentMap.builder();
            builder.addAll(DataComponents.COMMON_ITEM_COMPONENTS);
            builder.set(DataComponents.ITEM_NAME, Component.translatable(descriptionId));
            builder.set(DataComponents.ITEM_MODEL, id);
            builder.set(DataComponents.MAX_STACK_SIZE, maxStackSize);
            if (maxDurability > 0) {
                builder.set(DataComponents.MAX_DAMAGE, maxDurability);
            }
            builder.set(DataComponents.RARITY, rarity);

            // Merge custom components using native DataComponentMap iteration
            if (customComponents != null) {
                builder.addAll(customComponents);
            }

            ref.bindComponents(builder.build());
        } catch (Exception e) {
            WasmPacks.LOGGER.error(
                    "[WasmPacks] Failed to bind components for item {} — error: {}",
                    item, e.getMessage());
        }
    }

    private static <T> void applyComponent(Item.Properties properties, TypedDataComponent<@NotNull T> component) {
        properties.component(component.type(), component.value());
    }

    private static String makeDescriptionId(String type, Identifier id) {
        return type + "." + id.getNamespace() + "." + id.getPath().replace('/', '.');
    }
}
