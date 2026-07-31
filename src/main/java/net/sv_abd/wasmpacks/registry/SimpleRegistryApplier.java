package net.sv_abd.wasmpacks.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
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
 * synthetic blockstate/model in memory that points at the existing texture
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

            BlockBehaviour.Properties props = BlockBehaviour.Properties.of()
                    .mapColor(mapColor)
                    .strength(def.hardness(), def.resistance())
                    .sound(sound)
                    .lightLevel(state -> def.luminance());
            if (def.requiresTool()) {
                props = props.requiresCorrectToolForDrops();
            }

            Block block = new Block(props);
            Registry.register(BuiltInRegistries.BLOCK, id, block);
            WasmPacks.LOGGER.debug("[WasmPacks] Registered simple block: {}", id);

            if (def.blockItem()) {
                BlockItem blockItem = new BlockItem(block, new Item.Properties());
                Registry.register(BuiltInRegistries.ITEM, id, blockItem);
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

            Item.Properties props = new Item.Properties()
                    .stacksTo(def.maxStackSize())
                    .rarity(SimpleRegistryResolver.resolveRarity(def.rarity()));
            if (def.maxDurability() > 0) {
                props = props.durability(def.maxDurability());
            }
            if (def.fireResistant()) {
                props = props.fireResistant();
            }

            Item item = new Item(props);
            Registry.register(BuiltInRegistries.ITEM, id, item);
            WasmPacks.LOGGER.debug("[WasmPacks] Registered simple item: {}", id);
            return true;
        } catch (Exception e) {
            WasmPacks.LOGGER.error("[WasmPacks] Failed to register simple item {}: {}", id, e.getMessage());
            return false;
        }
    }
}
