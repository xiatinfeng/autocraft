package com.adimn.autocraft.trigger;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.adimn.autocraft.config.Config;
import com.adimn.autocraft.craft.CraftExecutor;
import com.adimn.autocraft.index.RecipeIndex;
import com.adimn.autocraft.plan.ImmutableRecipeGraph;
import com.adimn.autocraft.plan.IngredientRef;
import com.adimn.autocraft.plan.MaterialRef;
import com.adimn.autocraft.plan.PureSearchPlanner;
import com.adimn.autocraft.plan.PureSearchPlanner.Result;
import com.adimn.autocraft.plan.PureSearchPlanner.Status;
import com.adimn.autocraft.plan.PlanTree;
import com.adimn.autocraft.ui.PlanPreviewScreen;
import com.adimn.autocraft.ui.TreeLine;
import com.adimn.autocraft.util.Log;
import com.adimn.autocraft.util.NbtDisplay;

import net.minecraft.client.Minecraft;

/**
 * 触发层统一下单入口（M4/M5 重构）：
 *   order(target, count)             —— 黑名单检查 → 规划 → 预览确认 → 执行
 *   orderPrecomputed(target, count, result) —— 使用已算好的规划结果（命令 craft 路径复用，避免二次规划）
 *   plan(target, count)              —— 客户端线程规划（预览数量切换时复用）
 *   buildTree(...)                   —— 自写配方树（PlanTree，EMI-free），预览界面展示用
 *
 * 触发路径：
 *   ① 命令：/autocraft plan（只报告）、/autocraft craft（报告 + 预览/执行）
 *   ② 按钮：EMI 配方屏齿轮按钮 → 当前配方产出下单
 *   ③ 按钮：JEI 配方屏齿轮按钮 → 当前配方产出下单
 * 数量：预览界面可调（1/4/16/64），默认 1。
 *
 * 树已完全自写（PlanTree），不依赖 EMI 的 MaterialTree/BoM ——
 * 只装 JEI 的玩家同样能看到配方树（数据来自我们自己的 RecipeIndex graph）。
 */
public final class OrderTrigger {
    private OrderTrigger() {}

