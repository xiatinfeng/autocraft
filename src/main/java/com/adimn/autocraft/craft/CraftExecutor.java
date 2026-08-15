package com.adimn.autocraft.craft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.adimn.autocraft.config.Config;
import com.adimn.autocraft.plan.PureSearchPlanner.PlannedStep;
import com.adimn.autocraft.util.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * 执行层（M3 修复版）：按规划清单 List&lt;PlannedStep&gt; 驱动 CraftingMenu（3x3 合成桌）。
 *
 * 修复要点（2026-08-14 终测反馈"合成结果无效（服务端不认网格）"）：
 *   1. **扫描式填充**：每步开始时（容器状态已稳定）一次性扫描背包，把每个网格槽位的
 *      来源槽位提前定死（本地模型扣减），随后**盲发**点击包——执行中不再读客户端状态，
 *      消除客户端同步滞后导致的选错槽位/错误判断。
 *   2. **始终 RETURN_EXCESS**：PICKUP 拿起整叠 → button=1 放 1 个 → 剩余**无条件**放回源槽
 *      （cursor 为空的 no-op 无害），杜绝 cursor 残留导致的下一次 PICKUP 变成 SWAP。
 *   3. **SYNC 轮询**：填充完成后轮询结果槽（最长 ~20 tick），不再固定 2 tick 一把梭。
 *   4. **步骤间 SETTLE**：合成（QUICK_MOVE）后等 3 tick 让服务端同步背包/网格，再扫下一步。
 *
 * 全部点击走标准容器点击包（handleInventoryMouseClick），服务端认可，等同手点。
 */
public final class CraftExecutor {
    private static final int RESULT_SLOT = 0;
    private static final int GRID_START = 1;      // CraftingMenu 槽 1..9 = 3x3 网格
    private static final int INV_START = 10;      // 槽 10..45 = 主背包 + 快捷栏
    private static final int INV_END = 45;
    private static final int SETTLE_TICKS = 3;
    private static final int SYNC_INITIAL_WAIT = 2;
    private static final int SYNC_MAX_POLL = 18;  // 首次等待后最多再轮询 18 tick

    private enum Phase { SETTLE, SCAN, PICKUP, PLACE_ONE, RETURN_EXCESS, SYNC, CRAFT, COOLDOWN }

    /** 一个填充动作：往 gridSlot 放 1 个能匹配 ingredient 的物品；sourceSlot 在 SCAN 阶段定死。 */
    private static final class FillAction {
        final int gridSlot;
        final Ingredient ingredient;
        int sourceSlot = -1;
        long sourceCount;

        FillAction(int gridSlot, Ingredient ingredient) {
            this.gridSlot = gridSlot;
            this.ingredient = ingredient;
        }

        @Override
        public String toString() {
            return "g" + gridSlot + "<-" + (sourceSlot >= 0 ? "s" + sourceSlot + "(" + sourceCount + ")" : "?");
        }
    }

    private static CraftState state = CraftState.IDLE;
    private static List<PlannedStep> steps;
    private static int stepIndex;
    private static int batchRemaining;
    private static CraftingRecipe recipe;
    private static List<FillAction> fillPlan;
    private static int fillIndex;
    private static Phase phase;
    private static Phase afterCooldown;
    private static int waitTicks;
    private static int syncPoll;
    private static CraftingMenu menu;
    private static String currentStepId;

    private CraftExecutor() {}

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    public static CraftState getState() {
        return state;
    }

    public static boolean isDriving() {
        return state == CraftState.DRIVING;
    }

    /** 启动执行：必须在客户端线程调用（命令入口用 Minecraft.getInstance().execute 包一层）。 */
    public static void start(List<PlannedStep> plan) {
        if (plan == null || plan.isEmpty()) {
            chat("空计划，无事可做。");
            return;
        }
        if (TreeDriver.isDriving()) {
            TreeDriver.stop();   // 新执行器接管，避免旧驱动并发
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (!(mc.player.containerMenu instanceof CraftingMenu craftingMenu)) {
            chat("请先打开合成台（3x3）再执行。");
            Log.warn("执行启动失败：当前菜单不是 CraftingMenu: "
                    + mc.player.containerMenu.getClass().getSimpleName());
            return;
        }
        menu = craftingMenu;
        steps = List.copyOf(plan);
        state = CraftState.DRIVING;
        stepIndex = 0;
        fillPlan = null;
        prepareStep();
        Log.info("执行启动：共 " + steps.size() + " 步");
    }

    public static void stop() {
        if (state == CraftState.DRIVING) {
            chat("已停止自动合成（第 " + stepIndex + " 步中断）。");
            Log.info("执行停止：第 " + stepIndex + " 步中断（" + currentStepId + "）");
        }
        reset();
    }

    /** 每客户端 tick 驱动一步（由 KeyHandler 转发）。 */
    public static void onClientTick() {
        if (state != CraftState.DRIVING) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            stop();
            return;
        }
        if (!(mc.player.containerMenu instanceof CraftingMenu craftingMenu)) {
            stop();
            chat("合成台已关闭，自动合成停止。");
            return;
        }
        menu = craftingMenu;
        step();
    }

