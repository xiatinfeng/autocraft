package com.adimn.autocraft.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 有界回溯搜索规划器（M1 核心，纯 Java，零 MC 依赖）。
 *
 * 问题类型 = 带库存约束的搜索问题（约束求解），不是图遍历：
 *   - 多配方 → 搜索分支 + 失败回溯（启发式一次定死会选错）
 *   - 库存交互 → stock 进入搜索状态（实时扣减/回填；tag 变体聚合凑数）
 *   - 环 → resolving 集合（正在求解的物品回边跳过）+ 自耗净增益剪枝
 *   - 组合爆炸 → 全套护栏（步骤/状态/深度/时间/失败记忆）先行
 *
 * 语义要点：
 *   solveDemand(需要 N 个 X) 按三条路顺序尝试，失败即回溯：
 *   ① 库存直接扣（单个替代物够数）
 *   ② tag 替代品聚合（多个变体按"预留未来单件需求"排序凑够 N）
 *   ③ 生产 X（枚举 recipesByOutput[X] 每个配方；netGain<=0 跳过；按净增益算批次）
 *
 * 输出：有序执行清单 List<PlannedStep(recipeId, batches)> + 缺失报告 + 剩余库存。
 */
public final class PureSearchPlanner {

    /** 护栏默认值（DESIGN-v2 §3.3 / HANDOFF §4.2）。 */
    public static final int DEFAULT_MAX_STEPS = 256;
    public static final int MAX_SEARCH_STATES = 65_536;
    public static final int MAX_CALL_DEPTH = 512;
    public static final int DEFAULT_MAX_MEMOIZED_FAILURES = 8_192;
    /** 默认规划时间预算：500ms（System.nanoTime 相对检查）。 */
    public static final long DEFAULT_DEADLINE_NANOS = 500_000_000L;

    public enum Status { SUCCESS, UNRESOLVABLE, STEP_LIMIT, SEARCH_LIMIT, TIME_LIMIT }

    /** 最终执行清单项：做 recipeId 配方 batches 次。 */
    public record PlannedStep(String recipeId, int batches) {}

    /** 规划结果。remaining 为执行后剩余库存（失败时为初始库存快照）。 */
    public record Result(boolean feasible, List<PlannedStep> steps,
                         List<MaterialRef> missing, Map<MaterialRef, Integer> remaining,
                         Status status, int expandedStates, int backtracks, int memoHits) {}

    private sealed interface Task permits DemandTask, CompleteRecipeTask {}

    /** 需要某槽位 ingredient.count() 个（替代物任意一个/聚合）。 */
    private record DemandTask(IngredientRef ingredient) implements Task {}

    /** 做 node 配方 batches 次；outputCount 每批产出、consumeCount 产出后立即抵扣的需求量。 */
    private record CompleteRecipeTask(RecipeNode node, int outputCount, int consumeCount,
                                      int batches) implements Task {}

    /** 失败状态记忆键：相同的 pending/stock/resolving/stepCount 视为同态，跳过重复探索。 */
    private record FailureKey(List<Task> pending, Map<MaterialRef, Integer> stock,
                              Set<MaterialRef> resolving, int stepCount) {}

    private PureSearchPlanner() {}

    /** 默认护栏参数入口（M2+ 调用）。 */
    public static Result resolve(ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available,
                                 List<IngredientRef> roots) {
        return resolve(graph, available, roots, DEFAULT_MAX_STEPS, MAX_SEARCH_STATES,
                DEFAULT_MAX_MEMOIZED_FAILURES, DEFAULT_DEADLINE_NANOS);
    }

    /**
     * 完整参数入口（默认允许跨层合成）。deadlineNanos 为相对预算；传 Long.MAX_VALUE 表示不限时（测试用）。
     */
    public static Result resolve(ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available,
                                 List<IngredientRef> roots, int maxSteps, int maxSearchStates,
                                 int maxMemoizedFailures, long deadlineNanos) {
        return resolve(graph, available, roots, maxSteps, maxSearchStates,
                maxMemoizedFailures, deadlineNanos, true);
    }

