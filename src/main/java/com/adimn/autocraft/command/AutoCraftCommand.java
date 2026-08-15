package com.adimn.autocraft.command;

import java.util.List;
import java.util.Map;

import com.adimn.autocraft.config.Config;
import com.adimn.autocraft.craft.CraftExecutor;
import com.adimn.autocraft.index.RecipeIndex;
import com.adimn.autocraft.trigger.OrderTrigger;
import com.adimn.autocraft.plan.ImmutableRecipeGraph;
import com.adimn.autocraft.plan.IngredientRef;
import com.adimn.autocraft.plan.MaterialRef;
import com.adimn.autocraft.plan.PureSearchPlanner;
import com.adimn.autocraft.plan.PureSearchPlanner.PlannedStep;
import com.adimn.autocraft.plan.PureSearchPlanner.Result;
import com.adimn.autocraft.plan.PureSearchPlanner.Status;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 调试命令（M2/M3）：
 *   /autocraft plan  <item>  规划并打印步骤清单（不执行）
 *   /autocraft craft <item>  规划并立即执行（需打开合成台 3x3）
 *   /autocraft stop          停止当前执行
 * 依赖：单机（集成服务器）下命令源有完整配方表 + 玩家背包快照。
 */
public final class AutoCraftCommand {
    private AutoCraftCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("autocraft")
                .then(Commands.literal("plan")
                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                .executes(AutoCraftCommand::plan)))
                .then(Commands.literal("craft")
                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                .executes(AutoCraftCommand::craft)))
                .then(Commands.literal("delay")
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(0, 1200))
                                .executes(AutoCraftCommand::delay)))
                .then(Commands.literal("preview")
                        .then(Commands.argument("on", BoolArgumentType.bool())
                                .executes(AutoCraftCommand::preview)))
                .then(Commands.literal("cross")
                        .then(Commands.argument("on", BoolArgumentType.bool())
                                .executes(AutoCraftCommand::crossLayer)))
                .then(Commands.literal("stop")
                        .executes(AutoCraftCommand::stop)));
    }

    /** /autocraft plan <item>：规划并打印清单，可行则打开预览界面（可确认执行）。 */
    private static int plan(CommandContext<CommandSourceStack> ctx) {
        return runPlan(ctx, true);
    }

    /** /autocraft craft <item>：规划 + 预览 + 执行（与 plan 同路径）。 */
    private static int craft(CommandContext<CommandSourceStack> ctx) {
        return runPlan(ctx, true);
    }

    /** /autocraft delay <ticks>：会话级覆盖批次间隔（公网服防反作弊）。 */
    private static int delay(CommandContext<CommandSourceStack> ctx) {
        int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
        Config.setDelayOverride(ticks);
        ctx.getSource().sendSuccess(() -> Component.literal("批次间隔已设为 " + ticks + " tick（会话级，重启失效）。"),
                false);
        return 1;
    }

    /** /autocraft preview <on|off>：会话级覆盖计划预览开关。 */
    private static int preview(CommandContext<CommandSourceStack> ctx) {
        boolean on = BoolArgumentType.getBool(ctx, "on");
        Config.setPreviewOverride(on);
        ctx.getSource().sendSuccess(() -> Component.literal("计划预览已" + (on ? "开启" : "关闭") + "（会话级）。"),
                false);
        return 1;
    }

    /** /autocraft cross <on|off>：会话级覆盖跨层合成开关。 */
    private static int crossLayer(CommandContext<CommandSourceStack> ctx) {
        boolean on = BoolArgumentType.getBool(ctx, "on");
        Config.setCrossLayerOverride(on);
        ctx.getSource().sendSuccess(() -> Component.literal("跨层合成已" + (on ? "开启" : "关闭")
                + "（会话级；关闭=仅背包原料直合）。"), false);
        return 1;
    }

    private static int runPlan(CommandContext<CommandSourceStack> ctx, boolean openPreview) {
        ResourceLocation itemId = ResourceLocationArgument.getId(ctx, "item");
        CommandSourceStack source = ctx.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();
            Item item = ForgeRegistries.ITEMS.getValue(itemId);
            if (item == null) {
                source.sendSuccess(() -> Component.literal("未知物品: " + itemId), false);
                return 0;
            }
            RegistryAccess registryAccess = source.getLevel().registryAccess();
            RecipeManager manager = source.getLevel().getRecipeManager();
            ImmutableRecipeGraph graph = RecipeIndex.graph(manager, registryAccess);
            Map<MaterialRef, Integer> stock = RecipeIndex.snapshotInventory(player);
            MaterialRef target = MaterialRef.of(itemId.toString());
            List<IngredientRef> roots = List.of(IngredientRef.of(target, 1));
            Result result = PureSearchPlanner.resolve(graph, stock, roots,
                    Config.maxSteps(), Config.maxSearchStates(),
                    PureSearchPlanner.DEFAULT_MAX_MEMOIZED_FAILURES,
                    Config.planningTimeoutMs() * 1_000_000L,
                    Config.allowCrossLayer());

            source.sendSuccess(() -> Component.literal(report(result)), false);
            if (result.feasible() && openPreview) {
                // 切回客户端线程：走统一下单入口（黑名单/预览/数量）
                Minecraft.getInstance().execute(() -> OrderTrigger.orderFromTarget(itemId, 1));
            }
        } catch (Exception e) {
            source.sendSuccess(() -> Component.literal("规划出错: " + e), false);
        }
        return 1;
    }

    /** /autocraft stop：取消当前执行。 */
    private static int stop(CommandContext<CommandSourceStack> ctx) {
        Minecraft.getInstance().execute(CraftExecutor::stop);
        ctx.getSource().sendSuccess(() -> Component.literal("已请求停止自动合成。"), false);
        return 1;
    }

    private static String report(Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.feasible() ? "§a[可行] " : "§c[不可行] ")
                .append(result.status());
        if (result.status() == Status.STEP_LIMIT || result.status() == Status.SEARCH_LIMIT
                || result.status() == Status.TIME_LIMIT) {
            sb.append("（规划超限：展开 ").append(result.expandedStates())
                    .append(" 状态 / 回溯 ").append(result.backtracks())
                    .append(" 次 / 记忆命中 ").append(result.memoHits()).append(" 次）");
        }
        if (result.feasible()) {
            sb.append(" — ").append(result.steps().size()).append(" 步：");
            for (PlannedStep step : result.steps()) {
                sb.append("\n  ").append(step.recipeId()).append(" ×").append(step.batches());
            }
            sb.append("\n剩余: ");
            appendRemaining(sb, result.remaining());
        } else if (!result.missing().isEmpty()) {
            sb.append(" — 缺少: ");
            for (MaterialRef missing : result.missing()) {
                sb.append(missing).append(" ");
            }
        }
        return sb.toString();
    }

    private static void appendRemaining(StringBuilder sb, Map<MaterialRef, Integer> remaining) {
        boolean first = true;
        for (Map.Entry<MaterialRef, Integer> entry : remaining.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
            first = false;
        }
    }
}