    // ------------------------------------------------------------------
    // 状态机
    // ------------------------------------------------------------------

    private static void step() {
        switch (phase) {
            case SETTLE -> {
                if (--waitTicks > 0) {
                    break;   // 等容器状态同步（背包/网格）
                }
                phase = Phase.SCAN;
            }
            case SCAN -> {
                if (!scanSources()) {
                    return;   // 已 stop + 提示
                }
                fillIndex = 0;
                phase = Phase.PICKUP;
            }
            case PICKUP -> {
                FillAction fill = fillPlan.get(fillIndex);
                Log.debug("click PICKUP s" + fill.sourceSlot);
                click(fill.sourceSlot, 0, ClickType.PICKUP);
                phase = Phase.PLACE_ONE;
            }
            case PLACE_ONE -> {
                FillAction fill = fillPlan.get(fillIndex);
                Log.debug("click PLACE-1 g" + fill.gridSlot);
                click(fill.gridSlot, 1, ClickType.PICKUP);   // 只放 1 个
                phase = Phase.RETURN_EXCESS;
            }
            case RETURN_EXCESS -> {
                FillAction fill = fillPlan.get(fillIndex);
                Log.debug("click RETURN s" + fill.sourceSlot);
                // 无条件放回剩余（cursor 为空时是无害 no-op）——杜绝 cursor 残留
                click(fill.sourceSlot, 0, ClickType.PICKUP);
                phase = nextFillOrSync();
            }
            case SYNC -> {
                if (--waitTicks > 0) {
                    break;   // 首次等待
                }
                if (menu.getSlot(RESULT_SLOT).hasItem()) {
                    phase = Phase.CRAFT;
                    break;
                }
                if (++syncPoll <= SYNC_MAX_POLL) {
                    break;   // 继续轮询（服务端同步慢时兜底）
                }
                stop();
                chat("合成结果无效（服务端不认网格），已停止：步骤 " + (stepIndex + 1) + "（" + currentStepId + "）。");
                Log.warn("SYNC 超时无结果：步骤 " + currentStepId + " 网格=" + gridState());
                return;
            }
            case CRAFT -> {
                Log.debug("click CRAFT (QUICK_MOVE slot 0)");
                click(RESULT_SLOT, 0, ClickType.QUICK_MOVE);   // shift-click 合成
                batchRemaining--;
                Log.debug("批次完成：步骤 " + currentStepId + " 剩余批次 " + batchRemaining);
                afterCooldown = Phase.SETTLE;   // 合成后先同步再扫下一步/下一批
                waitTicks = SETTLE_TICKS;
                int delay = Config.interCraftDelayTicks();
                if (delay > 0) {
                    waitTicks = delay + SETTLE_TICKS;
                    phase = Phase.COOLDOWN;
                } else {
                    phase = Phase.SETTLE;
                }
                if (batchRemaining <= 0) {
                    stepIndex++;
                    prepareStep();
                }
            }
            case COOLDOWN -> {
                if (--waitTicks > 0) {
                    break;
                }
                phase = afterCooldown;
            }
        }
    }

