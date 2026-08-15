package com.adimn.autocraft.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自写配方树（EMI-free，M6）：目标物品 → 中间产物 → 原材料的层次结构。
 *
 * 语义 = EMI MaterialTree 的展示层（树 = "需要什么"），与规划器 steps
 * （"按什么顺序做"）解耦：
 *   - 从目标 MaterialRef 递归展开：每个节点选一个产出配方（优先规划器
 *     实际选中的 recipeId），输入变成子节点，直到叶子（库存满足或无配方）。
 *   - 节点状态：HAS(库存够) / PARTIAL(部分够) / MISSING(无配方且库存缺) /
 *     CRAFT(需生产，有配方)。
 *   - 数量按"需要 N 个 → 配方做 ceil(N/outputCount) 批 → 每输入 count×批次"
 *     向下传播；叶子节点另汇总"总耗材"（Map&lt;MaterialRef, Long&gt;）。
 *   - 环保护：ancestry 路径去重（同一祖先链上已出现的物品不再展开）；
 *     深度上限 64；总量 clamp 2^31。
 *
 * 纯 Java 值对象：零 MC 依赖，可离线单测（PlanSelfTest 扩展）。
 */
public final class PlanTree {

    /** 节点库存/可生产状态。 */
    public enum State {
        HAS,        // 库存满足（叶子）
        PARTIAL,    // 库存部分满足，仍需生产差额
        MISSING,    // 无产出配方且库存不足（叶子）
        CRAFT       // 有产出配方，需生产
    }

    /** 树节点：material 物品 + amount 需求量 + 产出配方(叶子为 null) + 子节点 + 状态。 */
    public record TreeNode(MaterialRef material, long amount, String recipeId,
                           List<TreeNode> children, State state) {
        /** 展示用短名：去掉 modid 前缀（如 "minecraft:oak_log" → "oak_log"）。 */
        public String itemName() {
            String id = material.itemId();
            int colon = id.indexOf(':');
            return colon >= 0 ? id.substring(colon + 1) : id;
        }
    }

    /** 树展开深度上限（环保护兜底；超出后按叶子处理，不无限递归）。 */
    public static final int MAX_DEPTH_LIMIT = 64;

    private PlanTree() {}

    /**
     * 构建配方树。
     *
     * @param graph          配方图（RecipeIndex 缓存实例）
     * @param target         目标物品
     * @param count          目标数量
     * @param stock          玩家背包快照（RecipeIndex.snapshotInventory）
     * @param chosenRecipes  规划器实际选中的 recipeId 集合（优先选择；空集合则用 recipesFor 第一个）
     */
    public static TreeNode build(ImmutableRecipeGraph graph, MaterialRef target, int count,
                                 Map<MaterialRef, Integer> stock,
                                 Set<String> chosenRecipes) {
        LinkedHashSet<MaterialRef> ancestry = new LinkedHashSet<>();
        TreeNode root = expand(graph, target, Math.max(1, count), stock,
                chosenRecipes == null ? Set.of() : chosenRecipes, ancestry, 0);
        return root;
    }

    /**
     * 汇总叶子（原材料/库存满足物）总耗材：Map&lt;MaterialRef, Long&gt;。
     * 所有叶子（无子节点）按需求量计入——无论 HAS/PARTIAL/MISSING，
     * 语义 = "合成这棵树总共需要多少材料"（EMI 总耗材同义）。
     */
    public static Map<MaterialRef, Long> totalLeafDemand(TreeNode root) {
        Map<MaterialRef, Long> totals = new LinkedHashMap<>();
        collect(root, totals);
        return totals;
    }

    // ------------------------------------------------------------------

