package com.adimn.autocraft.config;

import java.util.List;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * 客户端配置。延迟做成可配项，让用户按自己服的反作弊宽松度调整。
 */
public final class Config {
    public static final int DEFAULT_DELAY_TICKS = 20; // ≈1 秒，人速

    public static ForgeConfigSpec.IntValue DELAY_TICKS;
    public static ForgeConfigSpec.IntValue INTER_CRAFT_DELAY_TICKS;
    public static ForgeConfigSpec.IntValue MAX_CRAFTS_PER_TICK;
    public static ForgeConfigSpec.BooleanValue SHOW_PLAN_PREVIEW;
    public static ForgeConfigSpec.IntValue MAX_STEPS;
    public static ForgeConfigSpec.IntValue MAX_SEARCH_STATES;
    public static ForgeConfigSpec.IntValue PLANNING_TIMEOUT_MS;
    public static ForgeConfigSpec.BooleanValue ALLOW_CROSS_LAYER;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> BLACKLIST_ITEMS;
    public static ForgeConfigSpec.BooleanValue STOP_ON_FULL;
    public static ForgeConfigSpec.BooleanValue CHAT_FEEDBACK;
    public static ForgeConfigSpec.BooleanValue LOG_ENABLED;
    public static ForgeConfigSpec.BooleanValue LOG_DEBUG;

    /** 局内指令的运行时覆盖（会话级，重启或 /autocraft config 清空后失效）。 */
    private static volatile Integer delayOverride;
    private static volatile Boolean previewOverride;
    private static volatile Boolean crossLayerOverride;

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    private static final ForgeConfigSpec SPEC;

    static {
        BUILDER.comment("EMI AutoCraft 客户端配置");
        DELAY_TICKS = BUILDER.comment(
                "每步合成之间的客户端 tick 延迟（默认 20≈1 秒）。调大更慢更稳，调小更快但可能触发反作弊。")
                .defineInRange("delayTicks", DEFAULT_DELAY_TICKS, 1, 1200);
        INTER_CRAFT_DELAY_TICKS = BUILDER.comment(
                "规划执行：每批次合成之间的延迟（tick）。默认 0 单机；公网服建议 2+ 防反作弊判定。")
                .defineInRange("interCraftDelayTicks", 0, 0, 1200);
        MAX_CRAFTS_PER_TICK = BUILDER.comment(
                "规划执行：每 tick 最多合成批次上限（大批次跨 tick 拆分，防服务端限速/卡顿）。")
                .defineInRange("maxCraftsPerTick", 8, 1, 64);
        SHOW_PLAN_PREVIEW = BUILDER.comment("下单前是否弹出计划预览界面（步骤列表 + 开始/取消）。")
                .define("showPlanPreview", true);
        MAX_STEPS = BUILDER.comment("规划器：执行步骤上限（护栏）。").defineInRange("maxSteps", 256, 1, 4096);
        MAX_SEARCH_STATES = BUILDER.comment("规划器：搜索状态展开上限（防组合爆炸护栏）。")
                .defineInRange("maxSearchStates", 65536, 256, 1_000_000);
        PLANNING_TIMEOUT_MS = BUILDER.comment("规划器：时间预算（毫秒），超时返回 TIME_LIMIT。")
                .defineInRange("planningTimeoutMs", 500, 50, 5000);
        ALLOW_CROSS_LAYER = BUILDER.comment(
                "允许跨层合成（true=递归生产中间产物，false=仅背包原料直合）。")
                .define("allowCrossLayer", true);
        BLACKLIST_ITEMS = BUILDER.comment("黑名单物品（mod:id 列表），禁用为目标或原料。")
                .defineList("blacklistItems", List.of(), entry -> entry instanceof String);
        STOP_ON_FULL = BUILDER.comment("背包满时自动停止驱动。").define("stopOnFull", true);
        CHAT_FEEDBACK = BUILDER.comment("在聊天栏显示驱动状态提示。").define("chatFeedback", true);
        LOG_ENABLED = BUILDER.comment("启用文件日志，写入 整合包版本文件夹/logs/autocraft.log。").define("logEnabled", true);
        LOG_DEBUG = BUILDER.comment("记录 DEBUG 级日志（步骤级细节，较冗长）。").define("logDebug", false);
        SPEC = BUILDER.build();
    }

    private Config() {}

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, SPEC);
    }

    public static int delayTicks() {
        return DELAY_TICKS.get();
    }

    public static boolean stopOnFull() {
        return STOP_ON_FULL.get();
    }

    public static int interCraftDelayTicks() {
        Integer override = delayOverride;
        return override != null ? override : INTER_CRAFT_DELAY_TICKS.get();
    }

    public static int maxCraftsPerTick() {
        return MAX_CRAFTS_PER_TICK.get();
    }

    public static boolean showPlanPreview() {
        Boolean override = previewOverride;
        return override != null ? override : SHOW_PLAN_PREVIEW.get();
    }

    /** /autocraft delay <ticks>：会话级覆盖批次间隔。 */
    public static void setDelayOverride(int ticks) {
        delayOverride = Math.max(0, Math.min(1200, ticks));
    }

    /** /autocraft preview <on|off>：会话级覆盖预览开关。 */
    public static void setPreviewOverride(boolean on) {
        previewOverride = on;
    }

    /** /autocraft cross <on|off>：会话级覆盖跨层合成开关。 */
    public static void setCrossLayerOverride(boolean on) {
        crossLayerOverride = on;
    }

    /** 清空所有运行时覆盖。 */
    public static void clearOverrides() {
        delayOverride = null;
        previewOverride = null;
        crossLayerOverride = null;
    }

    public static int maxSteps() {
        return MAX_STEPS.get();
    }

    public static int maxSearchStates() {
        return MAX_SEARCH_STATES.get();
    }

    public static int planningTimeoutMs() {
        return PLANNING_TIMEOUT_MS.get();
    }

    public static boolean allowCrossLayer() {
        Boolean override = crossLayerOverride;
        return override != null ? override : ALLOW_CROSS_LAYER.get();
    }

    public static java.util.Set<String> blacklistItems() {
        return new java.util.HashSet<>(BLACKLIST_ITEMS.get());
    }

    public static boolean chatFeedback() {
        return CHAT_FEEDBACK.get();
    }

    public static boolean logEnabled() {
        return LOG_ENABLED.get();
    }

    public static boolean debugLog() {
        return LOG_DEBUG.get();
    }
}