    /** 扫描背包，为每个填充动作定死来源槽位（本地模型扣减，之后盲发）。 */
    private static boolean scanSources() {
        Map<Integer, Long> remaining = new HashMap<>();
        for (int slot = INV_START; slot <= INV_END; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!stack.isEmpty()) {
                remaining.put(slot, (long) stack.getCount());
            }
        }
        for (FillAction fill : fillPlan) {
            int source = -1;
            for (Map.Entry<Integer, Long> entry : remaining.entrySet()) {
                if (entry.getValue() > 0
                        && fill.ingredient.test(menu.getSlot(entry.getKey()).getItem())) {
                    source = entry.getKey();
                    break;
                }
            }
            if (source < 0) {
                stop();
                chat("材料不足：步骤 " + (stepIndex + 1) + "（" + currentStepId + "）。已保留已合成产物。");
                Log.warn("SCAN 缺料：步骤 " + currentStepId + " 槽位 g" + fill.gridSlot);
                return false;
            }
            fill.sourceSlot = source;
            fill.sourceCount = remaining.get(source);
            remaining.put(source, remaining.get(source) - 1);   // 本动作取 1 个
        }
        Log.debug("SCAN 完成：" + currentStepId + " " + fillPlan);
        return true;
    }

    private static Phase nextFillOrSync() {
        fillIndex++;
        if (fillIndex < fillPlan.size()) {
            return Phase.PICKUP;
        }
        syncPoll = 0;
        waitTicks = SYNC_INITIAL_WAIT;
        return Phase.SYNC;
    }

    /** 进入下一步：解析配方 + 计算网格布局（来源槽位留到 SCAN）。 */
    private static void prepareStep() {
        if (stepIndex >= steps.size()) {
            state = CraftState.DONE;
            chat("自动合成完成 ✓ 共 " + steps.size() + " 步。");
            Log.info("执行完成：全部 " + steps.size() + " 步");
            return;
        }
        PlannedStep planned = steps.get(stepIndex);
        currentStepId = planned.recipeId();
        recipe = resolveRecipe(planned.recipeId());
        if (recipe == null) {
            stop();
            chat("找不到配方：" + planned.recipeId() + "。");
            return;
        }
        batchRemaining = planned.batches();
        fillPlan = buildGridLayout(recipe);
        fillIndex = 0;
        waitTicks = SETTLE_TICKS;
        phase = Phase.SETTLE;
        chat("▶ 步骤 " + (stepIndex + 1) + "/" + steps.size() + "：" + currentStepId);
    }

    // ------------------------------------------------------------------
    // 驱动原语
    // ------------------------------------------------------------------

    /** 发送一个容器点击包（服务端认可，等同手点）。 */
    private static void click(int slot, int button, ClickType type) {
        Minecraft mc = Minecraft.getInstance();
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, mc.player);
    }

    /** 按配方形状计算网格槽位：Shaped 按宽高放左上角，Shapeless 顺次填左上角。 */
    private static List<FillAction> buildGridLayout(CraftingRecipe crafting) {
        List<FillAction> plan = new ArrayList<>();
        if (crafting instanceof ShapedRecipe shaped) {
            int width = shaped.getWidth();
            int height = shaped.getHeight();
            NonNullList<Ingredient> ingredients = crafting.getIngredients();
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    Ingredient ingredient = ingredients.get(row * width + col);
                    if (!ingredient.isEmpty()) {
                        plan.add(new FillAction(GRID_START + row * 3 + col, ingredient));
                    }
                }
            }
        } else {
            int slot = GRID_START;
            for (Ingredient ingredient : crafting.getIngredients()) {
                if (!ingredient.isEmpty()) {
                    plan.add(new FillAction(slot++, ingredient));
                }
            }
        }
        return plan;
    }

    private static CraftingRecipe resolveRecipe(String recipeId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        RecipeManager manager = mc.level.getRecipeManager();
        ResourceLocation id = ResourceLocation.tryParse(recipeId);
        if (id == null) {
            Log.warn("非法配方 id: " + recipeId);
            return null;
        }
        return manager.byKey(id)
                .filter(CraftingRecipe.class::isInstance)
                .map(CraftingRecipe.class::cast)
                .orElse(null);
    }

    /** 调试用：当前网格槽位内容摘要。 */
    private static String gridState() {
        StringBuilder sb = new StringBuilder("[");
        for (int slot = GRID_START; slot <= 9; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            sb.append(stack.isEmpty() ? "_" : stack.getCount() + "x" + stack.getHoverName().getString());
            if (slot < 9) {
                sb.append(",");
            }
        }
        return sb.append("]").toString();
    }

    private static void reset() {
        state = CraftState.IDLE;
        steps = null;
        fillPlan = null;
        recipe = null;
        menu = null;
        currentStepId = null;
    }

    public static void chat(String msg) {
        if (!Config.chatFeedback()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[AutoCraft] " + msg));
        }
    }
}
