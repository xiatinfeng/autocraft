package com.adimn.autocraft.util;

import java.util.List;
import java.util.Map;

/**
 * 手动加工信息与“等价获取方式”表。
 *
 * 仅用于预览/提示，绝不参与自动执行：
 *  AutoCraft 只自动执行 3×3 工作台合成；熔炉/高炉/锻造/挖掘等都需要玩家手动完成。
 */
public final class ManualProcessing {

    /** 加工方式元数据。 */
    public record ManualMethod(String id, String displayName, String iconItemId) {
    }

    /** 掉落/手动等价：1 个 itemId 等价于 perUnit 个 alternativeItemId。 */
    public record DropEquivalent(String alternativeItemId, int perUnit, String methodId) {
    }

    private static final Map<String, ManualMethod> METHODS = Map.of(
            "furnace", new ManualMethod("furnace", "熔炉", "minecraft:furnace"),
            "blast_furnace", new ManualMethod("blast_furnace", "高炉", "minecraft:blast_furnace"),
            "smoker", new ManualMethod("smoker", "烟熏炉", "minecraft:smoker"),
            "campfire", new ManualMethod("campfire", "营火", "minecraft:campfire"),
            "stonecutter", new ManualMethod("stonecutter", "切石机", "minecraft:stonecutter"),
            "smithing_table", new ManualMethod("smithing_table", "锻造台", "minecraft:smithing_table"),
            "mining", new ManualMethod("mining", "挖掘", "minecraft:diamond_pickaxe"),
            "manual", new ManualMethod("manual", "手动加工", null)
    );

    /** 物品 id -> 等价获取方式列表。 */
    private static final Map<String, List<DropEquivalent>> DROP_EQUIVALENCES = Map.of(
            "minecraft:amethyst_cluster", List.of(
                    new DropEquivalent("minecraft:amethyst_shard", 6, "mining"))
    );

    private ManualProcessing() {
    }

    public static ManualMethod method(String id) {
        return METHODS.get(id);
    }

    /** 某个物品是否在“手动等价表”中（例如紫水晶簇→碎片）。 */
    public static List<DropEquivalent> equivalences(String itemId) {
        return DROP_EQUIVALENCES.getOrDefault(itemId, List.of());
    }

    /** 根据物品 id 返回手动加工方式（若有掉落等价表条目）。 */
    public static ManualMethod methodForItem(String itemId) {
        List<DropEquivalent> list = equivalences(itemId);
        if (!list.isEmpty()) {
            return method(list.get(0).methodId());
        }
        return null;
    }

    /** 是否为 AutoCraft 可自动执行的工作台合成。 */
    public static boolean isAutoCraftable(String methodId) {
        return "crafting".equals(methodId);
    }
}
