package com.adimn.autocraft.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.adimn.autocraft.compat.CraftingGridAdapter;
import com.adimn.autocraft.compat.CraftingGridAdapters;
import com.adimn.autocraft.config.Config;
import com.adimn.autocraft.plan.ImmutableRecipeGraph;
import com.adimn.autocraft.plan.IngredientRef;
import com.adimn.autocraft.plan.MaterialRef;
import com.adimn.autocraft.plan.RecipeNode;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.common.ForgeHooks;
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
    private static volatile ImmutableRecipeGraph cachedCraftingGraph;
    private static volatile ImmutableRecipeGraph cachedAllGraph;

    private RecipeIndex() {}

    /**
     * 取当前“3×3 合成台可执行”配方图（版本化缓存）。
     * 旧入口别名，保持兼容：规划/执行只用可自动化配方。
     */
    public static ImmutableRecipeGraph graph(RecipeManager manager, RegistryAccess registryAccess) {
        return craftingGraph(manager, registryAccess);
    }

    /** 取当前“3×3 合成台可执行”配方图（版本化缓存：RecipeManager 实例变化时自动重建）。 */
    public static ImmutableRecipeGraph craftingGraph(RecipeManager manager, RegistryAccess registryAccess) {
        refreshIfNeeded(manager, registryAccess);
        return cachedCraftingGraph;
    }

    /**
     * 取当前“全配方”图（含铁砧/熔炉/锻造等特殊配方，仅用于预览树展示）。
     * 注意：这类配方不能由 CraftExecutor 在 3×3 合成台执行，规划/执行请用 craftingGraph。
     */
    public static ImmutableRecipeGraph allGraph(RecipeManager manager, RegistryAccess registryAccess) {
        refreshIfNeeded(manager, registryAccess);
        return cachedAllGraph;
    }

    private static void refreshIfNeeded(RecipeManager manager, RegistryAccess registryAccess) {
        if (manager != cachedManager) {
            cachedCraftingGraph = buildCrafting(manager, registryAccess);
            cachedAllGraph = buildAll(manager, registryAccess);
            cachedManager = manager;
        }
    }

    /** 玩家背包快照（主手/盔甲/副手全部计入）。 */
    public static Map<MaterialRef, Integer> snapshotInventory(Player player) {
        return snapshotInventory(player, null);
    }

    /**
     * 玩家背包快照 + 可选“当前打开容器”的来源槽快照。
     * 用于终端/精妙背包：规划时把背包/终端可用槽位也计入库存。
     */
    public static Map<MaterialRef, Integer> snapshotInventory(Player player,
                                                              AbstractContainerMenu openMenu) {
        Map<MaterialRef, Integer> stock = new HashMap<>();
        Inventory inv = player.getInventory();
        countStacks(inv.items, stock);
        countStacks(inv.armor, stock);
        countStacks(inv.offhand, stock);
        if (openMenu != null) {
            CraftingGridAdapter adapter = CraftingGridAdapters.find(openMenu);
            if (adapter != null) {
                try {
                    for (int slotId : adapter.getSourceSlotIds(openMenu)) {
                        if (slotId >= 0 && slotId < openMenu.slots.size()) {
                            countStack(openMenu.getSlot(slotId).getItem(), stock);
                        }
                    }
                    for (net.minecraft.world.item.ItemStack networkStack : adapter.getNetworkItemStacks(openMenu)) {
                        countStack(networkStack, stock);
                    }
                } catch (Throwable ignored) {
                    // 单个容器扫描失败不应影响玩家背包快照
                }
            }
        }
        return stock;
    }

    // ------------------------------------------------------------------

    private static ImmutableRecipeGraph buildCrafting(RecipeManager manager, RegistryAccess registryAccess) {
        List<RecipeNode> nodes = new ArrayList<>();
        for (Recipe<?> recipe : manager.getAllRecipesFor(RecipeType.CRAFTING)) {
            if (!(recipe instanceof CraftingRecipe crafting)) {
                continue;
            }
            try {
                RecipeNode node = toNode(recipe, registryAccess);
                if (node != null) {
                    nodes.add(node);
                }
            } catch (Exception ignored) {
                // 单个异常配方不应拖垮整个索引
            }
        }
        return ImmutableRecipeGraph.build(nodes);
    }

    /** 全配方图：RecipeManager.getRecipes() 返回所有已注册配方类型（含 mod 自定义铁砧/机器配方）。 */
    private static ImmutableRecipeGraph buildAll(RecipeManager manager, RegistryAccess registryAccess) {
        List<RecipeNode> nodes = new ArrayList<>();
        for (Recipe<?> recipe : manager.getRecipes()) {
            try {
                RecipeNode node = toNode(recipe, registryAccess);
                if (node != null) {
                    nodes.add(node);
                }
            } catch (Exception ignored) {
                // 单个异常配方（含 mod 特殊配方）不应拖垮整个索引
            }
        }
        return ImmutableRecipeGraph.build(nodes);
    }

    /**
     * 把任意 Recipe 转成纯值 RecipeNode。
     * 仅产出、输入都有效且输入非空的配方才进图（空输入=免费产出，防规划器误用）。
     */
    private static RecipeNode toNode(Recipe<?> recipe, RegistryAccess registryAccess) {
        ResourceLocation recipeId = recipe.getId();
        if (recipeId == null) {
            return null;
        }
        ItemStack result = recipe.getResultItem(registryAccess);
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
        // 可复用工具/催化剂（Forge 剩余物品=同物品）单独放入 catalysts，不参与消耗计算。
        List<IngredientRef> inputs = new ArrayList<>();
        List<IngredientRef> catalysts = new ArrayList<>();
        Map<List<MaterialRef>, Integer> inputsMerged = new LinkedHashMap<>();
        Map<List<MaterialRef>, String> inputsReq = new HashMap<>();
        Map<List<MaterialRef>, Integer> catalystsMerged = new LinkedHashMap<>();
        Map<List<MaterialRef>, String> catalystsReq = new HashMap<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;   // 空格子（如 3x3 配方留空位）
            }
            Set<MaterialRef> alternatives = new LinkedHashSet<>();
            int perSlot = 0;
            String requirementText = "";
            boolean slotReusable = true;
            for (ItemStack stack : ingredient.getItems()) {
                if (stack.isEmpty()) {
                    continue;
                }
                MaterialRef ref = materialRefOf(stack);
                if (ref != null && !isBlacklisted(ref.itemId())) {
                    alternatives.add(ref);
                    if (perSlot == 0) {
                        perSlot = stack.getCount();   // 槽位每份数量（通常 1）
                    }
                    if (requirementText.isEmpty()) {
                        requirementText = nbtRequirementText(stack);
                    }
                    if (!isReusable(stack)) {
                        slotReusable = false;
                    }
                }
            }
            if (alternatives.isEmpty() || perSlot == 0) {
                continue;
            }
            List<MaterialRef> key = List.copyOf(alternatives);
            if (slotReusable) {
                catalystsMerged.merge(key, perSlot, Integer::sum);
                catalystsReq.putIfAbsent(key, requirementText);
            } else {
                inputsMerged.merge(key, perSlot, Integer::sum);
                inputsReq.putIfAbsent(key, requirementText);
            }
        }
        if (inputsMerged.isEmpty() && catalystsMerged.isEmpty()) {
            return null;   // 无输入配方（特殊/动态配方）不能进图
        }
        for (var entry : inputsMerged.entrySet()) {
            inputs.add(new IngredientRef(entry.getKey(), entry.getValue(),
                    inputsReq.getOrDefault(entry.getKey(), ""), false));
        }
        for (var entry : catalystsMerged.entrySet()) {
            catalysts.add(new IngredientRef(entry.getKey(), entry.getValue(),
                    catalystsReq.getOrDefault(entry.getKey(), ""), true));
        }
        return new RecipeNode(recipeId.toString(), MaterialRef.of(outputKey.toString()),
                result.getCount(), inputs, catalysts, methodOf(recipe));
    }

    /** 根据 RecipeType 判断加工方式（用于预览树标注“需手动加工”的节点）。 */
    private static String methodOf(Recipe<?> recipe) {
        RecipeType<?> type = recipe.getType();
        if (type == RecipeType.CRAFTING) {
            return "crafting";
        } else if (type == RecipeType.SMELTING) {
            return "furnace";
        } else if (type == RecipeType.BLASTING) {
            return "blast_furnace";
        } else if (type == RecipeType.SMOKING) {
            return "smoker";
        } else if (type == RecipeType.CAMPFIRE_COOKING) {
            return "campfire";
        } else if (type == RecipeType.STONECUTTING) {
            return "stonecutter";
        } else if (type == RecipeType.SMITHING) {
            return "smithing_table";
        } else {
            return "manual";
        }
    }

    /**
     * 可复用工具/催化剂检测：Forge 剩余物品接口返回与输入同物品的非空栈，
     * 即该槽位不消耗（如贤者之石、扳手）。
     */
    private static boolean isReusable(ItemStack stack) {
        try {
            ItemStack remaining = ForgeHooks.getCraftingRemainingItem(stack);
            return !remaining.isEmpty() && remaining.getItem() == stack.getItem();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isBlacklisted(String itemId) {
        return Config.blacklistItems().contains(itemId);
    }

    private static void countStacks(NonNullList<ItemStack> stacks, Map<MaterialRef, Integer> stock) {
        for (ItemStack stack : stacks) {
            countStack(stack, stock);
        }
    }

    private static void countStack(ItemStack stack, Map<MaterialRef, Integer> stock) {
        if (stack.isEmpty()) {
            return;
        }
        MaterialRef ref = materialRefOf(stack);
        if (ref != null) {
            stock.merge(ref, stack.getCount(), Integer::sum);
        }
    }

    // ------------------------------------------------------------------
    // NBT 指纹（匹配）与 NBT 需求展示（只读，不 hash 大 NBT）
    // ------------------------------------------------------------------

    private static final String VANILLA_ENCHANTED_BOOK = "minecraft:enchanted_book";

    /** 展示层忽略的易变/渲染类 NBT 键（不参与需求文字，也不参与指纹）。 */
    private static final Set<String> NBT_DISPLAY_SKIP_KEYS = Set.of(
            "display", "Model", "Texture", "Render", "UUID", "ForgeCaps",
            "Capabilities", "Palette", "HideFlags", "CustomModelData");

    /** 物品 → MaterialRef；白名单物品（原版附魔书）且开关开启时附加 NBT 指纹。 */
    private static MaterialRef materialRefOf(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) {
            return null;
        }
        String nbt = Config.nbtFingerprintEnabled() ? nbtFingerprint(stack) : null;
        return (nbt == null || nbt.isEmpty())
                ? MaterialRef.of(key.toString())
                : new MaterialRef(key.toString(), nbt);
    }

    /**
     * 原版附魔书 NBT 指纹：StoredEnchantments 归一化排序后生成轻量签名。
     * 计算始终便宜，可用于展示；是否用于库存匹配由 Config.nbtFingerprintEnabled 控制。
     */
    private static String nbtFingerprint(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null || !key.toString().equals(VANILLA_ENCHANTED_BOOK)) {
            return null;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("StoredEnchantments", Tag.TAG_LIST)) {
            return null;
        }
        ListTag list = tag.getList("StoredEnchantments", Tag.TAG_COMPOUND);
        List<String> ench = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String id = entry.getString("id");
            int lvl = entry.getInt("lvl");
            if (!id.isEmpty()) {
                ench.add(id + ":" + lvl);
            }
        }
        if (ench.isEmpty()) {
            return null;
        }
        Collections.sort(ench);
        return "ench=" + String.join(",", ench);
    }

    /**
     * 展示用 NBT 需求文字：附魔书走指纹；其他物品只读非渲染类标量/小列表，
     * 不做完整 hash，避免拔刀剑等大 NBT 负担。
     */
    private static String nbtRequirementText(ItemStack stack) {
        String fingerprint = nbtFingerprint(stack);
        if (fingerprint != null) {
            return fingerprint;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || tag.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String key : tag.getAllKeys()) {
            if (NBT_DISPLAY_SKIP_KEYS.contains(key)) {
                continue;
            }
            Tag value = tag.get(key);
            if (value instanceof NumericTag num) {
                parts.add(key + "=" + num.getAsInt());
            } else if (value instanceof StringTag str) {
                parts.add(key + "=" + str.getAsString());
            } else if (value instanceof ListTag list && list.size() <= 8) {
                parts.add(key + "=" + list);
            }
        }
        return String.join(", ", parts);
    }
}