    private static TreeNode expand(ImmutableRecipeGraph graph, MaterialRef material, long need,
                                   Map<MaterialRef, Integer> stock, Set<String> chosenRecipes,
                                   LinkedHashSet<MaterialRef> ancestry, int depth) {
        if (depth > MAX_DEPTH_LIMIT) {
            return new TreeNode(material, need, null, List.of(),
                    inStock(material, need, stock) ? State.HAS : State.MISSING);
        }
        int have = stock.getOrDefault(material, 0);
        if (have >= need) {
            return new TreeNode(material, need, null, List.of(), State.HAS);   // 库存满足
        }
        // 选产出配方：优先规划器选中；否则第一个（与规划器枚举序一致）
        RecipeNode recipe = pickRecipe(graph, material, chosenRecipes);
        if (recipe == null) {
            // 无配方：库存有部分 → PARTIAL，否则 MISSING
            return new TreeNode(material, need, null, List.of(),
                    have > 0 ? State.PARTIAL : State.MISSING);
        }
        // 环保护：同一祖先链上已出现 → 视为叶子（避免 A→B→A 无限展开）
        if (ancestry.contains(material)) {
            return new TreeNode(material, need, recipe.recipeId(), List.of(),
                    have > 0 ? State.PARTIAL : State.MISSING);
        }
        long batches = batchesFor(need - have, recipe.outputCount());
        if (batches <= 0) {
            return new TreeNode(material, need, recipe.recipeId(), List.of(),
                    have > 0 ? State.PARTIAL : State.MISSING);
        }
        // 展开输入
        ancestry.add(material);
        List<TreeNode> children = new ArrayList<>(recipe.inputs().size());
        for (IngredientRef input : recipe.inputs()) {
            // tag 多解：取"有库存的替代物"（否则第一个）——展示层用单物品行
            MaterialRef alt = pickAlternative(input, stock);
            long scaled = saturatingMul(input.count(), batches);
            children.add(expand(graph, alt, scaled, stock, chosenRecipes, ancestry, depth + 1));
        }
        ancestry.remove(material);
        // 本节点库存不足且有配方 → 一律 CRAFT（需生产；子节点颜色各自标注满足/缺失）
        return new TreeNode(material, need, recipe.recipeId(), List.copyOf(children), State.CRAFT);
    }

    /** 选产出配方：chosenRecipes 命中优先，否则 recipesFor 第一个（null=无配方）。 */
    private static RecipeNode pickRecipe(ImmutableRecipeGraph graph, MaterialRef material,
                                         Set<String> chosenRecipes) {
        List<RecipeNode> candidates = graph.recipesFor(material);
        if (candidates.isEmpty()) {
            return null;
        }
        for (RecipeNode candidate : candidates) {
            if (chosenRecipes.contains(candidate.recipeId())) {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    /** tag 替代物：优先返回有库存的（凑数展示更贴近玩家实际），否则第一个。 */
    private static MaterialRef pickAlternative(IngredientRef input, Map<MaterialRef, Integer> stock) {
        for (MaterialRef alt : input.alternatives()) {
            if (stock.getOrDefault(alt, 0) > 0) {
                return alt;
            }
        }
        return input.alternatives().get(0);
    }

    private static boolean inStock(MaterialRef material, long need, Map<MaterialRef, Integer> stock) {
        return stock.getOrDefault(material, 0) >= need;
    }

    /** 需要 needed 个、每批产出 outputCount 个 → 批次数（向上取整，至少 1）。 */
    private static long batchesFor(long needed, int outputCount) {
        if (outputCount <= 0) {
            return -1;
        }
        long batches = (needed + outputCount - 1L) / outputCount;
        return Math.min(batches, Integer.MAX_VALUE);
    }

    /** 饱和乘法：结果 clamp 到 2^31-1（展示层防溢出，Avaritia 类大数安全）。 */
    private static long saturatingMul(long a, long b) {
        if (a <= 0 || b <= 0) {
            return 0;
        }
        if (a > Integer.MAX_VALUE / b) {
            return Integer.MAX_VALUE;
        }
        return a * b;
    }

    private static void collect(TreeNode node, Map<MaterialRef, Long> totals) {
        if (node.children() == null || node.children().isEmpty()) {
            totals.merge(node.material(), node.amount(), Long::sum);
            return;
        }
        for (TreeNode child : node.children()) {
            collect(child, totals);
        }
    }
}
