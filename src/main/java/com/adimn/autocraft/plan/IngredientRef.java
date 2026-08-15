package com.adimn.autocraft.plan;

import java.util.List;

/**
 * 配方槽位引用：一个槽位需要的物品（count 个），alternatives 列出所有可替代物。
 *
 * tag 配方（如 "minecraft:planks"）展开后 alternatives 含所有木头变体；
 * 普通单物品配方 alternatives.size() == 1。
 */
public record IngredientRef(List<MaterialRef> alternatives, int count) {

    /** 便捷工厂：单物品、count 个。 */
    public static IngredientRef of(MaterialRef material, int count) {
        return new IngredientRef(List.of(material), count);
    }

    public IngredientRef {
        alternatives = List.copyOf(alternatives);
        if (alternatives.isEmpty()) {
            throw new IllegalArgumentException("ingredient alternatives must not be empty");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("ingredient count must be > 0");
        }
    }

    @Override
    public String toString() {
        return count + "x " + alternatives;
    }
}
