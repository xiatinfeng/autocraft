package com.adimn.autocraft.config;

import java.util.List;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * 客户端配置。延迟做成可配项，让用户按自己服的反作弊宽松度调整。
 */
public final class Config {
    public static ForgeConfigSpec.IntValue INTER_CRAFT_DELAY_TICKS;
    public static ForgeConfigSpec.IntValue NETWORK_FILL_SETTLE_TICKS;
    public static ForgeConfigSpec.BooleanValue SHOW_PLAN_PREVIEW;
    public static ForgeConfigSpec.IntValue MAX_STEPS;
    public static ForgeConfigSpec.IntValue MAX_SEARCH_STATES;
    public static ForgeConfigSpec.IntValue PLANNING_TIMEOUT_MS;
    public static ForgeConfigSpec.BooleanValue ALLOW_CROSS_LAYER;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> BLACKLIST_ITEMS;
    public static ForgeConfigSpec.BooleanValue NBT_FINGERPRINT;
    public static ForgeConfigSpec.BooleanValue CHAT_FEEDBACK;
    public static ForgeConfigSpec.BooleanValue LOG_ENABLED;
    public static ForgeConfigSpec.BooleanValue LOG_DEBUG;
    public static ForgeConfigSpec.BooleanValue CRAFT_EXTRA_COUNT;
    public static ForgeConfigSpec.BooleanValue FIXED_NODE_SIZE;

    /** 局内指令的运行时覆盖（会话级，重启或 /autocraft config 清空后失效）。 */
    private static volatile Integer delayOverride;
    private static volatile Integer networkFillTicksOverride;
    private static volatile Boolean previewOverride;
    private static volatile Boolean crossLayerOverride;
    private static volatile Boolean extraCountOverride;
    private static volatile Boolean fixedNodeSizeOverride;

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    private static final ForgeConfigSpec SPEC;

    static {
        BUILDER.comment("AutoCraft 客户端配置");
        INTER_CRAFT_DELAY_TICKS = BUILDER.comment(
                "规划执行：每批次合成之间的延迟（tick）。默认 0 单机；公网服建议 2+ 防反作弊判定。")
                .defineInRange("interCraftDelayTicks", 0, 0, 1200);
        NETWORK_FILL_SETTLE_TICKS = BUILDER.comment(
                "网络填格（RS/AE2）在发送填格包前的等待 tick。默认 15；觉得慢可调低，公网服建议保持较高。")
                .defineInRange("networkFillSettleTicks", 15, 0, 200);
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
        NBT_FINGERPRINT = BUILDER.comment(
                "NBT 指纹匹配（默认仅对原版附魔书启用；大 NBT 物品如拔刀剑不参与匹配，只展示 NBT 需求）。")
                .define("nbtFingerprint", true);
        CHAT_FEEDBACK = BUILDER.comment("在聊天栏显示驱动状态提示。").define("chatFeedback", true);
        LOG_ENABLED = BUILDER.comment("启用文件日志，写入 整合包版本文件夹/logs/autocraft.log。").define("logEnabled", true);
        LOG_DEBUG = BUILDER.comment("记录 DEBUG 级日志（步骤级细节，较冗长）。").define("logDebug", false);
        CRAFT_EXTRA_COUNT = BUILDER.comment("数量语义：true=额外合成数（输入5做5个新物品）；false=目标总拥有量。")
                .define("craftExtraCount", true);
        FIXED_NODE_SIZE = BUILDER.comment("预览树节点固定屏幕大小（不随缩放变化）。默认 false。")
                .define("fixedNodeSize", false);
        SPEC = BUILDER.build();
    }

    private Config() {}

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, SPEC);
    }

    public static int interCraftDelayTicks() {
        Integer override = delayOverride;
        return override != null ? override : INTER_CRAFT_DELAY_TICKS.get();
    }

    public static int networkFillSettleTicks() {
        Integer override = networkFillTicksOverride;
        return override != null ? override : NETWORK_FILL_SETTLE_TICKS.get();
    }

    public static boolean showPlanPreview() {
        Boolean override = previewOverride;
        return override != null ? override : SHOW_PLAN_PREVIEW.get();
    }

    public static boolean nbtFingerprintEnabled() {
        return NBT_FINGERPRINT.get();
    }

    /** /autocraft delay <ticks>：会话级覆盖批次间隔。 */
    public static void setDelayOverride(int ticks) {
        delayOverride = Math.max(0, Math.min(1200, ticks));
    }

    /** /autocraft speed <ticks>：会话级覆盖网络填格等待。 */
    public static void setNetworkFillSettleOverride(int ticks) {
        networkFillTicksOverride = Math.max(0, Math.min(200, ticks));
    }

    /** /autocraft preview <on|off>：会话级覆盖预览开关。 */
    public static void setPreviewOverride(boolean on) {
        previewOverride = on;
    }

    /** /autocraft cross <on|off>：会话级覆盖跨层合成开关。 */
    public static void setCrossLayerOverride(boolean on) {
        crossLayerOverride = on;
    }

    /** /autocraft quantity <extra|total>：会话级覆盖数量语义。 */
    public static void setExtraCountOverride(boolean extra) {
        extraCountOverride = extra;
    }

    /** /autocraft fixnodes <on|off>：会话级覆盖预览树节点固定大小开关。 */
    public static void setFixedNodeSizeOverride(boolean on) {
        fixedNodeSizeOverride = on;
    }

    /** 清空所有运行时覆盖。 */
    public static void clearOverrides() {
        delayOverride = null;
        networkFillTicksOverride = null;
        previewOverride = null;
        crossLayerOverride = null;
        extraCountOverride = null;
        fixedNodeSizeOverride = null;
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

    /** 数量语义：true=额外合成数（默认），false=目标总拥有量。 */
    public static boolean extraCount() {
        Boolean override = extraCountOverride;
        return override != null ? override : CRAFT_EXTRA_COUNT.get();
    }

    /** 预览树节点固定大小开关（默认 false）。 */
    public static boolean fixedNodeSize() {
        Boolean override = fixedNodeSizeOverride;
        return override != null ? override : FIXED_NODE_SIZE.get();
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
