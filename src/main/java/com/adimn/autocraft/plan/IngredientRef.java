package com.adimn.autocraft.plan;

import java.util.List;

/**
 * 配方槽位引用：一个槽位需要的物品（count 个），alternatives 列出所有可替代物。
 *
 * tag 配方（如 "minecraft:planks"）展开后 alternatives 含所有木头变体；
 * 普通单物品配方 alternatives.size() == 1。
 *
 * requirementText：展示用 NBT 需求描述（如 "protection:4" / "killCount=500"）。
 * 它不参与库存匹配，只用于预览树/工具提示展示；库存匹配由 alternatives 里的
 * MaterialRef（含 nbt 指纹）负责。
 *
 * reusable：是否为可复用工具/催化剂（如贤者之石、扳手）。
 * 为 true 时该槽位不消耗：整条计划只需持有 count 个，不按批次数量消耗；
 * 总耗材按“每种催化剂最多需要的 count”去重汇总。
 */
public record IngredientRef(List<MaterialRef> alternatives, int count,
                            String requirementText, boolean reusable) {

    /** 便捷工厂：单物品、count 个。 */
    public static IngredientRef of(MaterialRef material, int count) {
        return new IngredientRef(List.of(material), count);
    }

    /** 兼容旧调用：无展示需求、非催化剂。 */
    public IngredientRef(List<MaterialRef> alternatives, int count) {
        this(alternatives, count, "", false);
    }

    /** 兼容旧调用：无展示需求、非催化剂。 */
    public IngredientRef(List<MaterialRef> alternatives, int count, String requirementText) {
        this(alternatives, count, requirementText, false);
    }

    public IngredientRef {
        alternatives = List.copyOf(alternatives);
        if (alternatives.isEmpty()) {
            throw new IllegalArgumentException("ingredient alternatives must not be empty");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("ingredient count must be > 0");
        }
        requirementText = requirementText == null ? "" : requirementText;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (reusable) {
            sb.append("catalyst ");
        }
        sb.append(count).append("x ").append(alternatives);
        if (!requirementText.isEmpty()) {
            sb.append(" [").append(requirementText).append("]");
        }
        return sb.toString();
    }
}