    /**
     * 完整参数入口 + 跨层开关。
     * allowCrossLayer=false 时只允许“背包原料直合”：目标或中间产物的配方输入必须直接从库存满足，
     * 不递归生产输入材料（DESIGN-v2 §3.7 allowCrossLayer）。
     */
    public static Result resolve(ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available,
                                 List<IngredientRef> roots, int maxSteps, int maxSearchStates,
                                 int maxMemoizedFailures, long deadlineNanos,
                                 boolean allowCrossLayer) {
        Search search = new Search(graph, available, maxSteps, maxSearchStates,
                maxMemoizedFailures, deadlineNanos, allowCrossLayer);
        List<Task> pending = roots.stream()
                .map(DemandTask::new).map(task -> (Task) task).toList();
        Status status;
        try {
            status = search.solve(pending) ? Status.SUCCESS
                    : search.stepLimitReached ? Status.STEP_LIMIT : Status.UNRESOLVABLE;
        } catch (SearchLimitException ignored) {
            status = Status.SEARCH_LIMIT;
        } catch (TimeLimitException ignored) {
            status = Status.TIME_LIMIT;
        }
        List<MaterialRef> missing = status == Status.SUCCESS || roots.isEmpty()
                ? List.of()
                : List.of(search.deepestFailure != null
                        ? search.deepestFailure
                        : roots.get(0).alternatives().get(0));
        List<PlannedStep> steps = status == Status.SUCCESS ? search.steps : List.of();
        Map<MaterialRef, Integer> remaining = status == Status.SUCCESS
                ? Map.copyOf(search.stock) : search.initialStock;
        return new Result(status == Status.SUCCESS, steps, missing, remaining, status,
                search.expandedStates, search.backtracks, search.memoHits);
    }

    // ------------------------------------------------------------------
    // Search：可回溯的搜索状态机
    // ------------------------------------------------------------------

    private static final class Search {
        private final ImmutableRecipeGraph graph;
        private final Map<MaterialRef, Integer> initialStock;
        private final Map<MaterialRef, Integer> stock = new HashMap<>();
        private final int maxSteps;
        private final int maxSearchStates;
        private final int maxMemoizedFailures;
        private final long deadlineNanos;            // 绝对截止（nanoTime）
        private final boolean allowCrossLayer;
        private final List<PlannedStep> steps = new ArrayList<>();
        private final Set<MaterialRef> resolving = new HashSet<>();
        private final Set<FailureKey> failedStates = new HashSet<>();
        private int expandedStates;
        private int backtracks;
        private int memoHits;
        private int callDepth;
        private int deepestPending = Integer.MAX_VALUE;
        private MaterialRef deepestFailure;
        private boolean stepLimitReached;

        private Search(ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available,
                       int maxSteps, int maxSearchStates, int maxMemoizedFailures,
                       long deadlineNanos, boolean allowCrossLayer) {
            this.graph = graph;
            available.forEach((material, count) -> {
                if (count != null && count > 0) stock.put(material, count);
            });
            initialStock = Map.copyOf(stock);
            this.maxSteps = Math.max(1, maxSteps);
            this.maxSearchStates = Math.max(1, maxSearchStates);
            this.maxMemoizedFailures = Math.max(0, maxMemoizedFailures);
            this.deadlineNanos = deadlineNanos == Long.MAX_VALUE
                    ? Long.MAX_VALUE : System.nanoTime() + deadlineNanos;
            this.allowCrossLayer = allowCrossLayer;
        }

        /** 递归入口：护栏检查（时间/深度）→ 状态展开。 */
        private boolean solve(List<Task> pending) {
            if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos) {
                throw new TimeLimitException();
            }
            if (++callDepth > MAX_CALL_DEPTH) {
                callDepth--;
                throw new SearchLimitException();
            }
            try {
                return solveWithinDepth(pending);
            } finally {
                callDepth--;
            }
        }

