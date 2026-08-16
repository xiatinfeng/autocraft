package com.adimn.autocraft.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

    /** 树节点：material 物品 + amount 需求量 + 产出配方(叶子为 null) + 子节点 + 状态 + NBT 需求 + 是否催化剂。 */
    public record TreeNode(MaterialRef material, long amount, String recipeId,
                           List<TreeNode> children, State state, String requirementText,
                           boolean catalyst) {
        /** 兼容旧调用：无 NBT 需求、非催化剂。 */
        public TreeNode(MaterialRef material, long amount, String recipeId,
                        List<TreeNode> children, State state) {
            this(material, amount, recipeId, children, state, "", false);
        }

        /** 兼容旧调用：有 NBT 需求、非催化剂。 */
        public TreeNode(MaterialRef material, long amount, String recipeId,
                        List<TreeNode> children, State state, String requirementText) {
            this(material, amount, recipeId, children, state, requirementText, false);
        }

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
        return build(graph, target, count, stock, chosenRecipes, Set.of());
    }

    /** 带“按材料展开备选配方”集合的构建入口（交互式点击展开用）。 */
    public static TreeNode build(ImmutableRecipeGraph graph, MaterialRef target, int count,
                                 Map<MaterialRef, Integer> stock,
                                 Set<String> chosenRecipes, Set<String> expandedMaterials) {
        LinkedHashSet<MaterialRef> ancestry = new LinkedHashSet<>();
        Map<MaterialRef, TreeNode> catalystCache = new HashMap<>();
        TreeNode root = expand(graph, target, Math.max(1, count), stock,
                chosenRecipes == null ? Set.of() : chosenRecipes, ancestry, 0, "", false,
                catalystCache, expandedMaterials == null ? Set.of() : expandedMaterials);
        return root;
    }

    /**
     * 汇总总耗材：普通叶子按需求量累加；催化剂不累加，
     * 而是按“每种催化剂在所有分支中的最大持有量”去重（持有 1 个不消耗）。
     * 催化剂的下级配方材料仍按普通原料正常累加。
     */
    public static Map<MaterialRef, Long> totalLeafDemand(TreeNode root) {
        Map<MaterialRef, Long> totals = new LinkedHashMap<>();
        Map<MaterialRef, Long> catalystMax = new HashMap<>();
        collect(root, totals, catalystMax);
        catalystMax.forEach((material, amount) -> totals.merge(material, amount, Long::sum));
        return totals;
    }

    // ------------------------------------------------------------------

    private static TreeNode expand(ImmutableRecipeGraph graph, MaterialRef material, long need,
                                   Map<MaterialRef, Integer> stock, Set<String> chosenRecipes,
                                   LinkedHashSet<MaterialRef> ancestry, int depth,
                                   String requirementText, boolean catalyst,
                                   Map<MaterialRef, TreeNode> catalystCache,
                                   Set<String> expandedMaterials) {
        if (depth > MAX_DEPTH_LIMIT) {
            return new TreeNode(material, need, null, List.of(),
                    inStock(material, need, stock) ? State.HAS : State.MISSING, requirementText,
                    catalyst);
        }
        int have = stock.getOrDefault(material, 0);
        boolean stockSatisfies = have >= need;
        List<RecipeNode> candidates = graph.recipesFor(material);
        if (candidates.isEmpty()) {
            // 无配方：库存满足 → HAS；有部分 → PARTIAL；否则 MISSING
            return new TreeNode(material, need, null, List.of(),
                    stockSatisfies ? State.HAS : (have > 0 ? State.PARTIAL : State.MISSING),
                    requirementText, catalyst);
        }
        // 环保护：同一祖先链上已出现 → 视为叶子（避免 A→B→A 无限展开）
        if (ancestry.contains(material)) {
            return new TreeNode(material, need, candidates.get(0).recipeId(), List.of(),
                    stockSatisfies ? State.HAS : (have > 0 ? State.PARTIAL : State.MISSING),
                    requirementText, catalyst);
        }
        long effectiveNeed = stockSatisfies ? need : need - have;
        boolean expandAllHere = expandedMaterials != null
                && expandedMaterials.contains(material.itemId());
        // 该材料被点击展开：每个配方展开成一个同物品的“配方分支”子节点
        if (expandAllHere && candidates.size() > 1) {
            ancestry.add(material);
            List<TreeNode> recipeBranches = new ArrayList<>(candidates.size());
            for (RecipeNode cand : candidates) {
                List<TreeNode> children = expandRecipeInputs(graph, cand, effectiveNeed, stock,
                        chosenRecipes, ancestry, depth, catalystCache, expandedMaterials);
                recipeBranches.add(new TreeNode(material, need, cand.recipeId(), children,
                        stockSatisfies ? State.HAS : State.CRAFT, requirementText, catalyst));
            }
            ancestry.remove(material);
            return new TreeNode(material, need, null, recipeBranches,
                    stockSatisfies ? State.HAS : State.CRAFT, requirementText, catalyst);
        }
        // 中间产物已经足够：默认不再展开它的原材料，直接显示为绿色叶子。
        // 如果玩家手动点击展开（expandedMaterials），仍会走上面的分支展示完整配方。
        if (stockSatisfies) {
            return new TreeNode(material, need, null, List.of(),
                    State.HAS, requirementText, catalyst);
        }
        // 主配方路径：库存不足时展开配方，只显示缺失的原材料
        RecipeNode recipe = pickRecipe(graph, material, chosenRecipes, need);
        ancestry.add(material);
        List<TreeNode> children = expandRecipeInputs(graph, recipe, effectiveNeed, stock,
                chosenRecipes, ancestry, depth, catalystCache, expandedMaterials);
        ancestry.remove(material);
        return new TreeNode(material, need, recipe.recipeId(), children,
                stockSatisfies ? State.HAS : State.CRAFT, requirementText, catalyst);
    }

    /** 展开某个配方的输入/催化剂，返回子节点列表。 */
    private static List<TreeNode> expandRecipeInputs(ImmutableRecipeGraph graph, RecipeNode recipe,
            long effectiveNeed, Map<MaterialRef, Integer> stock, Set<String> chosenRecipes,
            LinkedHashSet<MaterialRef> ancestry, int depth,
            Map<MaterialRef, TreeNode> catalystCache, Set<String> expandedMaterials) {
        long batches = batchesFor(effectiveNeed, recipe.outputCount());
        if (batches <= 0) {
            return List.of();
        }
        List<TreeNode> children = new ArrayList<>(recipe.inputs().size() + recipe.catalysts().size());
        for (IngredientRef input : recipe.inputs()) {
            MaterialRef alt = pickAlternative(input, stock);
            long scaled = saturatingMul(input.count(), batches);
            children.add(expand(graph, alt, scaled, stock, chosenRecipes, ancestry, depth + 1,
                    input.requirementText(), false, catalystCache, expandedMaterials));
        }
        for (IngredientRef cat : recipe.catalysts()) {
            MaterialRef alt = pickAlternative(cat, stock);
            // 和 EMI 一样：每个路径都生成独立节点，避免共享节点导致布局 DAG 悬空连线。
            children.add(expand(graph, alt, cat.count(), stock, chosenRecipes, ancestry,
                    depth + 1, cat.requirementText(), true, catalystCache, expandedMaterials));
        }
        return children;
    }

    /**
     * 选产出配方：chosenRecipes 命中优先；否则用兜底启发式：
     * 优先输出数量不超过需求量的配方（避免“做 1 个却走 9 合 1/1 拆 9”的过量生产），
     * 同档优先输入种类更多者（更可能是真实合成/铁砧配方，而不是方块-锭互转）。
     * 都不满足时退回第一个（与规划器枚举序一致）。
     */
    private static RecipeNode pickRecipe(ImmutableRecipeGraph graph, MaterialRef material,
                                         Set<String> chosenRecipes, long need) {
        List<RecipeNode> candidates = graph.recipesFor(material);
        if (candidates.isEmpty()) {
            return null;
        }
        RecipeNode chosen = null;
        for (RecipeNode candidate : candidates) {
            if (chosenRecipes.contains(candidate.recipeId())) {
                chosen = candidate;
                break;
            }
        }
        if (chosen != null) {
            // 树是展示层：若规划器选中的是互转环配方（如 锭⇄块），
            // 但存在非互转环直合配方，优先展示直合配方，避免循环树。
            if (!isRoundTripRecipe(graph, chosen, material)) {
                return chosen;
            }
            for (RecipeNode candidate : candidates) {
                if (!isRoundTripRecipe(graph, candidate, material)) {
                    return candidate;
                }
            }
            return chosen;
        }
        RecipeNode fallback = candidates.get(0);
        RecipeNode best = null;
        int bestScore = -1;
        for (RecipeNode candidate : candidates) {
            int score = 0;
            if (!isRoundTripRecipe(graph, candidate, material)) {
                score += 1000;
            }
            if (candidate.outputCount() <= need) {
                score += 100;
            }
            score += candidate.inputs().size();
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best != null ? best : fallback;
    }

    /**
     * 互转环检测（可达性）：配方的某个输入 alt 通过配方图能“回到” output 就判定为环。
     * 例如 永恒锭→永恒块→永恒锭 这类多步互转也会被识别，而不只是 2 步自耗。
     * 带深度上限防止大图爆炸。
     */
    private static boolean isRoundTripRecipe(ImmutableRecipeGraph graph, RecipeNode candidate,
                                             MaterialRef output) {
        for (IngredientRef input : candidate.inputs()) {
            for (MaterialRef alt : input.alternatives()) {
                if (alt.equals(output)) {
                    continue;
                }
                if (canReach(graph, alt, output, new HashSet<>(), 0)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 从 from 出发，沿配方输入反向能否到达 target（深度上限 8）。 */
    private static boolean canReach(ImmutableRecipeGraph graph, MaterialRef from, MaterialRef target,
                                    Set<MaterialRef> visited, int depth) {
        if (from.equals(target)) {
            return true;
        }
        if (depth >= 8 || !visited.add(from)) {
            return false;
        }
        for (RecipeNode recipe : graph.recipesFor(from)) {
            for (IngredientRef input : recipe.inputs()) {
                for (MaterialRef alt : input.alternatives()) {
                    if (canReach(graph, alt, target, visited, depth + 1)) {
                        return true;
                    }
                }
            }
        }
        return false;
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

    private static void collect(TreeNode node, Map<MaterialRef, Long> totals,
                                Map<MaterialRef, Long> catalystMax) {
        if (node.catalyst()) {
            // 催化剂本身不消耗：同种催化剂全树只取最大持有量。
            catalystMax.merge(node.material(), node.amount(), Math::max);
        }
        if (node.children() == null || node.children().isEmpty()) {
            if (!node.catalyst()) {
                totals.merge(node.material(), node.amount(), Long::sum);
            }
            return;
        }
        if (node.state() == State.HAS) {
            // Already own this intermediate/catalyst: do not count its sub-materials in totals.
            return;
        }
        for (TreeNode child : node.children()) {
            collect(child, totals, catalystMax);
        }
    }
}
