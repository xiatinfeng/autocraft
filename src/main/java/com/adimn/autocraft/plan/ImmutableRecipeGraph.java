package com.adimn.autocraft.plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 配方图（纯值、不可变）：物品 → 产出它的所有配方 + id → 配方节点。
 *
 * 不可变性保证：跨线程安全（规划可异步）、缓存可复用、离线单测无需 MC 运行时。
 * 构建后任何外部可变容器都不能再影响图内容（构造器内统一深拷贝）。
 */
public record ImmutableRecipeGraph(
        Map<MaterialRef, List<RecipeNode>> recipesByOutput,
        Map<String, RecipeNode> recipesById) {

    /** 从配方节点集合构建：按 output 建索引（保持插入序），按 recipeId 建索引（首个胜出）。 */
    public static ImmutableRecipeGraph build(Collection<RecipeNode> nodes) {
        Map<MaterialRef, List<RecipeNode>> byOutput = new LinkedHashMap<>();
        Map<String, RecipeNode> byId = new LinkedHashMap<>();
        for (RecipeNode node : nodes) {
            byOutput.computeIfAbsent(node.output(), k -> new ArrayList<>()).add(node);
            byId.putIfAbsent(node.recipeId(), node);
        }
        return new ImmutableRecipeGraph(byOutput, byId);
    }

    /** 从已按 output 分组的 map 构建（兼容 RecipeIndex 直接产出该形状）。 */
    public static ImmutableRecipeGraph from(Map<MaterialRef, List<RecipeNode>> byOutput) {
        Map<String, RecipeNode> byId = new LinkedHashMap<>();
        for (List<RecipeNode> candidates : byOutput.values()) {
            for (RecipeNode node : candidates) {
                byId.putIfAbsent(node.recipeId(), node);
            }
        }
        return new ImmutableRecipeGraph(byOutput, byId);
    }

    public ImmutableRecipeGraph {
        recipesByOutput = recipesByOutput.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        recipesById = Map.copyOf(recipesById);
    }

    /** 产出某物品的所有配方（无则空表）。 */
    public List<RecipeNode> recipesFor(MaterialRef output) {
        return recipesByOutput.getOrDefault(output, List.of());
    }

    /** 按 id 查配方节点（无则 null）。 */
    public RecipeNode recipeById(String recipeId) {
        return recipesById.get(recipeId);
    }
}
