package com.adimn.autocraft.craft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import com.adimn.autocraft.compat.CraftingGridAdapter;
import com.adimn.autocraft.compat.CraftingGridAdapters;
import com.adimn.autocraft.config.Config;
import com.adimn.autocraft.plan.PureSearchPlanner.PlannedStep;
import com.adimn.autocraft.util.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * 执行层（M3 修复版）：按规划清单 List&lt;PlannedStep&gt; 驱动 3×3 合成格。
 * 通过 CraftingGridAdapter 支持原版合成台、RS/AE2 终端、精妙背包合成升级等菜单。
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
    private static final int SETTLE_TICKS = 3;
    private static final int NETWORK_FILL_SETTLE_TICKS = 15;  // 网络填格：上一步合成点击包到下一步填格包之间多等一会
    private static final int PREPARE_TICKS = 10;
    private static final int SYNC_INITIAL_WAIT = 2;
    private static final int SYNC_MAX_POLL = 18;  // 首次等待后最多再轮询 18 tick

    private enum Phase { PREPARE, SETTLE, SCAN, PICKUP, PLACE_ONE, RETURN_EXCESS, SYNC, CRAFT, COOLDOWN }

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
    private static AbstractContainerMenu menu;
    private static CraftingGridAdapter adapter;
    private static int resultSlotId = -1;
    private static List<Integer> gridSlotIds = List.of();
    private static List<Integer> sourceSlotIds = List.of();
    private static int preparationTicks = 0;
    private static boolean networkFillPending = false;
    private static boolean recipeBookFillPending = false;
    private static int networkFillBatches = 1;
    private static int networkCraftClicksRemaining = 0;
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
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        AbstractContainerMenu current = mc.player.containerMenu;
        CraftingGridAdapter found = CraftingGridAdapters.find(current);
        if (found == null) {
            chat("请先打开合成台（3x3）或支持的终端/背包合成格再执行。");
            Log.warn("执行启动失败：当前菜单没有可用合成格适配器: "
                    + current.getClass().getSimpleName());
            return;
        }
        adapter = found;
        menu = current;
        steps = List.copyOf(plan);
        state = CraftState.DRIVING;
        stepIndex = 0;
        fillPlan = null;
        if (adapter.needsPreparation(current)) {
            adapter.prepare(current);
            preparationTicks = PREPARE_TICKS;
            phase = Phase.PREPARE;
            Log.info("执行启动（需准备合成格）：共 " + steps.size() + " 步，适配器=" + adapter.name());
        } else {
            if (!captureSlots(current)) {
                chat("无法识别当前合成格槽位，未开始执行。");
                Log.warn("执行启动失败：适配器 " + adapter.name() + " 槽位识别失败");
                reset();
                return;
            }
            prepareStep();
            Log.info("执行启动：共 " + steps.size() + " 步，适配器=" + adapter.name());
        }
    }

    public static void stop() {
        if (state == CraftState.DRIVING) {
            chat("已停止自动合成（第 " + stepIndex + " 步中断）。");
            Log.info("执行停止：第 " + stepIndex + " 步中断（" + currentStepId + "）");
        }
        reset();
    }

    /** 每客户端 tick 驱动一步（由 ClientTicker 转发）。 */
    public static void onClientTick() {
        if (state != CraftState.DRIVING) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            stop();
            return;
        }
        AbstractContainerMenu current = mc.player.containerMenu;
        if (adapter == null || current != menu || CraftingGridAdapters.find(current) != adapter) {
            stop();
            chat("合成格已关闭或切换，自动合成停止。");
            return;
        }
        menu = current;
        step();
    }

    // ------------------------------------------------------------------
    // 状态机
    // ------------------------------------------------------------------

    private static void step() {
        switch (phase) {
            case PREPARE -> {
                if (--preparationTicks > 0) {
                    break;
                }
                if (!captureSlots(menu)) {
                    String adapterName = adapter != null ? adapter.name() : "null";
                    stop();
                    chat("合成格准备失败（槽位识别异常），已停止。");
                    Log.warn("PREPARE 失败：适配器 " + adapterName);
                    return;
                }
                // 准备完成后进入第一步；prepareStep 内部会按适配器设置 SETTLE/网络填格等待。
                prepareStep();
            }
            case SETTLE -> {
                if (--waitTicks > 0) {
                    break;   // 等容器状态同步（背包/网格）
                }
                if (recipeBookFillPending) {
                    // 原版合成台：一次配方书填格，然后等结果槽出现
                    Log.info("SETTLE 完成，发送配方书填格：" + currentStepId);
                    sendPlaceRecipePacket();
                    recipeBookFillPending = false;
                    syncPoll = 0;
                    waitTicks = SYNC_INITIAL_WAIT;
                    phase = Phase.SYNC;
                } else if (adapter != null && adapter.usesNetworkFill()) {
                    if (networkFillPending) {
                        // 延迟到 SETTLE 后再发网络填格：确保上一步合成的产物已同步进玩家背包/网络
                        // 暂时固定为单批：RS 从玩家背包回退时只会拿 1 份，批量填格会导致格子数量不一致。
                        // 等后续实现“网络库存足够才批量”的检测后再恢复 computeNetworkFillBatches()。
                        networkFillBatches = 1;
                        networkCraftClicksRemaining = networkFillBatches;
                        Log.info("SETTLE 完成，发送网络填格：" + currentStepId
                                + " batches=" + networkFillBatches);
                        adapter.fillGrid(menu, recipe, networkFillBatches);
                        networkFillPending = false;
                        // 填格后先等结果槽出现，再开始 shift-click
                        syncPoll = 0;
                        waitTicks = SYNC_INITIAL_WAIT;
                        phase = Phase.SYNC;
                    } else if (networkCraftClicksRemaining > 0) {
                        // 同一批材料已经填好，继续补 shift-click（防止 RS 一次只合成 1 个）
                        phase = Phase.CRAFT;
                    } else {
                        // 网络填格后直接轮询结果槽，不再手动 SCAN/PICKUP
                        syncPoll = 0;
                        waitTicks = SYNC_INITIAL_WAIT;
                        phase = Phase.SYNC;
                    }
                } else {
                    phase = Phase.SCAN;
                }
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
                if (resultSlotId >= 0 && menu.getSlot(resultSlotId).hasItem()) {
                    Log.info("SYNC 结果出现：步骤 " + currentStepId
                            + " 网格=" + gridState()
                            + " 结果=" + menu.getSlot(resultSlotId).getItem().getCount()
                            + "x" + menu.getSlot(resultSlotId).getItem().getHoverName().getString());
                    phase = Phase.CRAFT;
                    break;
                }
                if (++syncPoll <= SYNC_MAX_POLL) {
                    break;   // 继续轮询（服务端同步慢时兜底）
                }
                String failedStepId = currentStepId;
                String failedGrid = gridState();
                int failedStepIndex = stepIndex + 1;
                stop();
                chat("合成结果无效（服务端不认网格），已停止：步骤 " + failedStepIndex + "（" + failedStepId + "）。");
                Log.warn("SYNC 超时无结果：步骤 " + failedStepId + " 网格=" + failedGrid);
                return;
            }
            case CRAFT -> {
                Log.debug("click CRAFT (QUICK_MOVE result " + resultSlotId + ")");
                click(resultSlotId, 0, ClickType.QUICK_MOVE);   // shift-click 合成
                if (adapter != null && adapter.usesNetworkFill()
                        && networkCraftClicksRemaining > 0 && networkFillBatches > 1) {
                    networkCraftClicksRemaining--;
                    Log.info("网络填格批量点击：步骤 " + currentStepId
                            + " 剩余点击 " + networkCraftClicksRemaining);
                    if (networkCraftClicksRemaining > 0) {
                        // 同一批材料已填好，继续补 shift-click；不重新填格
                        afterCooldown = Phase.SETTLE;
                        waitTicks = SETTLE_TICKS;
                        int delay = Config.interCraftDelayTicks();
                        if (delay > 0) {
                            waitTicks = delay + SETTLE_TICKS;
                            phase = Phase.COOLDOWN;
                        } else {
                            phase = Phase.SETTLE;
                        }
                        break;
                    }
                    // 所有计划内的 shift-click 已发出，认为本步骤完成
                    batchRemaining = 0;
                    stepIndex++;
                    prepareStep();
                    break;
                }
                // 普通/非网络路径：一次合成一批
                batchRemaining -= networkFillBatches;
                Log.info("批次完成：步骤 " + currentStepId + " 剩余批次 " + batchRemaining
                        + "（本次填格 " + networkFillBatches + " 批）");
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
                } else if (adapter != null && adapter.usesRecipeBookPlacement()) {
                    // 原版合成台：下一批继续用配方书一键填格
                    recipeBookFillPending = true;
                } else if (adapter != null && adapter.usesNetworkFill()) {
                    // RS 等网络填格不一定会自动补货；每批都重新让 mod 从网络/存储填一次格。
                    networkFillPending = true;
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

    /** 扫描来源槽，为每个填充动作定死来源槽位（本地模型扣减，之后盲发）。 */
    private static boolean scanSources() {
        Map<Integer, Long> remaining = new HashMap<>();
        for (int slot : sourceSlotIds) {
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
        Log.info("准备步骤：" + planned.recipeId() + " batches=" + planned.batches());
        fillIndex = 0;
        recipeBookFillPending = false;
        if (adapter.usesNetworkFill()) {
            // 网络填格：让 mod 从网络/存储填充合成格，我们只负责等待同步和 shift 合成。
            // 注意：这里只登记待填格，真正的 adapter.fillGrid 延后到 SETTLE 之后执行，
            // 避免上一步合成点击包还没被服务端处理完就立刻发送下一步填格包。
            networkFillPending = true;
            networkCraftClicksRemaining = 0;
            fillPlan = List.of();
            waitTicks = Config.networkFillSettleTicks();
            phase = Phase.SETTLE;
            chat("▶ 步骤 " + (stepIndex + 1) + "/" + steps.size() + "：" + currentStepId);
            return;
        }
        // 非网络路径（原版/精妙背包）每次只填/合成 1 批，避免沿用上一次网络填格的批量值。
        networkFillBatches = 1;
        networkCraftClicksRemaining = 0;
        if (adapter.usesRecipeBookPlacement()) {
            // 原版合成台：用 ServerboundPlaceRecipePacket 一次填满网格，避免逐槽点击。
            recipeBookFillPending = true;
            fillPlan = List.of();
            waitTicks = SETTLE_TICKS;
            phase = Phase.SETTLE;
            chat("▶ 步骤 " + (stepIndex + 1) + "/" + steps.size() + "：" + currentStepId);
            return;
        }
        fillPlan = buildGridLayout(recipe);
        waitTicks = SETTLE_TICKS;
        phase = Phase.SETTLE;
        chat("▶ 步骤 " + (stepIndex + 1) + "/" + steps.size() + "：" + currentStepId);
    }

    /**
     * 计算一次网络填格最多放几批材料：
     * 产物可堆叠时，一次放入多份原料，shift-click 可一次合成多份；
     * 产物不可堆叠（如工具）时只能一批一批来。
     */
    private static int computeNetworkFillBatches() {
        if (adapter == null || !adapter.usesNetworkFill() || recipe == null || batchRemaining <= 1) {
            return 1;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return 1;
        }
        ItemStack result = recipe.getResultItem(mc.level.registryAccess());
        if (result.isEmpty() || result.getMaxStackSize() <= 1) {
            return 1;
        }
        int outputCount = Math.max(1, result.getCount());
        int maxBatches = Math.max(1, result.getMaxStackSize() / outputCount);
        return Math.max(1, Math.min(batchRemaining, maxBatches));
    }

    // ------------------------------------------------------------------
    // 驱动原语
    // ------------------------------------------------------------------

    /** 发送一个容器点击包（服务端认可，等同手点）。 */
    private static void click(int slot, int button, ClickType type) {
        Minecraft mc = Minecraft.getInstance();
        if (adapter != null && adapter.usesVirtualSlotIds()) {
            sendRawContainerClick(slot, button, type);
        } else {
            mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, mc.player);
        }
    }

    /**
     * 虚拟槽（Sophisticated）专用：直接发送 ServerboundContainerClickPacket。
     * 客户端 MultiPlayerGameMode 会直接访问 menu.slots 导致虚拟槽越界/崩溃，
     * 而服务端 StorageContainerMenuBase.getSlot() 能正确映射虚拟槽。
     */
    private static void sendRawContainerClick(int slot, int button, ClickType type) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null || menu == null) {
            return;
        }
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                menu.containerId,
                menu.getStateId(),
                slot,
                button,
                type,
                menu.getCarried().copy(),
                new Int2ObjectOpenHashMap<>());
        mc.getConnection().send(packet);
    }

    /** 原版合成台：用配方书填格包一次放好整组材料。 */
    private static void sendPlaceRecipePacket() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || menu == null || recipe == null) {
            return;
        }
        mc.getConnection().send(new ServerboundPlaceRecipePacket(menu.containerId, recipe, false));
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
                        plan.add(new FillAction(gridSlotIds.get(row * 3 + col), ingredient));
                    }
                }
            }
        } else {
            int index = 0;
            for (Ingredient ingredient : crafting.getIngredients()) {
                if (!ingredient.isEmpty()) {
                    plan.add(new FillAction(gridSlotIds.get(index++), ingredient));
                }
            }
        }
        return plan;
    }

    /** 从当前菜单捕获网格/结果/来源槽位；失败返回 false。 */
    private static boolean captureSlots(AbstractContainerMenu m) {
        CraftingGridAdapter a = CraftingGridAdapters.find(m);
        if (a == null) {
            return false;
        }
        int result = a.getResultSlotId(m);
        List<Integer> grid = a.getGridSlotIds(m);
        List<Integer> sources = a.getSourceSlotIds(m);
        if (result < 0 || grid.size() != 9) {
            Log.warn("槽位识别失败：适配器=" + a.name() + " result=" + result + " grid=" + grid);
            return false;
        }
        for (int id : grid) {
            if (id < 0) {
                Log.warn("网格槽非法：" + id);
                return false;
            }
            // 注意：Sophisticated 等模组的合成格是 StorageContainerMenuBase.getSlot(int) 映射的
            // 虚拟槽位，可能超过 menu.slots.size()，所以这里不能再用 m.slots.size() 做上界判断。
        }
        adapter = a;
        resultSlotId = result;
        gridSlotIds = List.copyOf(grid);
        sourceSlotIds = List.copyOf(sources);
        return true;
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
        for (int i = 0; i < gridSlotIds.size(); i++) {
            int slot = gridSlotIds.get(i);
            ItemStack stack = menu.getSlot(slot).getItem();
            sb.append(stack.isEmpty() ? "_" : stack.getCount() + "x" + stack.getHoverName().getString());
            if (i < gridSlotIds.size() - 1) {
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
        adapter = null;
        resultSlotId = -1;
        gridSlotIds = List.of();
        sourceSlotIds = List.of();
        preparationTicks = 0;
        networkFillPending = false;
        recipeBookFillPending = false;
        networkFillBatches = 1;
        networkCraftClicksRemaining = 0;
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
