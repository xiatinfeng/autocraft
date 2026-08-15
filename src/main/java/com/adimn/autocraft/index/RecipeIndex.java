package com.adimn.autocraft.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.adimn.autocraft.config.Config;
import com.adimn.autocraft.plan.ImmutableRecipeGraph;
import com.adimn.autocraft.plan.IngredientRef;
import com.adimn.autocraft.plan.MaterialRef;
import com.adimn.autocraft.plan.RecipeNode;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 配方索引（M2）：RecipeManager → 纯值 ImmutableRecipeGraph。
 *
 * 版本化缓存：记录 RecipeManager 实例引用——数据包重载/世界切换会产生新实例，
 * 实例变化时自动重建图（不可变图可安全跨线程共享，重建成本只在变化时发生）。
 *
 * 背包快照：Player → Map&lt;MaterialRef, Integer&gt;（含 armor/offhand），供 PureSearchPlanner 使用。
 *
 * 已核实 1.20.1 真实 API（勿按 1.21 写法）：
 *   - RecipeManager.getAllRecipesFor(RecipeType) 直接返回 List&lt;Recipe&lt;C&gt;&gt;（无 RecipeHolder 包装）
 *   - Recipe.getResultItem(RegistryAccess) / getIngredients() / getId()
 *   - Ingredient.getItems() → ItemStack[]；Ingredient.isEmpty()
 *   - ForgeRegistries.ITEMS.getKey(Item) / getValue(ResourceLocation)
 */
public final class RecipeIndex {
    private static volatile RecipeManager cachedManager;
    private static volatile ImmutableRecipeGraph cachedGraph;

    private RecipeIndex() {}

    /** 取当前配方图（版本化缓存：RecipeManager 实例变化时自动重建）。 */
    public static ImmutableRecipeGraph graph(RecipeManager manager, RegistryAccess registryAccess) {
        if (manager != cachedManager) {
            cachedGraph = build(manager, registryAccess);
            cachedManager = manager;
        }
        return cachedGraph;
    }

    /** 玩家背包快照（主手/盔甲/副手全部计入）。 */
    public static Map<MaterialRef, Integer> snapshotInventory(Player player) {
        Map<MaterialRef, Integer> stock = new HashMap<>();
        Inventory inv = player.getInventory();
        countStacks(inv.items, stock);
        countStacks(inv.armor, stock);
        countStacks(inv.offhand, stock);
        return stock;
    }

    // ------------------------------------------------------------------

    private static ImmutableRecipeGraph build(RecipeManager manager, RegistryAccess registryAccess) {
        List<RecipeNode> nodes = new ArrayList<>();
        for (Recipe<?> recipe : manager.getAllRecipesFor(RecipeType.CRAFTING)) {
            if (!(recipe instanceof CraftingRecipe crafting)) {
                continue;
            }
            RecipeNode node = toNode(crafting, registryAccess);
            if (node != null) {
                nodes.add(node);
            }
        }
        return ImmutableRecipeGraph.build(nodes);
    }

    private static RecipeNode toNode(CraftingRecipe crafting, RegistryAccess registryAccess) {
        ResourceLocation recipeId = crafting.getId();
        if (recipeId == null) {
            return null;
        }
        ItemStack result = crafting.getResultItem(registryAccess);
        if (result.isEmpty()) {
            return null;
        }
        ResourceLocation outputKey = ForgeRegistries.ITEMS.getKey(result.getItem());
        if (outputKey == null) {
            return null;
        }
        if (isBlacklisted(outputKey.toString())) {
            return null;   // 黑名单产出配方整体剔除
        }
        // 合并相同 alternatives 的多个槽位（合成桌 3x3 网格常放多份同物品），
        // tag 槽位（alternatives 多元素）天然不同，不会合并。
        List<IngredientRef> inputs = new ArrayList<>();
        Map<List<MaterialRef>, Integer> merged = new LinkedHashMap<>();
        for (Ingredient ingredient : crafting.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;   // 空格子（如 3x3 配方留空位）
            }
            Set<MaterialRef> alternatives = new LinkedHashSet<>();
            int perSlot = 0;
            for (ItemStack stack : ingredient.getItems()) {
                if (stack.isEmpty()) {
                    continue;
                }
                ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (key != null && !isBlacklisted(key.toString())) {
                    alternatives.add(MaterialRef.of(key.toString()));
                    if (perSlot == 0) {
                        perSlot = stack.getCount();   // 槽位每份数量（通常 1）
                    }
                }
            }
            if (alternatives.isEmpty() || perSlot == 0) {
                continue;
            }
            merged.merge(List.copyOf(alternatives), perSlot, Integer::sum);
        }
        for (var entry : merged.entrySet()) {
            inputs.add(new IngredientRef(entry.getKey(), entry.getValue()));
        }
        return new RecipeNode(recipeId.toString(), MaterialRef.of(outputKey.toString()),
                result.getCount(), inputs);
    }

    private static boolean isBlacklisted(String itemId) {
        return Config.blacklistItems().contains(itemId);
    }

    private static void countStacks(NonNullList<ItemStack> stacks, Map<MaterialRef, Integer> stock) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (key == null) {
                continue;
            }
            MaterialRef ref = MaterialRef.of(key.toString());
            stock.merge(ref, stack.getCount(), Integer::sum);
        }
    }
}
