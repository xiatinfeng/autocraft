package com.adimn.autocraft.plan;

import java.util.List;

/**
 * 配方节点：1 个配方 → 1 个节点。做 batches 次该配方产出 outputCount 个 output，
 * 每批消耗 inputs 各槽位。
 */
public record RecipeNode(String recipeId, MaterialRef output, int outputCount,
                         List<IngredientRef> inputs) {

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
    }

    @Override
    public String toString() {
        return recipeId + " -> " + outputCount + "x " + output;
    }
}