        private boolean solveWithinDepth(List<Task> pending) {
            if (++expandedStates > maxSearchStates) {
                throw new SearchLimitException();
            }
            if (pending.isEmpty()) {
                return true;
            }
            FailureKey key = new FailureKey(List.copyOf(pending), Map.copyOf(stock),
                    Set.copyOf(resolving), steps.size());
            if (failedStates.contains(key)) {
                memoHits++;
                return false;
            }
            Task current = pending.get(0);
            List<Task> rest = pending.subList(1, pending.size());
            boolean solved = current instanceof DemandTask demand
                    ? solveDemand(demand.ingredient(), rest, pending.size())
                    : completeRecipe((CompleteRecipeTask) current, rest);
            if (!solved && failedStates.size() < maxMemoizedFailures) {
                failedStates.add(key);
            }
            return solved;
        }

        /** 求解"需要 ingredient.count() 个某物品"：三条路顺序尝试、失败回溯。 */
        private boolean solveDemand(IngredientRef ingredient, List<Task> rest, int pendingCount) {
            // 路①：单个替代物库存直接够
            for (MaterialRef alternative : ingredient.alternatives()) {
                int have = stock.getOrDefault(alternative, 0);
                if (have < ingredient.count()) {
                    continue;
                }
                setStock(alternative, have - ingredient.count());
                if (solve(rest)) {
                    return true;
                }
                setStock(alternative, have);   // 回填
                backtracks++;
            }

            // 路②：tag 变体聚合（多个替代物凑够 N）
            Map<MaterialRef, Integer> consumed = consumeAcrossAlternatives(ingredient, rest);
            if (consumed != null) {
                if (solve(rest)) {
                    return true;
                }
                consumed.forEach(this::setStock);  // 回填
                backtracks++;
            }

            // 路③：生产 X
            for (MaterialRef wanted : ingredient.alternatives()) {
                if (resolving.contains(wanted)) {
                    continue;   // 环保护：正在求解的物品回边跳过
                }
                int have = stock.getOrDefault(wanted, 0);
                int needed = ingredient.count() - have;
                if (needed <= 0) {
                    continue;
                }
                for (RecipeNode candidate : graph.recipesFor(wanted)) {
                    if (steps.size() + scheduledRecipes(rest) >= maxSteps) {
                        stepLimitReached = true;   // 步骤护栏：跳过该分支
                        continue;
                    }
                    int selfConsumed = selfConsumedPerBatch(candidate, wanted);
                    int netGain = candidate.outputCount() - selfConsumed;
                    if (netGain <= 0) {
                        continue;   // no-gain 剪枝：防环/防无收益（如 2X→2X）
                    }
                    int batches = batchesFor(needed,
                            selfConsumed > 0 ? netGain : candidate.outputCount());
                    if (batches <= 0) {
                        continue;
                    }
                    int consumeCount = ingredient.count();
                    int scheduledBatches = batches;
                    List<Task> continuation = rest;
                    if (selfConsumed > 0) {
                        // 自耗配方：每批先吃掉 selfConsumed 个自身，最多吃 have 个；
                        // 产物先入库存（consumeCount=0），剩余需求重新入队求解。
                        scheduledBatches = Math.min(batches, have / selfConsumed);
                        if (scheduledBatches <= 0) {
                            continue;
                        }
                        consumeCount = 0;
                        continuation = new ArrayList<>(rest.size() + 1);
                        continuation.add(new DemandTask(ingredient));
                        continuation.addAll(rest);
                    }
                    if (!allowCrossLayer) {
                        // 仅背包原料直合：配方输入必须直接从库存满足，不再递归生产输入。
                        if (selfConsumed > 0) {
                            continue;   // 自耗类配方属于跨层/循环语义，直合模式不支持
                        }
                        List<Map<MaterialRef, Integer>> consumedInputs = new ArrayList<>();
                        boolean inputsOk = true;
                        for (IngredientRef input : candidate.inputs()) {
                            long scaledCount = (long) input.count() * scheduledBatches;
                            if (scaledCount > Integer.MAX_VALUE) {
                                inputsOk = false;
                                break;
                            }
                            IngredientRef scaled = new IngredientRef(
                                    input.alternatives(), (int) scaledCount);
                            if (!tryConsumeDirect(scaled, rest, consumedInputs)) {
                                inputsOk = false;
                                break;
                            }
                        }
                        if (!inputsOk) {
                            rollbackConsumed(consumedInputs);
                            continue;
                        }
                        List<Task> directBranch = new ArrayList<>(rest.size() + 1);
                        directBranch.add(new CompleteRecipeTask(candidate,
                                candidate.outputCount(), ingredient.count(), scheduledBatches));
                        directBranch.addAll(rest);
                        resolving.add(wanted);
                        if (solve(directBranch)) {
                            return true;
                        }
                        resolving.remove(wanted);
                        rollbackConsumed(consumedInputs);
                        backtracks++;
                        continue;
                    }
                    List<Task> branch = recipeBranch(candidate, scheduledBatches,
                            consumeCount, continuation);
                    if (branch == null) {
                        continue;
                    }
                    resolving.add(wanted);
                    if (solve(branch)) {
                        return true;
                    }
                    resolving.remove(wanted);
                    backtracks++;
                }
            }

            // 记录"最浅层失败"（pending 最少的那次），供缺失报告
            if (pendingCount < deepestPending) {
                deepestPending = pendingCount;
                deepestFailure = ingredient.alternatives().get(0);
            }
            return false;
        }

