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
 * <h2>Why registration is per-identifier idempotent, not a single one-shot latch</h2>
 * In singleplayer, the integrated server and its "client" share a single JVM
 * and a single {@code BuiltInRegistries.BLOCK} instance. If the server-side
 * reload-triggered {@link #apply} call registers these blocks, and the
 * client-side sync-payload handler then also calls {@link #applyOrdered} for
 * the same definitions, a naive second pass would throw on duplicate
 * registration.
 * <p>
 * An earlier version of this class guarded against that with a single
 * process-wide {@code AtomicBoolean} that, once tripped, skipped ALL future
 * registration for the rest of the game process. That over-corrected: a
 * world load is a brand new server-resource-reload cycle every time (quit to
 * title, then open a world again — same save or a different one — in the
 * SAME game launch), and each of those cycles is expected to register
 * whatever simple blocks/items its own data packs declare. A single
 * "has this process registered anything, ever" latch means the SECOND (and
 * every subsequent) world opened in one launch silently registers nothing at
 * all, even for blocks/items that were never registered by the first world
 * (different save, updated data pack, etc.) — they simply never appear.
 * <p>
 * The correct invariant is per-identifier, not per-process: registering the
 * same id twice must no-op (that's what actually prevents the duplicate-
 * registration crash above), but a NEW id introduced by a later world load
 * must still go through. {@link #registerBlock} / {@link #registerItem}
 * therefore check {@code BuiltInRegistries#containsKey} themselves and skip
 * only the individual entries that already exist, rather than relying on one
 * global flag to skip the entire pass. Whichever call site (server-local
 * {@link #apply}, or the client sync-payload handler) reaches a given id
 * first wins; the other sees it already present and moves on. On a real
 * dedicated server + remote client these are two separate JVMs anyway, so
 * this is only ever a concern for the shared-JVM singleplayer case.
 */
public final class SimpleRegistryApplier {

    private SimpleRegistryApplier() {}

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
        // new this call (everything here may already be present in the
        // registries from an earlier world load this process) — the sync
        // payload should reflect what's really live in the registries right now.
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
     * server used.
     * <p>
     * Safe to call once per world load (server-side) AND again for the
     * client sync handler in the same session — see class doc. Entries whose
     * id is already present in the relevant registry are skipped
     * individually; anything new still gets registered.
     */
    public static void applyOrdered(List<Map.Entry<Identifier, SimpleBlockDefinition>> blocks,
                                     List<Map.Entry<Identifier, SimpleItemDefinition>> items) {
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
            int alreadyPresentBlocks = 0;
            for (Map.Entry<Identifier, SimpleBlockDefinition> entry : blocks) {
                Identifier id = entry.getKey();
                if (BuiltInRegistries.BLOCK.containsKey(id)) {
                    // Already registered by an earlier world load this process (or by
                    // the other call site earlier in this same session) — leave it
                    // alone rather than trying to re-register it.
                    alreadyPresentBlocks++;
                    continue;
                }
                if (registerBlock(id, entry.getValue())) {
                    registeredBlocks++;
                }
            }
            int registeredItems = 0;
            int alreadyPresentItems = 0;
            for (Map.Entry<Identifier, SimpleItemDefinition> entry : items) {
                Identifier id = entry.getKey();
                if (BuiltInRegistries.ITEM.containsKey(id)) {
                    alreadyPresentItems++;
                    continue;
                }
                if (registerItem(id, entry.getValue())) {
                    registeredItems++;
                }
            }
            WasmPacks.LOGGER.info(
                    "[WasmPacks] Simple registry pass complete: {} block(s) registered ({} already present), "
                            + "{} standalone item(s) registered ({} already present).",
                    registeredBlocks, alreadyPresentBlocks, registeredItems, alreadyPresentItems);
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
