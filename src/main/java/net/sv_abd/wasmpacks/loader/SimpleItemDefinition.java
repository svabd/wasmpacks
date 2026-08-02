package net.sv_abd.wasmpacks.loader;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record SimpleItemDefinition(
        Identifier texture,
        int maxStackSize,
        int maxDurability,
        String rarity,
        boolean fireResistant,
        @Nullable DataComponentMap dataComponents
) {
    public SimpleItemDefinition(Identifier texture, int maxStackSize, int maxDurability, String rarity, boolean fireResistant) {
        this(texture, maxStackSize, maxDurability, rarity, fireResistant, null);
    }

    @Override
    public @NotNull String toString() {
        return "SimpleItemDefinition{texture=" + texture
                + ", maxStackSize=" + maxStackSize
                + ", maxDurability=" + maxDurability
                + ", rarity='" + rarity + '\''
                + ", fireResistant=" + fireResistant
                + ", dataComponents=" + dataComponents + '}';
    }
}