# HANDOFF — autocraft M1（给新 agent 的交接包）

> 生成：2026-08-14 · 项目 autocraft · Forge 1.20.1 · 纯客户端 mod
> 任务：**M1 里程碑：数据结构 + 回溯搜索规划器 + 离线单测**（纯 Java，不碰 MC 运行时）

---

## 1. 项目是什么

**autocraft**（原 emi-autocraft，2026-08-13 改名）：玩家背包有全部原料时，对目标物品（如木稿）一键自动合成**整条配方树**（嵌套合成）——省略中间步骤（分解木板、合成木棍），直接从原木做出木稿。

- **依赖**：仅 JEI/EMI（客户端）；**不依赖 RS / 任何服务端 mod**。
- **形态**：纯客户端（bi-optional），公网服免装服务端。
- **范围 v1**：仅 `RecipeType.CRAFTING`（合成桌 2×2/3×3）。

## 2. 必读文档（按顺序）

1. **`DESIGN-v2.md`** —— 唯一现行完整设计（§0 定位 / §1 问题定义 / §2 架构 / §3 模块设计 / §4 决策 D1-D9 / §5 示例 / §6 里程碑 / §7 风险）。**实现以此为准。**
2. `auto-chain-crafting-design.md` —— v1 历史 + §8/§9 复盘（背景，为什么用回溯搜索）。
3. `_rsref/` —— rs_integration 源码快照（**仅学习算法思想，专有许可，严禁复制代码**）。

## 3. 三条红线（必须遵守）

1. **命名**：包 `com.adimn.autocraft`、主类 `AutoCraft`、modid `autocraft`、archivesBaseName `autocraft`（已迁移完毕，勿改回）。
2. **许可**：rs_integration = 专有许可（Proprietary License, All rights reserved）——只借鉴**算法思想**（回溯搜索/图结构/搜索护栏），**零代码复制**（其类名/结构可参考，代码必须自写）。
3. **依赖**：M1 是纯 Java 算法层，**不 import 任何 MC/Forge/EMI 类**（可离线单测）；只有 M2+ 才接入 `Minecraft.getInstance().level.getRecipeManager()`。

## 4. M1 任务清单（详细）

### 4.1 数据结构（新包 `com.adimn.autocraft.plan`，纯 record）

```java
record MaterialRef(ResourceLocation itemId, String nbt)              // v1: nbt 恒 ""
record IngredientRef(List<MaterialRef> alternatives, int count)      // tag 多解 = alternatives 全展开
record RecipeNode(ResourceLocation recipeId, MaterialRef output, int outputCount,
                  List<IngredientRef> inputs)                        // 配方节点
record ImmutableRecipeGraph(
    Map<MaterialRef, List<RecipeNode>> recipesByOutput,              // 物品 → 产出配方
    Map<ResourceLocation, RecipeNode> recipesById)                   // id → 节点
```

- 注意：`ResourceLocation` 是 MC 类——M1 若要保持纯 Java，可用 `String` 存 id（`"minecraft:oak_log"`），M2 接 MC 时再换；或在测试里用 MC 的命名空间字符串模拟。**建议 M1 用 String，零 MC 依赖。**
- 图提供构建方法：`ImmutableRecipeGraph.build(Map<String, List<RecipeNode>>)` 或逐节点添加。

### 4.2 规划器 `PureSearchPlanner`（回溯搜索，核心）

状态：
```java
Map<MaterialRef, Integer> stock         // 库存快照（实时扣减/回填）
Deque<Task> pending                      // 任务队列
Set<MaterialRef> resolving               // 正在求解的物品（环保护）
List<PlannedStep> steps                  // 已确定步骤
Set<FailureKey> failedStates             // 失败状态记忆
```

任务：
```java
sealed interface Task permits DemandTask, CompleteRecipeTask
record DemandTask(IngredientRef ingredient)
record CompleteRecipeTask(RecipeNode node, int outputCount, int consumeCount, int batches)
record PlannedStep(ResourceLocation recipeId, int batches)          // 或 String recipeId
```

求解（`solveDemand(需要 N 个 X)`，三条路顺序尝试、失败回溯）：
1. **库存直接扣**：`stock[X] >= N` → 扣减，继续。
2. **tag 替代品聚合**：alternatives 中多个有货变体凑够 N（按"预留未来单件需求"排序取）。
3. **生产 X**：枚举 `recipesByOutput[X]` 每个配方：
   - `selfConsumed` = 配方输入中消耗自身 X 的数量；`netGain = outputCount - selfConsumed`
   - `netGain <= 0` → 跳过该配方（**no-gain 剪枝**，防环/防无收益）
   - `batches = ceil(needed / (有自耗 ? netGain : outputCount))`
   - 分支 = 每个输入 → 新 `DemandTask(count×batches)` + `CompleteRecipeTask` → 递归
   - 失败 → 回溯试下一个配方/下一条路

环保护：`resolving` 集合（回边跳过）+ 自耗净增益剪枝（上）。

护栏（**先实现，再写主体**）：
```java
maxSteps = 256            // 执行步骤上限
maxSearchStates = 65536   // 搜索状态展开上限
maxCallDepth = 512        // 递归深度
deadlineNanos = 500ms     // 时间预算（System.nanoTime 检查）
maxMemoizedFailures = 8192
```

结果：
```java
record Result(boolean feasible, List<PlannedStep> steps,
              List<MaterialRef> missing, Map<MaterialRef, Integer> remaining,
              Status status /* SUCCESS/UNRESOLVABLE/STEP_LIMIT/SEARCH_LIMIT/TIME_LIMIT */,
              int expandedStates, int backtracks, int memoHits)
```

### 4.3 离线单测（JUnit，纯 Java）

5 个必测场景（`src/test/java/com/adimn/autocraft/plan/`）：
1. **多层**：原木→木板→木棍→木稿（数量 ceil 正确：1 原木→4 木板；2 木板→4 木棍；木稿 = 2 木棍+1 木板）。
2. **多配方**：箱子有 N 种木板组合 → 搜索选可行分支（某个木板缺料时自动换另一个）。
3. **环**：A→B 且 B→A 的等价互转（如羊毛染色）→ 不无限递归，优雅 UNRESOLVABLE 或跳过。
4. **缺失**：背包缺中间料 → INFEASIBLE + missing 正确报告。
5. **护栏**：深树/多分支构造 → 不超预算（SEARCH_LIMIT/TIME_LIMIT 优雅返回，不抛异常不卡死）。

## 5. 验收标准（M1 完成 = 以下全绿）

- [ ] `ImmutableRecipeGraph` 构建正确（recipesByOutput/recipesById）
- [ ] 5 个单测场景全部通过
- [ ] `./gradlew test --offline` 可跑（若 MC 依赖编译不过，单测可独立于 MC 源码放单独 module 或 main 里纯 Java 类）
- [ ] 无 MC/Forge/EMI import（M1 层）

## 6. 后续里程碑（M1 之后，先不做）

M2 RecipeIndex 接客户端 RecipeManager → M3 CraftExecutor 驱动合成桌 → M4 JEI/EMI 触发 → M5 GUI → M6 Config+打磨。详见 DESIGN-v2.md §6。