    /** 对目标物品下单：规划 → 预览确认 → 执行。 */
    public static void order(MaterialRef target, int count) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (Config.blacklistItems().contains(target.itemId())) {
            CraftExecutor.chat("该物品已在黑名单：" + target);
            return;
        }
        Result result = plan(target, count);
        if (result == null) {
            return;
        }
        dispatch(target, count, result);
    }

    /**
     * 使用已算好的规划结果下单（/autocraft craft 在服务端线程规划后复用，避免二次规划）。
     * 客户端线程调用。
     */
    public static void orderPrecomputed(MaterialRef target, int count, Result result) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (Config.blacklistItems().contains(target.itemId())) {
            CraftExecutor.chat("该物品已在黑名单：" + target);
            return;
        }
        if (result == null) {
            return;
        }
        dispatch(target, count, result);
    }

    /**
     * 预览/执行分派：
     * - 开启预览时，无论可行与否都打开预览（不可行也能查看缺失材料）；
     * - 关闭预览时，可行直接执行，不可行聊天栏报缺料。
     */
    private static void dispatch(MaterialRef target, int count, Result result) {
        if (Config.showPlanPreview()) {
            openPreview(target, count, result);
            return;
        }
        if (result.feasible()) {
            Log.info("下单执行：" + target + " x" + count + "，共 " + result.steps().size() + " 步");
            CraftExecutor.start(result.steps());
        } else {
            CraftExecutor.chat("无法自动合成 " + target + "：" + failureText(result));
            Log.warn("下单不可行：" + target + " -> " + result.status() + " missing=" + result.missing());
        }
    }

    /**
     * 打开预览界面（客户端线程）。result 可为 null（内部会重新规划）。
     * 规划失败（null）才不开；不可行的 Result 同样打开，用于展示缺失材料/数量。
     */
    public static void openPreview(MaterialRef target, int count, Result result) {
        Minecraft mc = Minecraft.getInstance();
        if (result == null) {
            result = plan(target, count);
            if (result == null) {
                return;
            }
        }
        PlanTree.TreeNode root = buildTree(target, count, result);
        Map<MaterialRef, Integer> stock = new java.util.HashMap<>();
        if (mc.player != null) {
            stock.putAll(RecipeIndex.snapshotInventory(mc.player, mc.player.containerMenu));
        }
        // 默认额外合成数：忽略目标物品已有库存；/autocraft quantity total 可切回总拥有量。
        if (Config.extraCount()) {
            stock.remove(target);
        }
        ImmutableRecipeGraph graph = RecipeIndex.allGraph(
                mc.level.getRecipeManager(), mc.level.registryAccess());
        mc.setScreen(new PlanPreviewScreen(mc.screen, target, count, result, root, stock, graph));
    }

    /** 客户端线程规划（预览数量切换 / 直接执行共用）。失败返回 null 或不可行 Result。 */
    public static Result plan(MaterialRef target, int count) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return null;
        }
        try {
            ImmutableRecipeGraph graph = RecipeIndex.craftingGraph(
                    mc.level.getRecipeManager(), mc.level.registryAccess());
            Map<MaterialRef, Integer> stock = new java.util.HashMap<>(
                    RecipeIndex.snapshotInventory(mc.player, mc.player.containerMenu));
            // 默认额外合成数：忽略目标物品已有库存；/autocraft quantity total 可切回总拥有量。
            if (Config.extraCount()) {
                stock.remove(target);
            }
            List<IngredientRef> roots = List.of(IngredientRef.of(target, count));
            return PureSearchPlanner.resolve(graph, stock, roots,
                    Config.maxSteps(), Config.maxSearchStates(),
                    PureSearchPlanner.DEFAULT_MAX_MEMOIZED_FAILURES,
                    Config.planningTimeoutMs() * 1_000_000L,
                    Config.allowCrossLayer());
        } catch (Exception e) {
            CraftExecutor.chat("规划出错：" + e);
            Log.error("下单规划异常", e);
            return null;
        }
    }

    /**
     * 构建自写配方树（EMI-free）：RecipeIndex graph + 规划器结果 → PlanTree。
     * 配方选择优先规划器实际选中的 recipeId；失败/缺料时用 recipesFor 第一个
     * （与规划器枚举序一致），树仍能展示"目标需要哪些材料"。
     * 返回 TreeNode 根（无配方图/目标非法时为 null，预览界面回退平面步骤列表）。
     */
    public static PlanTree.TreeNode buildTree(MaterialRef target, int count, Result result) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return null;
        }
        try {
            // 预览树用全配方图：铁砧/熔炉/锻造等特殊配方也能展示真实材料来源，
            // 即使这些配方不能被 3×3 合成台自动执行。
            ImmutableRecipeGraph graph = RecipeIndex.allGraph(
                    mc.level.getRecipeManager(), mc.level.registryAccess());
            Map<MaterialRef, Integer> stock = new java.util.HashMap<>(
                    RecipeIndex.snapshotInventory(mc.player, mc.player.containerMenu));
            // 默认额外合成数：忽略目标物品已有库存；/autocraft quantity total 可切回总拥有量。
            if (Config.extraCount()) {
                stock.remove(target);
            }
            Set<String> chosen = new java.util.HashSet<>();
            if (result != null && result.feasible()) {
                for (PureSearchPlanner.PlannedStep step : result.steps()) {
                    chosen.add(step.recipeId());
                }
            }
            return PlanTree.build(graph, target, Math.max(1, count), stock, chosen);
        } catch (Exception e) {
            Log.warn("构建配方树失败（回退平面列表）：" + e);
            return null;
        }
    }

    /** 兼容旧调用：构建树后压平为 TreeLine 列表（预览界面统一消费）。 */
    public static List<TreeLine> treeLines(MaterialRef target, int count, Result result) {
        PlanTree.TreeNode root = buildTree(target, count, result);
        if (root == null) {
            return List.of();
        }
        List<TreeLine> lines = new java.util.ArrayList<>();
        flattenPlanNode(root, 0, lines);
        // 总耗材汇总（与 EMI 树底部"总耗材"同义）
        Map<MaterialRef, Long> totals = PlanTree.totalLeafDemand(root);
        if (!totals.isEmpty()) {
            lines.add(new TreeLine(0, "总耗材", 0xFFFFFF));
            for (Map.Entry<MaterialRef, Long> e : totals.entrySet()) {
                lines.add(new TreeLine(1, "  " + e.getKey().itemId() + " ×" + e.getValue(),
                        0xA0A0A0));
            }
        }
        return lines;
    }

    private static void flattenPlanNode(PlanTree.TreeNode node, int depth, List<TreeLine> out) {
        String label = node.itemName() + " ×" + node.amount();
        if (node.catalyst()) {
            label += "（催化剂）";
        }
        if (!node.requirementText().isEmpty()) {
            label += " [" + NbtDisplay.localize(node.requirementText()) + "]";
        }
        if (node.recipeId() != null) {
            label += "  [" + node.recipeId() + "]";
        }
        out.add(new TreeLine(depth, label, planNodeColor(node.state())));
        if (node.children() != null) {
            for (PlanTree.TreeNode child : node.children()) {
                flattenPlanNode(child, depth + 1, out);
            }
        }
    }

    private static int planNodeColor(PlanTree.State state) {
        return switch (state) {
            case HAS -> 0x55FF55;      // 绿：库存满足
            case PARTIAL -> 0xFFFF55;  // 黄：部分满足
            case MISSING -> 0xFF5555;  // 红：缺失
            case CRAFT -> 0xE0E0E0;    // 灰：需生产
        };
    }

    /** 预览界面用的失败摘要（public）。 */
    public static String failureSummary(Result result) {
        return failureText(result);
    }

    private static String failureText(Result result) {
        if (result.status() == Status.STEP_LIMIT || result.status() == Status.SEARCH_LIMIT
                || result.status() == Status.TIME_LIMIT) {
            return "规划超限（" + result.status() + "，展开 " + result.expandedStates() + " 状态）";
        }
        if (!result.missing().isEmpty()) {
            StringBuilder sb = new StringBuilder("缺少 ");
            for (MaterialRef missing : result.missing()) {
                sb.append(missing).append(" ");
            }
            return sb.toString().trim();
        }
        return result.status().name();
    }

}
