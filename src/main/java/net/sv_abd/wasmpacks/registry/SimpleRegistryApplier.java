package net.sv_abd.wasmpacks.registry;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.level.material.MapColor;
import net.sv_abd.wasmpacks.WasmPacks;
import net.sv_abd.wasmpacks.loader.SimpleBlockDefinition;
import net.sv_abd.wasmpacks.loader.SimpleBlockLoader;
import net.sv_abd.wasmpacks.loader.SimpleItemDefinition;
import net.sv_abd.wasmpacks.loader.SimpleItemLoader;
import net.sv_abd.wasmpacks.mixin.MappedRegistryAccessor;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns parsed {@link SimpleBlockDefinition}/{@link SimpleItemDefinition}
 * entries into real {@link Block}/{@link Item} instances in
 * {@link BuiltInRegistries#BLOCK}/{@link BuiltInRegistries#ITEM}.
 *
 * BLOCK and ITEM are frozen during mod loading and normally reject writes
 * after that point (see {@link MappedRegistryAccessor} for why/how we can
 * still write to them). We only ever want to do this ONCE per server
 * process — see the class doc on {@link #apply} for why.
 *
 * WHAT'S NOT DONE HERE: this only registers the server-side Block/Item
 * objects (behavior, hardness, stack size, etc.). It does NOT give them a
 * client-side appearance. Data packs cannot ship textures or models, so a
 * simple block/item registered by this class will render as the
 * missing-texture checkerboard until a client-side step generates a
 * synthetic block-state/model in memory that points at the existing texture
 * named in {@code def.texture()} — that's a separate piece of work (model
 * baking hooks, not registry hooks) and is intentionally left for a
 * follow-up rather than guessed at here.
 */
public final class SimpleRegistryApplier {

    private SimpleRegistryApplier() {}

    /**
     * Ensures the unfreeze/register/refreeze cycle only ever runs once per
     * server process. Block/Item IDs get baked into world saves and synced
     * to clients at login; adding or removing entries between sessions is
     * explicitly out of scope (see design discussion — "no mid-session
     * add/remove", extended here to "no mid-process re-registration either").
     * On a later /reload, this listener's apply() will fire again (same as
     * every other reload listener), but we detect that and skip, rather
     * than attempt to re-register (which would throw on duplicate ids) or
     * silently ignore data pack changes without saying so.
     */
    private static final AtomicBoolean APPLIED = new AtomicBoolean(false);

    public static void apply(SimpleBlockLoader blockLoader, SimpleItemLoader itemLoader) {
        if (!APPLIED.compareAndSet(false, true)) {
            WasmPacks.LOGGER.warn(
                    "[WasmPacks] Simple block/item registration already ran for this server process. "
                            + "Data pack changes to simple_blocks/simple_items require a full restart to take "
                            + "effect, not just /reload. Skipping.");
            return;
        }

        Map<Identifier, SimpleBlockDefinition> blocks = blockLoader.getDefinitions();
        Map<Identifier, SimpleItemDefinition> items = itemLoader.getDefinitions();

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
            for (Map.Entry<Identifier, SimpleBlockDefinition> entry : blocks.entrySet()) {
                if (registerBlock(entry.getKey(), entry.getValue())) {
                    registeredBlocks++;
                }
            }
            int registeredItems = 0;
            for (Map.Entry<Identifier, SimpleItemDefinition> entry : items.entrySet()) {
                if (registerItem(entry.getKey(), entry.getValue())) {
                    registeredItems++;
                }
            }
            WasmPacks.LOGGER.info(
                    "[WasmPacks] Simple registry pass complete: {} block(s), {} standalone item(s) registered.",
                    registeredBlocks, registeredItems);
        } finally {
            // Always re-freeze even if something above threw, so a bad definition
            // can't leave BLOCK/ITEM permanently writable for the rest of the run.
            blockRegistry.wasmpacks$setFrozen(true);
            itemRegistry.wasmpacks$setFrozen(true);
        }
    }

    /** Returns true if the block (and its BlockItem, if requested) registered successfully. */
    private static boolean registerBlock(Identifier id, SimpleBlockDefinition def) {
        try {
            SoundType sound = SimpleRegistryResolver.resolveSound(def.sound());
            MapColor mapColor = SimpleRegistryResolver.resolveMapColor(def.mapColor());

            // Since MC 1.21.2, Block/Item construction requires the registry key to be
            // set on Properties BEFORE construction (Properties#setId) — omitting this
            // throws NullPointerException("Block/Item id not set"). See NeoForge's 1.21.2
            // migration primer for the exact pattern this mirrors.
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
            WasmPacks.LOGGER.debug("[WasmPacks] Registered simple block: {}", id);

            if (def.blockItem()) {
                ResourceKey<@NotNull Item> itemKey = ResourceKey.create(Registries.ITEM, id);
                Item.Properties itemProps = new Item.Properties()
                        .useBlockDescriptionPrefix()
                        .setId(itemKey);
                BlockItem blockItem = new BlockItem(block, itemProps);
                Registry.register(BuiltInRegistries.ITEM, itemKey, blockItem);
                // See bindItemComponents() doc: the normal component-binding pipeline
                // runs before our reload listener registers anything, so it never
                // picks these up on its own — bind manually. BlockItems get plain
                // defaults (stack 64, no durability, common rarity, not fire-resistant)
                // since simple_blocks JSON doesn't currently expose these for the item side.
                bindItemComponents(blockItem, id, "block", 64, 0, Rarity.COMMON);
                WasmPacks.LOGGER.debug("[WasmPacks] Registered auto BlockItem for: {}", id);
            }
            return true;
        } catch (Exception e) {
            // One bad/colliding definition should not abort the whole registration pass.
            WasmPacks.LOGGER.error("[WasmPacks] Failed to register simple block {}: {}", id, e.getMessage());
            return false;
        }
    }

    private static boolean registerItem(Identifier id, SimpleItemDefinition def) {
        try {
            if (BuiltInRegistries.ITEM.containsKey(id)) {
                // Most likely a simple_items entry colliding with an auto-generated
                // BlockItem of the same id, or with vanilla/another mod's item.
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

            Item item = new Item(props);
            Registry.register(BuiltInRegistries.ITEM, itemKey, item);
            bindItemComponents(item, id, "item", def.maxStackSize(), def.maxDurability(),
                    SimpleRegistryResolver.resolveRarity(def.rarity()));
            WasmPacks.LOGGER.debug("[WasmPacks] Registered simple item: {}", id);
            return true;
        } catch (Exception e) {
            WasmPacks.LOGGER.error("[WasmPacks] Failed to register simple item {}: {}", id, e.getMessage());
            return false;
        }
    }

    /**
     * Manually binds the item's {@link DataComponentMap} onto its registry
     * {@link Holder.Reference}, bypassing vanilla's normal pending-initializer
     * pipeline.
     *
     * WHY THIS IS NEEDED: Item's constructor doesn't bind components directly —
     * it stashes a pending initializer into BuiltInRegistries.DATA_COMPONENT_INITIALIZERS,
     * which ReloadableServerResources later applies via
     * updateComponentsAndStaticRegistryTags() -> this.newComponents.forEach(...).
     * That `newComponents` list is built when ReloadableServerResources itself is
     * constructed, BEFORE our reload listener runs and registers anything — so
     * our items' pending initializers are never in the batch that gets applied,
     * and Holder.Reference#components() would otherwise throw
     * "Components not bound yet" the moment anything reads it (which NeoForge's
     * own startup code does, unconditionally, for every item).
     *
     * We start from DataComponents.COMMON_ITEM_COMPONENTS (the same base every
     * normal item gets via Item.Properties()'s default componentInitializer),
     * then layer on ITEM_NAME/ITEM_MODEL (which is what actually makes the item
     * display a name at all — without ITEM_NAME bound, the item has no name
     * component whatsoever, which renders as blank, not as a raw translation key)
     * and the stack size/durability/rarity we already exposed via JSON.
     *
     * RISK NOTE: none of this is verified against actual 26.2 sources beyond
     * what's been confirmed through real compile errors and behavior reports so
     * far. Failure here is caught and logged, not fatal to the overall
     * registration pass — but an item that fails to bind will likely still show
     * blank/wrong until corrected.
     */
    /**
     * Inlined equivalent of vanilla's Util.makeDescriptionId(type, id), which
     * has been "type + '.' + namespace + '.' + path.replace('/', '.')" for a
     * very long time. Inlined rather than importing Util directly, since we
     * don't yet know which package it lives in for this MC version (an
     * earlier attempt to import net.minecraft.Util failed to resolve).
     */
    private static String makeDescriptionId(String type, Identifier id) {
        return type + "." + id.getNamespace() + "." + id.getPath().replace('/', '.');
    }

    private static void bindItemComponents(Item item, Identifier id, String descriptionPrefix,
                                            int maxStackSize, int maxDurability, Rarity rarity) {
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
            ref.bindComponents(builder.build());
        } catch (Exception e) {
            WasmPacks.LOGGER.error(
                    "[WasmPacks] Failed to bind components for item {} — it will likely be missing correct "
                            + "name/stack size/durability/rarity data this session. Error: {}",
                    item, e.getMessage());
        }
    }
}
