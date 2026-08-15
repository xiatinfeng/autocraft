package com.adimn.autocraft.plan;

import java.util.Objects;

/**
 * 物品引用：itemId（modid:path）+ nbt（v1 恒 ""）。
 *
 * M1 纯 Java 层用 String 存 id（如 "minecraft:oak_log"），零 MC 依赖；
 * M2 接入客户端 RecipeManager 时由 RecipeIndex 负责转换（ResourceLocation → String）。
 */
public record MaterialRef(String itemId, String nbt) {

    /** 便捷工厂：无 NBT 物品。 */
    public static MaterialRef of(String itemId) {
        return new MaterialRef(itemId, "");
    }

    public MaterialRef {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        nbt = nbt == null ? "" : nbt;
    }

    @Override
    public String toString() {
        return nbt.isEmpty() ? itemId : itemId + "#" + nbt;
    }

    /** 兼容旧 equals/hashCode 习惯；record 已自动实现，这里仅显式声明意图。 */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MaterialRef other)) return false;
        return itemId.equals(other.itemId) && nbt.equals(other.nbt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemId, nbt);
    }
}
