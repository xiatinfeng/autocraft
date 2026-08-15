package com.adimn.autocraft.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.adimn.autocraft.plan.PureSearchPlanner.PlannedStep;
import com.adimn.autocraft.plan.PureSearchPlanner.Result;
import com.adimn.autocraft.plan.PureSearchPlanner.Status;

/**
 * M1 离线自测运行器（纯 Java，零 JUnit / 零 MC 依赖）。
 *
 * 运行方式（JDK 17）：
 *   1) ./gradlew compileJava --offline
 *   2) java -cp build/classes/java/main com.adimn.autocraft.plan.PlanSelfTest
 *
 * 覆盖 DESIGN-v2 §3.8 / HANDOFF §4.3 的 5 个必测场景 + 图不变量：
 *   多层 / 多配方 / 环 / 缺失 / 护栏（+ 图构建与不可变性）
 * 全绿打印 PASS，任一失败打印 FAIL 详情并以退出码 1 结束。
 */
public final class PlanSelfTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== autocraft M1 离线自测 ===");
        testGraphInvariants();
        testMultiLayerLogToPickaxe();
        testMultiRecipeChest();
        testRings();
        testMissingMaterial();
        testGuards();
        testPlanTree();
        testDirectOnly();
        System.out.println("----------------------------------------");
        System.out.printf("结果：%d PASS / %d FAIL%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 测试 0：图构建与不可变性
    // ------------------------------------------------------------------

    private static void testGraphInvariants() {
        System.out.println("\n[T0] 图构建 / 索引 / 不可变性");
        List<RecipeNode> nodes = new ArrayList<>();
        nodes.add(recipe("r_log", "minecraft:oak_log", "minecraft:oak_planks", 4,
                ing(MaterialRef.of("minecraft:oak_log"), 1)));
        nodes.add(recipe("r_plank", "minecraft:oak_planks", "minecraft:stick", 4,
                ing(MaterialRef.of("minecraft:oak_planks"), 2)));

        ImmutableRecipeGraph graph = ImmutableRecipeGraph.build(nodes);
        check("recipesByOutput 索引：oak_log 无产出配方（原料）",
                graph.recipesFor(MaterialRef.of("minecraft:oak_log")).isEmpty());
        // r_log 产出 oak_planks、r_plank 产出 stick，各自 1 个
        check("recipesByOutput 索引：oak_planks 有 1 个产出配方",
                graph.recipesFor(MaterialRef.of("minecraft:oak_planks")).size() == 1);
        check("recipesByOutput 索引：stick 有 1 个产出配方",
                graph.recipesFor(MaterialRef.of("minecraft:stick")).size() == 1);
        check("recipesById 索引 r_log", graph.recipeById("r_log") != null);
        check("recipesById 未知 id 返回 null", graph.recipeById("nope") == null);
        check("空输出返回空表", graph.recipesFor(MaterialRef.of("minecraft:dirt")).isEmpty());

        // 不可变性：构建后改动原节点集合不影响图（r_evil 是构建后追加的，不应出现在图里）
        nodes.add(recipe("r_evil", "minecraft:oak_log", "minecraft:dirt", 1,
                ing(MaterialRef.of("minecraft:oak_log"), 1)));
        check("build 后外部节点集合改动不影响图",
                graph.recipesFor(MaterialRef.of("minecraft:oak_planks")).size() == 1
                        && graph.recipesFor(MaterialRef.of("minecraft:dirt")).isEmpty());
        // recipesFor 返回的是不可变副本，外部尝试修改应抛 UnsupportedOperationException
        boolean immutable = false;
        try {
            graph.recipesFor(MaterialRef.of("minecraft:oak_planks")).clear();
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        check("recipesFor 返回不可变副本", immutable);

        // 非法输入拒绝
        boolean threw = false;
        try {
            new MaterialRef("  ", null);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check("空 itemId 被拒绝", threw);
        threw = false;
        try {
            new IngredientRef(List.of(), 1);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check("空 alternatives 被拒绝", threw);
        threw = false;
        try {
            new RecipeNode("r", MaterialRef.of("minecraft:dirt"), 0, List.of());
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check("outputCount<=0 被拒绝", threw);
    }

    // ------------------------------------------------------------------
    // 测试 1：多层（原木 → 木板 → 木棍 → 木稿）
    // ------------------------------------------------------------------

    private static void testMultiLayerLogToPickaxe() {
        System.out.println("\n[T1] 多层：oak_log -> plank -> stick -> wooden_pickaxe");
        // 配方：1 log → 4 plank；2 plank → 4 stick；2 stick + 1 plank → 1 pickaxe
        RecipeNode rLog = recipe("r_log", "minecraft:oak_log", "minecraft:oak_planks", 4,
                ing(MaterialRef.of("minecraft:oak_log"), 1));
        RecipeNode rStick = recipe("r_stick", "minecraft:oak_planks", "minecraft:stick", 4,
                ing(MaterialRef.of("minecraft:oak_planks"), 2));
        RecipeNode rPickaxe = recipe("r_pickaxe", "minecraft:stick", "minecraft:wooden_pickaxe", 1,
                ing(MaterialRef.of("minecraft:stick"), 2),
                ing(MaterialRef.of("minecraft:oak_planks"), 1));
        ImmutableRecipeGraph graph = ImmutableRecipeGraph.build(
                List.of(rLog, rStick, rPickaxe));

        Map<MaterialRef, Integer> stock = new LinkedHashMap<>();
        stock.put(MaterialRef.of("minecraft:oak_log"), 16);
        Result result = PureSearchPlanner.resolve(graph, stock,
                roots(MaterialRef.of("minecraft:wooden_pickaxe"), 1));

        check("T1 可行", result.feasible());
        check("T1 status=SUCCESS", result.status() == Status.SUCCESS);
        check("T1 3 个执行步骤", result.steps().size() == 3);
        if (result.steps().size() == 3) {
            check("T1 步骤顺序 [r_log, r_stick, r_pickaxe]",
                    result.steps().get(0).recipeId().equals("r_log")
                            && result.steps().get(1).recipeId().equals("r_stick")
                            && result.steps().get(2).recipeId().equals("r_pickaxe"));
            check("T1 每步批次均为 1",
                    result.steps().stream().allMatch(step -> step.batches() == 1));
        }
        // 数量传播校验（设计文档 §5 示例的 remaining 数值为示意，此处按真实扣账断言）：
        // 1 log 扣 1 → 产 4 plank，需求 plank×1 抵扣 1 → 剩 3；stick 消耗 2 plank → 剩 1；
        // 产 4 stick，需求 stick×2 抵扣 2 → 剩 2
        check("T1 剩余 log=15",
                stockAfter(result).getOrDefault(MaterialRef.of("minecraft:oak_log"), 0) == 15);
        check("T1 剩余 plank=1",
                stockAfter(result).getOrDefault(MaterialRef.of("minecraft:oak_planks"), 0) == 1);
        check("T1 剩余 stick=2",
                stockAfter(result).getOrDefault(MaterialRef.of("minecraft:stick"), 0) == 2);
    }

    // ------------------------------------------------------------------
    // 测试 2：多配方（箱子 N 种木板组合，缺一种自动换另一种）
    // ------------------------------------------------------------------

    private static void testMultiRecipeChest() {
        System.out.println("\n[T2] 多配方：chest（oak 或 spruce 木板组合）");
        RecipeNode rOak = recipe("r_chest_oak", "minecraft:oak_planks", "minecraft:chest", 1,
                ing(MaterialRef.of("minecraft:oak_planks"), 8));
        RecipeNode rSpruce = recipe("r_chest_spruce", "minecraft:spruce_planks", "minecraft:chest", 1,
                ing(MaterialRef.of("minecraft:spruce_planks"), 8));
        ImmutableRecipeGraph graph = ImmutableRecipeGraph.build(List.of(rOak, rSpruce));

        // 只有 spruce 木板 → 必须自动选 r_chest_spruce
        Map<MaterialRef, Integer> stock = new LinkedHashMap<>();
        stock.put(MaterialRef.of("minecraft:spruce_planks"), 8);
        Result result = PureSearchPlanner.resolve(graph, stock,
                roots(MaterialRef.of("minecraft:chest"), 1));

        check("T2 可行", result.feasible());
        check("T2 选中 spruce 配方",
                result.steps().size() == 1
                        && result.steps().get(0).recipeId().equals("r_chest_spruce"));
        check("T2 剩余 spruce=0",
                stockAfter(result).getOrDefault(MaterialRef.of("minecraft:spruce_planks"), 0) == 0);

        // 反向：只有 oak 木板
        Map<MaterialRef, Integer> stockOak = new LinkedHashMap<>();
        stockOak.put(MaterialRef.of("minecraft:oak_planks"), 8);
        Result resultOak = PureSearchPlanner.resolve(graph, stockOak,
                roots(MaterialRef.of("minecraft:chest"), 1));
        check("T2 反向选中 oak 配方",
                resultOak.feasible()
                        && resultOak.steps().get(0).recipeId().equals("r_chest_oak"));

        // 都没有 → 不可行
        Result resultNone = PureSearchPlanner.resolve(graph, Map.of(),
                roots(MaterialRef.of("minecraft:chest"), 1));
        check("T2 无原料不可行", !resultNone.feasible()
                && resultNone.status() == Status.UNRESOLVABLE);
    }

    // ------------------------------------------------------------------
    // 测试 3：环（等价互转 / 自耗净增益）
    // ------------------------------------------------------------------

    private static void testRings() {
        System.out.println("\n[T3] 环：A<->B 等价互转；无收益自耗；正收益自耗");
        // 3a：A→B、B→A 无库存 → 不无限递归，UNRESOLVABLE 且状态数极少
        RecipeNode rAB = recipe("r_ab", "minecraft:a", "minecraft:b", 1,
                ing(MaterialRef.of("minecraft:a"), 1));
        RecipeNode rBA = recipe("r_ba", "minecraft:b", "minecraft:a", 1,
                ing(MaterialRef.of("minecraft:b"), 1));
        ImmutableRecipeGraph cycle = ImmutableRecipeGraph.build(List.of(rAB, rBA));
        Result resultCycle = PureSearchPlanner.resolve(cycle, Map.of(),
                roots(MaterialRef.of("minecraft:a"), 1));
        check("T3a 环不可行且 UNRESOLVABLE", !resultCycle.feasible()
                && resultCycle.status() == Status.UNRESOLVABLE);
        check("T3a 环未爆炸（状态数 < 200）", resultCycle.expandedStates() < 200);
        check("T3a 缺失报告非空", !resultCycle.missing().isEmpty());

        // 3b：无收益自耗（2X→2X，netGain=0）→ no-gain 剪枝跳过，不产生步骤
        RecipeNode rNoGain = recipe("r_nogain", "minecraft:x", "minecraft:x", 2,
                ing(MaterialRef.of("minecraft:x"), 2));
        ImmutableRecipeGraph noGain = ImmutableRecipeGraph.build(List.of(rNoGain));
        Result resultNoGain = PureSearchPlanner.resolve(noGain, Map.of(),
                roots(MaterialRef.of("minecraft:x"), 1));
        check("T3b 无收益自耗不可行", !resultNoGain.feasible());
        check("T3b 未调度该配方（steps 为空）", resultNoGain.steps().isEmpty());

        // 3c：正收益自耗（2X→3X，netGain=1），库存 2、需求 10 → 可逐步放大达成
        RecipeNode rGain = recipe("r_gain", "minecraft:x", "minecraft:x", 3,
                ing(MaterialRef.of("minecraft:x"), 2));
        ImmutableRecipeGraph gain = ImmutableRecipeGraph.build(List.of(rGain));
        Map<MaterialRef, Integer> stock = new LinkedHashMap<>();
        stock.put(MaterialRef.of("minecraft:x"), 2);
        Result resultGain = PureSearchPlanner.resolve(gain, stock,
                roots(MaterialRef.of("minecraft:x"), 10));
        check("T3c 正收益自耗可行", resultGain.feasible());
        check("T3c 已调度配方且批次>0",
                !resultGain.steps().isEmpty()
                        && resultGain.steps().stream().allMatch(s -> s.batches() > 0));
    }

    // ------------------------------------------------------------------
    // 测试 4：缺失（背包缺中间料 → 不可行 + missing 报告）
    // ------------------------------------------------------------------

    private static void testMissingMaterial() {
        System.out.println("\n[T4] 缺失：只有 1 个 plank，缺 stick 原料");
        RecipeNode rLog = recipe("r_log", "minecraft:oak_log", "minecraft:oak_planks", 4,
                ing(MaterialRef.of("minecraft:oak_log"), 1));
        RecipeNode rStick = recipe("r_stick", "minecraft:oak_planks", "minecraft:stick", 4,
                ing(MaterialRef.of("minecraft:oak_planks"), 2));
        RecipeNode rPickaxe = recipe("r_pickaxe", "minecraft:stick", "minecraft:wooden_pickaxe", 1,
                ing(MaterialRef.of("minecraft:stick"), 2),
                ing(MaterialRef.of("minecraft:oak_planks"), 1));
        ImmutableRecipeGraph graph = ImmutableRecipeGraph.build(
                List.of(rLog, rStick, rPickaxe));

        Map<MaterialRef, Integer> stock = new LinkedHashMap<>();
        stock.put(MaterialRef.of("minecraft:oak_planks"), 1);
        Result result = PureSearchPlanner.resolve(graph, stock,
                roots(MaterialRef.of("minecraft:wooden_pickaxe"), 1));

        check("T4 不可行", !result.feasible());
        check("T4 UNRESOLVABLE", result.status() == Status.UNRESOLVABLE);
        check("T4 missing 非空", !result.missing().isEmpty());
        check("T4 无步骤产出", result.steps().isEmpty());
    }

    // ------------------------------------------------------------------
    // 测试 5：护栏（步骤上限 / 状态上限，优雅返回不崩）
    // ------------------------------------------------------------------

    private static void testGuards() {
        System.out.println("\n[T5] 护栏：STEP_LIMIT / SEARCH_LIMIT");
        // 5a：300 级链条（item0→item1→...→item299），maxSteps=64 → STEP_LIMIT
        List<RecipeNode> chainNodes = new ArrayList<>();
        for (int i = 0; i < 299; i++) {
            chainNodes.add(recipe("r_chain_" + i, "test:item_" + i, "test:item_" + (i + 1), 1,
                    ing(MaterialRef.of("test:item_" + i), 1)));
        }
        ImmutableRecipeGraph chain = ImmutableRecipeGraph.build(chainNodes);
        Map<MaterialRef, Integer> chainStock = new LinkedHashMap<>();
        chainStock.put(MaterialRef.of("test:item_0"), 1);
        Result stepLimited = PureSearchPlanner.resolve(chain, chainStock,
                roots(MaterialRef.of("test:item_299"), 1),
                64, PureSearchPlanner.MAX_SEARCH_STATES,
                PureSearchPlanner.DEFAULT_MAX_MEMOIZED_FAILURES, Long.MAX_VALUE);
        check("T5a 步骤上限 → STEP_LIMIT", stepLimited.status() == Status.STEP_LIMIT);
        check("T5a 不可行（步骤不足）", !stepLimited.feasible());

        // 5b：20 层 × 每层 3 个等价配方 → 3^20 分支，maxSearchStates=5000 → SEARCH_LIMIT
        List<RecipeNode> branchNodes = new ArrayList<>();
        for (int level = 0; level < 20; level++) {
            for (int alt = 0; alt < 3; alt++) {
                branchNodes.add(recipe("r_branch_" + level + "_" + alt,
                        "test:node_" + level, "test:node_" + (level + 1), 1,
                        ing(MaterialRef.of("test:node_" + level), 1)));
            }
        }
        ImmutableRecipeGraph branchy = ImmutableRecipeGraph.build(branchNodes);
        Result searchLimited = PureSearchPlanner.resolve(branchy, Map.of(),
                roots(MaterialRef.of("test:node_20"), 1),
                PureSearchPlanner.DEFAULT_MAX_STEPS, 5_000,
                PureSearchPlanner.DEFAULT_MAX_MEMOIZED_FAILURES, Long.MAX_VALUE);
        check("T5b 状态上限 → SEARCH_LIMIT", searchLimited.status() == Status.SEARCH_LIMIT);
        // 超限发生在第 maxSearchStates+1 次展开（先 ++ 再比较），故断言 ≤ 上限+1
        check("T5b 展开状态数 ≤ 上限+1", searchLimited.expandedStates() <= 5_001);
        check("T5b 优雅返回（无异常）", true);
    }

    // ------------------------------------------------------------------
    // 测试 6：PlanTree（自写配方树，EMI-free）
    // ------------------------------------------------------------------

    private static void testPlanTree() {
        System.out.println("\n[T6] PlanTree：多层树 / 库存状态 / 总耗材 / 环保护");
        // 配方：1 log → 4 plank；2 plank → 4 stick；2 stick + 1 plank → 1 pickaxe
        RecipeNode rLog = recipe("r_log", "minecraft:oak_log", "minecraft:oak_planks", 4,
                ing(MaterialRef.of("minecraft:oak_log"), 1));
        RecipeNode rStick = recipe("r_stick", "minecraft:oak_planks", "minecraft:stick", 4,
                ing(MaterialRef.of("minecraft:oak_planks"), 2));
        RecipeNode rPickaxe = recipe("r_pickaxe", "minecraft:stick", "minecraft:wooden_pickaxe", 1,
                ing(MaterialRef.of("minecraft:stick"), 2),
                ing(MaterialRef.of("minecraft:oak_planks"), 1));
        ImmutableRecipeGraph graph = ImmutableRecipeGraph.build(List.of(rLog, rStick, rPickaxe));

        // 6a：无库存 → 树完整展开，根 CRAFT，叶子 oak_log 两处 MISSING
        PlanTree.TreeNode root = PlanTree.build(graph,
                MaterialRef.of("minecraft:wooden_pickaxe"), 1, Map.of(), Set.of());
        check("T6a 根物品=pickaxe", root.material().itemId().equals("minecraft:wooden_pickaxe"));
        check("T6a 根状态=CRAFT", root.state() == PlanTree.State.CRAFT);
        check("T6a 根有配方", root.recipeId().equals("r_pickaxe"));
        check("T6a 根 2 个子节点（stick+plank）", root.children().size() == 2);
        // 总耗材：1 log→4 plank；stick 需 2 plank=1 批、pickaxe 需 1 plank=1 批 → 共 2 log
        Map<MaterialRef, Long> totals = PlanTree.totalLeafDemand(root);
        check("T6a 总耗材 oak_log=2",
                totals.getOrDefault(MaterialRef.of("minecraft:oak_log"), 0L) == 2L);

        // 6b：库存满 → 根 HAS（1 把镐已在手，无需展开）
        Map<MaterialRef, Integer> stock = new LinkedHashMap<>();
        stock.put(MaterialRef.of("minecraft:wooden_pickaxe"), 1);
        PlanTree.TreeNode root2 = PlanTree.build(graph,
                MaterialRef.of("minecraft:wooden_pickaxe"), 1, stock, Set.of());
        check("T6b 库存满足 → 根 HAS", root2.state() == PlanTree.State.HAS);
        check("T6b 无子节点", root2.children().isEmpty());

        // 6c：部分库存 → 有配方的中间节点仍 CRAFT；无配方原料部分满足才 PARTIAL
        Map<MaterialRef, Integer> stockPartial = new LinkedHashMap<>();
        stockPartial.put(MaterialRef.of("minecraft:stick"), 1);
        PlanTree.TreeNode root3 = PlanTree.build(graph,
                MaterialRef.of("minecraft:wooden_pickaxe"), 1, stockPartial, Set.of());
        check("T6c 部分库存仍 CRAFT", root3.state() == PlanTree.State.CRAFT);
        boolean stickCraft = hasNode(root3, "minecraft:stick", PlanTree.State.CRAFT);
        check("T6c stick 有配方 → CRAFT（非 PARTIAL）", stickCraft);
        // 无配方原料部分满足：目标 oak_log 需 3、库存 1 → PARTIAL
        PlanTree.TreeNode partialLeaf = PlanTree.build(graph,
                MaterialRef.of("minecraft:oak_log"), 3,
                Map.of(MaterialRef.of("minecraft:oak_log"), 1), Set.of());
        check("T6c 无配方原料部分满足 → PARTIAL",
                partialLeaf.state() == PlanTree.State.PARTIAL);

        // 6d：环保护（A→B→A 互转）不无限递归，能正常返回
        RecipeNode rAB = recipe("r_ab", "minecraft:a", "minecraft:b", 1,
                ing(MaterialRef.of("minecraft:a"), 1));
        RecipeNode rBA = recipe("r_ba", "minecraft:b", "minecraft:a", 1,
                ing(MaterialRef.of("minecraft:b"), 1));
        ImmutableRecipeGraph cycle = ImmutableRecipeGraph.build(List.of(rAB, rBA));
        PlanTree.TreeNode cycleRoot = PlanTree.build(cycle,
                MaterialRef.of("minecraft:a"), 1, Map.of(), Set.of());
        check("T6d 环上根可返回（不崩）", cycleRoot != null);
        check("T6d 环上节点有子节点但深度受限",
                cycleRoot.children().size() <= 1 && depthOf(cycleRoot) <= PlanTree.MAX_DEPTH_LIMIT);

        // 6e：chosenRecipes 优先——两个产出配方都给出，选中的那个被展开
        RecipeNode rAlt = recipe("r_pickaxe_alt", "minecraft:stick", "minecraft:wooden_pickaxe", 1,
                ing(MaterialRef.of("minecraft:stick"), 3));
        ImmutableRecipeGraph graphAlt = ImmutableRecipeGraph.build(List.of(rPickaxe, rAlt));
        PlanTree.TreeNode chosen = PlanTree.build(graphAlt,
                MaterialRef.of("minecraft:wooden_pickaxe"), 1, Map.of(), Set.of("r_pickaxe_alt"));
        check("T6e chosen 配方被优先选中", chosen.recipeId().equals("r_pickaxe_alt"));
    }

    // ------------------------------------------------------------------
    // 测试 7：allowCrossLayer=false（仅背包原料直合）
    // ------------------------------------------------------------------

    private static void testDirectOnly() {
        System.out.println("\n[T7] allowCrossLayer=false：仅背包原料直合");
        RecipeNode rLog = recipe("r_log", "minecraft:oak_log", "minecraft:oak_planks", 4,
                ing(MaterialRef.of("minecraft:oak_log"), 1));
        RecipeNode rStick = recipe("r_stick", "minecraft:oak_planks", "minecraft:stick", 4,
                ing(MaterialRef.of("minecraft:oak_planks"), 2));
        RecipeNode rPickaxe = recipe("r_pickaxe", "minecraft:stick", "minecraft:wooden_pickaxe", 1,
                ing(MaterialRef.of("minecraft:stick"), 2),
                ing(MaterialRef.of("minecraft:oak_planks"), 1));
        ImmutableRecipeGraph graph = ImmutableRecipeGraph.build(
                List.of(rLog, rStick, rPickaxe));

        // 7a：只有原木 → 直合模式不可行（木板/木棍需跨层生产）
        Map<MaterialRef, Integer> logOnly = new LinkedHashMap<>();
        logOnly.put(MaterialRef.of("minecraft:oak_log"), 16);
        Result directLog = PureSearchPlanner.resolve(graph, logOnly,
                roots(MaterialRef.of("minecraft:wooden_pickaxe"), 1),
                PureSearchPlanner.DEFAULT_MAX_STEPS,
                PureSearchPlanner.MAX_SEARCH_STATES,
                PureSearchPlanner.DEFAULT_MAX_MEMOIZED_FAILURES,
                Long.MAX_VALUE, false);
        check("T7a 直合模式只有原木时不可行", !directLog.feasible()
                && directLog.status() == Status.UNRESOLVABLE);
        check("T7a 直合模式没有调度任何步骤", directLog.steps().isEmpty());

        // 7b：木板+木棍已在背包 → 直合模式一步做出木稿
        Map<MaterialRef, Integer> directStock = new LinkedHashMap<>();
        directStock.put(MaterialRef.of("minecraft:oak_planks"), 8);
        directStock.put(MaterialRef.of("minecraft:stick"), 8);
        Result directPickaxe = PureSearchPlanner.resolve(graph, directStock,
                roots(MaterialRef.of("minecraft:wooden_pickaxe"), 1),
                PureSearchPlanner.DEFAULT_MAX_STEPS,
                PureSearchPlanner.MAX_SEARCH_STATES,
                PureSearchPlanner.DEFAULT_MAX_MEMOIZED_FAILURES,
                Long.MAX_VALUE, false);
        check("T7b 直合模式原料齐全可行", directPickaxe.feasible());
        check("T7b 直合模式只执行 pickaxe 一步",
                directPickaxe.steps().size() == 1
                        && directPickaxe.steps().get(0).recipeId().equals("r_pickaxe"));

        // 7c：直合模式允许直接由原木产出木板（输入就在库存）
        Map<MaterialRef, Integer> logForPlanks = new LinkedHashMap<>();
        logForPlanks.put(MaterialRef.of("minecraft:oak_log"), 1);
        Result directPlanks = PureSearchPlanner.resolve(graph, logForPlanks,
                roots(MaterialRef.of("minecraft:oak_planks"), 1),
                PureSearchPlanner.DEFAULT_MAX_STEPS,
                PureSearchPlanner.MAX_SEARCH_STATES,
                PureSearchPlanner.DEFAULT_MAX_MEMOIZED_FAILURES,
                Long.MAX_VALUE, false);
        check("T7c 直合模式可直接原木→木板", directPlanks.feasible()
                && directPlanks.steps().size() == 1
                && directPlanks.steps().get(0).recipeId().equals("r_log"));
    }

    /** 深度（用于环保护验证）。 */
    private static int depthOf(PlanTree.TreeNode node) {
        int max = 1;
        for (PlanTree.TreeNode child : node.children()) {
            max = Math.max(max, 1 + depthOf(child));
        }
        return max;
    }

    private static boolean hasState(PlanTree.TreeNode node, PlanTree.State state) {
        if (node.state() == state) {
            return true;
        }
        for (PlanTree.TreeNode child : node.children()) {
            if (hasState(child, state)) {
                return true;
            }
        }
        return false;
    }

    /** 子树中存在指定物品且状态匹配的节点。 */
    private static boolean hasNode(PlanTree.TreeNode node, String itemId, PlanTree.State state) {
        if (node.material().itemId().equals(itemId) && node.state() == state) {
            return true;
        }
        for (PlanTree.TreeNode child : node.children()) {
            if (hasNode(child, itemId, state)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static RecipeNode recipe(String recipeId, String inputId, String outputId,
                                     int outputCount, IngredientRef... inputs) {
        return new RecipeNode(recipeId, MaterialRef.of(outputId), outputCount, List.of(inputs));
    }

    private static IngredientRef ing(MaterialRef material, int count) {
        return IngredientRef.of(material, count);
    }

    private static List<IngredientRef> roots(MaterialRef target, int count) {
        return List.of(IngredientRef.of(target, count));
    }

    private static Map<MaterialRef, Integer> stockAfter(Result result) {
        return result.remaining();
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }
}