        /**
         * tag 替代品聚合：多个有货变体凑够 count。
         * 排序偏好"库存量 - 未来单件需求"大者优先，尽量把单件需求物留给后续精确消耗。
         * 仅一个贡献者时回退（交给路①），避免无意义的状态变更。
         */
        private Map<MaterialRef, Integer> consumeAcrossAlternatives(
                IngredientRef ingredient, List<Task> rest) {
            List<MaterialRef> stocked = ingredient.alternatives().stream()
                    .filter(material -> stock.getOrDefault(material, 0) > 0)
                    .sorted(Comparator
                            .comparingLong((MaterialRef material) ->
                                    (long) stock.getOrDefault(material, 0)
                                            - singletonDemand(rest, material))
                            .reversed()
                            .thenComparingInt(material -> -stock.getOrDefault(material, 0)))
                    .toList();
            if (stocked.size() < 2) {
                return null;
            }
            long total = 0L;
            for (MaterialRef material : stocked) {
                total += stock.getOrDefault(material, 0);
                if (total >= ingredient.count()) {
                    break;
                }
            }
            if (total < ingredient.count()) {
                return null;
            }
            int remaining = ingredient.count();
            int contributors = 0;
            Map<MaterialRef, Integer> previous = new HashMap<>();
            for (MaterialRef material : stocked) {
                int have = stock.getOrDefault(material, 0);
                int take = Math.min(have, remaining);
                if (take <= 0) {
                    continue;
                }
                previous.put(material, have);
                setStock(material, have - take);
                remaining -= take;
                contributors++;
                if (remaining == 0) {
                    break;
                }
            }
            if (contributors > 1) {
                return previous;
            }
            previous.forEach(this::setStock);
            return null;
        }

        /**
         * 直合模式：只从库存满足一个槽位需求（单替代物直接够，或多个替代物聚合）。
         * 成功时把变更记录追加到 rollbacks（供整体回滚），返回 true。
         */
        private boolean tryConsumeDirect(IngredientRef ingredient, List<Task> rest,
                                         List<Map<MaterialRef, Integer>> rollbacks) {
            // 单替代物直接够
            for (MaterialRef alternative : ingredient.alternatives()) {
                int have = stock.getOrDefault(alternative, 0);
                if (have >= ingredient.count()) {
                    rollbacks.add(Map.of(alternative, have));
                    setStock(alternative, have - ingredient.count());
                    return true;
                }
            }
            // 多替代物聚合
            Map<MaterialRef, Integer> previous = consumeAcrossAlternatives(ingredient, rest);
            if (previous != null) {
                rollbacks.add(previous);
                return true;
            }
            return false;
        }

        /** 按逆序回滚一组直合模式的库存变更。 */
        private void rollbackConsumed(List<Map<MaterialRef, Integer>> rollbacks) {
            for (int i = rollbacks.size() - 1; i >= 0; i--) {
                rollbacks.get(i).forEach(this::setStock);
            }
        }

