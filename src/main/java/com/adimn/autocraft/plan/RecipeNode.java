package com.adimn.autocraft.plan;

import java.util.List;

/**
 * 配方节点：1 个配方 → 1 个节点。做 batches 次该配方产出 outputCount 个 output。
 *
 * inputs：每批消耗的普通原料（数量按批次放大）。
 * catalysts：可复用工具/催化剂（贤者之石、扳手等），整条计划只需持有 count 个，
 * 不按批次消耗；总耗材按催化剂种类去重。
 */
public record RecipeNode(String recipeId, MaterialRef output, int outputCount,
                         List<IngredientRef> inputs, List<IngredientRef> catalysts,
                         String method) {

    /** 兼容旧调用：无催化剂，默认工作台合成。 */
    public RecipeNode(String recipeId, MaterialRef output, int outputCount,
                      List<IngredientRef> inputs) {
        this(recipeId, output, outputCount, inputs, List.of(), "crafting");
    }

    /** 兼容旧调用：无加工方式，默认工作台合成。 */
    public RecipeNode(String recipeId, MaterialRef output, int outputCount,
                      List<IngredientRef> inputs, List<IngredientRef> catalysts) {
        this(recipeId, output, outputCount, inputs, catalysts, "crafting");
    }

    public RecipeNode {
        if (recipeId == null || recipeId.isBlank()) {
            throw new IllegalArgumentException("recipeId must not be blank");
        }
        if (output == null) {
            throw new IllegalArgumentException("output must not be null");
        }
        if (outputCount <= 0) {
            throw new IllegalArgumentException("outputCount must be > 0");
        }
        inputs = List.copyOf(inputs);
        catalysts = List.copyOf(catalysts);
        method = method == null || method.isBlank() ? "crafting" : method;
    }

    /** 是否为 AutoCraft 能自动执行的 3×3 工作台合成。 */
    public boolean isAutoCraftable() {
        return "crafting".equals(method);
    }

    @Override
    public String toString() {
        return recipeId + " -> " + outputCount + "x " + output
                + (catalysts.isEmpty() ? "" : " catalysts=" + catalysts);
    }
}