        /** rest 任务里对该物品的"单件需求"总量（仅 alternatives 恰好等于该物品的 DemandTask）。 */
        private static long singletonDemand(List<Task> tasks, MaterialRef material) {
            long demand = 0L;
            for (Task task : tasks) {
                if (task instanceof DemandTask needed
                        && needed.ingredient().alternatives().size() == 1
                        && needed.ingredient().alternatives().get(0).equals(material)) {
                    demand = Math.min(Integer.MAX_VALUE,
                            demand + needed.ingredient().count());
                }
            }
            return demand;
        }

        /** 完成一个配方批次：产物入库存、抵扣需求、记录步骤；失败则整体回退。 */
        private boolean completeRecipe(CompleteRecipeTask completed, List<Task> rest) {
            MaterialRef output = completed.node().output();
            int before = stock.getOrDefault(output, 0);
            long afterProduction = (long) before
                    + (long) completed.outputCount() * completed.batches();
            if (afterProduction < completed.consumeCount()
                    || afterProduction > Integer.MAX_VALUE) {
                return false;
            }
            resolving.remove(output);
            setStock(output, (int) afterProduction - completed.consumeCount());
            steps.add(new PlannedStep(completed.node().recipeId(), completed.batches()));
            if (solve(rest)) {
                return true;
            }
            steps.remove(steps.size() - 1);
            setStock(output, before);
            resolving.add(output);
            backtracks++;
            return false;
        }

        /** 展开配方分支：每个输入 → 数量×batches 的 DemandTask，末尾接 CompleteRecipeTask + rest。 */
        private List<Task> recipeBranch(RecipeNode candidate, int batches, int consumeCount,
                                        List<Task> rest) {
            List<Task> branch = new ArrayList<>(candidate.inputs().size() + 1 + rest.size());
            for (IngredientRef input : candidate.inputs()) {
                long scaled = (long) input.count() * batches;
                if (scaled > Integer.MAX_VALUE) {
                    return null;
                }
                branch.add(new DemandTask(new IngredientRef(input.alternatives(), (int) scaled)));
            }
            branch.add(new CompleteRecipeTask(candidate, candidate.outputCount(),
                    consumeCount, batches));
            branch.addAll(rest);
            return List.copyOf(branch);
        }

        /** 需要 needed 个、每批产出 outputCount 个 → 批次数（向上取整，至少 1）。 */
        private static int batchesFor(int needed, int outputCount) {
            if (outputCount <= 0) {
                return -1;
            }
            long batches = ((long) needed + outputCount - 1L) / outputCount;
            return batches > Integer.MAX_VALUE ? -1 : (int) Math.max(1L, batches);
        }

        /** 该配方每批消耗"自身输出物"的数量（染色类自耗配方）。 */
        private static int selfConsumedPerBatch(RecipeNode candidate, MaterialRef output) {
            long consumed = 0L;
            for (IngredientRef input : candidate.inputs()) {
                if (input.alternatives().contains(output)) {
                    consumed += input.count();
                    if (consumed > Integer.MAX_VALUE) {
                        return Integer.MAX_VALUE;
                    }
                }
            }
            return (int) consumed;
        }

        /** pending 中已排定的配方步骤数（步骤护栏用）。 */
        private static int scheduledRecipes(List<Task> pending) {
            int count = 0;
            for (Task task : pending) {
                if (task instanceof CompleteRecipeTask) {
                    count++;
                }
            }
            return count;
        }

        private void setStock(MaterialRef material, int count) {
            if (count <= 0) {
                stock.remove(material);
            } else {
                stock.put(material, count);
            }
        }
    }

    /** 搜索状态超限（组合爆炸护栏）。 */
    private static final class SearchLimitException extends RuntimeException {
        private SearchLimitException() {
            super(null, null, false, false);
        }
    }

    /** 时间预算超限。 */
    private static final class TimeLimitException extends RuntimeException {
        private TimeLimitException() {
            super(null, null, false, false);
        }
    }
}
