# EMI 自动合成附属模组（emi-autocraft）设计规格

> 目标：做一个 **EMI 的 Forge 1.20.1 附属模组**，把 EMI 配方树合成模式的"逐行点 `+`"变成"一个键自动走完整棵树"。
> 玩家体验：在 EMI 里打开目标物品的配方树 → 按一个快捷键 → 模组以**人速**（可配置延迟）自动把整条链跑完，玩家不用再手动点每一步。
> **不重新发明轮子**：完全复用 EMI 内部的 `BoM` 树 + `EmiFavorites.syntheticFavorites` 列表 + `EmiRecipeFiller.performFill`，本模组只是"自动点那个 `+`"。

---

## 0. 为什么做这个（已与用户确认）

- 用户玩整合包，作者把中间产物改得"又臭又长"。明明背包有全部原材料，却要手动走一堆合成步骤 → **手腕/鼠标负担**。
- EMI 配方树模式能展示整条链、且每步帮你填格+合成，但**仍要玩家逐行点 `+`**。
- 用户明确："会自动填配方、不自动合成也行" → 即**放弃一键无人值守**，但只要"别让我手动摆材料"。
- 本附属模组 = 把"逐行点 `+`"自动化，**节奏仍是人速**（每步间有可配置延迟），因此**不触发反作弊宏检测**（详见 §5）。

---

## 1. 查证的 EMI 内部 API（全部来自 `emilyploszaj/emi` 源码，非猜测）

> 仓库已 clone 在工作区 `emi/`（默认分支 1.21.1，但 `xplat` 代码跨 1.20.1 backport 同结构，Pi 实现时用 1.20.1 版本）。

| 用途 | 类名 / 方法 | 签名 / 行为 | 源码定位 |
|---|---|---|---|
| **建树入口** | `dev.emi.emi.bom.BoM.setGoal(EmiRecipe recipe)` | `tree = new MaterialTree(recipe); craftingMode = false;` | `bom/BoM.java:168` |
| 树对象 | `BoM.tree` | `public static MaterialTree tree;`（字段 `goal` 存目标配方，Pi scout 确认字段名） | `bom/BoM.java:29` |
| 树合成模式开关 | `BoM.craftingMode` | `public static boolean craftingMode = false;` | `bom/BoM.java:33` |
| 配方抉择 / 叶子约束 | `BoM.getRecipe(EmiIngredient)` | 返回该原料选用的配方（`addedRecipes` 用户默认 > `defaultRecipes` > 排除禁用）。即用户"只有原木和钻石"的偏好落点 | `bom/BoM.java:157` |
| 钉死某原料默认配方 | `BoM.addResolution(EmiIngredient, EmiRecipe)` | `tree.addResolution(...)`。用户预置叶子约束的 API | `bom/BoM.java:173` |
| **重算合成清单** | `EmiFavorites.updateSynthetic(EmiPlayerInventory inv)` | 清空并依据 `BoM.tree` + `BoM.craftingMode` 重建 `syntheticFavorites` 列表 | `runtime/EmiFavorites.java:192` |
| **驱动源列表** | `EmiFavorites.syntheticFavorites` | `public static List<EmiFavorite.Synthetic>`。元素两构造：`Synthetic(EmiRecipe,long,long,long,int)`（recipe 型，可合成步骤）/ `Synthetic(EmiIngredient,long,long)`（ingredient 型，原材料展示行）。`recipe` 字段来自基类 `EmiFavorite.recipe`（`protected final`），经 `getRecipe()` 读；`state` 是 **`int`**（非枚举，见下） | `runtime/EmiFavorites.java:33` |
| **单步合成（核心）** | `EmiRecipeFiller.performFill(EmiRecipe recipe, AbstractContainerScreen<T> screen, EmiCraftContext.Type type, EmiCraftContext.Destination destination, int amount)` | `public static` 返回 `boolean`。入参为 **`AbstractContainerScreen<T>`**（mojmap 名，非 Forge 的 `HandledScreen`）。内部 `getFirstValidHandler`→`handler.canCraft`→`handler.craft`。**这是本模组唯一要循环调用的入口** | `registry/EmiRecipeFiller.java:116` |
| 单步合成底层 | `EmiRecipeFiller.clientFill(...)` | 用 `manager.clickSlot(...)` 模拟**真实点击**移料+取成品（服务端权威、任何服可用） | `registry/EmiRecipeFiller.java:287` |
| 取当前界面 | `EmiApi.getHandledScreen()` | 返回当前 `HandledScreen`（兼容 InventoryScreen / RecipeScreen / BoMScreen） | `api/EmiApi.java:106` |
| 取悬停物 | `EmiApi.getHoveredStack()` / `getRecipeContext(EmiIngredient)` | 拿 EMI UI 里鼠标悬停的物品 → 取其配方上下文 | `api/EmiApi.java:82,99` |
| 进度状态 | `ProgressState` 枚举（EMI 内部） | `UNSTARTED`(0) / `PARTIAL`(1) / `COMPLETED`(2) 三个序数 | `bom/ProgressState.java:3` |
| ⚠️ **`Synthetic.state` 是 `int` 字段（非枚举）** | `EmiFavorite.Synthetic.state` | `public final int state;` —— **存 ProgressState 的序数值**：`-1`=ingredient 型原材料展示行、`0`=UNSTARTED（材料未齐，暂不可合成）、`1`=PARTIAL、`2`=COMPLETED。驱动时只 `performFill` `state >= 1` 的条目（`state < 1` 跳过）。`getRecipe()` 来自基类 `EmiFavorite` | `runtime/EmiFavorite.java`（`EmiFavorite$Synthetic extends EmiFavorite`，`recipe` 为 `protected final`） |
| 合成目标类型/去向 | `EmiCraftContext.Type` / `.Destination` | 枚举（如 `Destination.INVENTORY` / `CURSOR`）。**Pi scout 读取 `api/recipe/handler/EmiCraftContext.java` 确认精确值** | `api/recipe/handler/EmiCraftContext.java` |

### 关键事实（决定架构）
1. **`performFill` 是公开静态方法**，但所在类 `EmiRecipeFiller` 在 EMI **内部包**（`registry`），**不在稳定 `api` 包**。`BoM`/`EmiFavorites`/`MaterialTree`/`ProgressState`/`EmiCraftContext` 同理。
2. 稳定 `api` 包只暴露 `EmiApi.viewRecipeTree()`（打开 GUI），**不暴露读树、不暴露整树自动合成**。所以本模组**必须依赖 EMI 内部包** → 必须**锁定 EMI 版本**（见 §6）。
3. `performFill` 底层是真实 `clickSlot`，所以服务端看到的是合法点击——**唯一风险是速率（宏检测）**，由 §5 的延迟解决。

---

## 2. 模组架构（Forge 1.20.1 客户端附属）

```
src/main/java/com/adimn/emiautocraft/
├── EmiAutoCraft.java          # @Mod 主类：注册 KeyMapping + client tick 监听
├── craft/
│   ├── TreeDriver.java        # 核心：updateSynthetic → 扫 syntheticFavorites → performFill 循环
│   └── CraftState.java       # 驱动状态机（IDLE / DRIVING / DONE / NEED_MATERIALS）
├── input/
│   └── KeyHandler.java       # 快捷键按下=启动，松开/关界面=停止
├── compat/
│   └── EmiGuard.java        # 运行时检测 EMI 是否存在（类加载守卫，避免无 EMI 时崩溃）
└── config/
    └── Config.java           # delayTicks（默认 20≈1s）、enableKey、stopOnFull
resources/META-INF/mods.toml
build.gradle                    # implementation files('libs/emi-1.1.24+1.20.1+forge.jar')，锁定版本（不用 fg.deobf）
```

### 2.1 启动一棵树的序列（核心算法）
```
onKeyPress():
  if (!EmiGuard.present()) { chat("需要安装 EMI"); return; }
  AbstractContainerScreen<?> screen = EmiApi.getHandledScreen();
  if (screen == null || !hasValidHandler(screen)) {
      chat("请打开合成台/背包后重试"); return;   // performFill 需要有效 handler
  }
  // 目标配方：优先用已打开的树目标，否则用悬停物
  EmiRecipe goal = currentGoalOrHovered();
  if (goal == null) { chat("在 EMI 中打开目标物品配方树，或悬停它再按"); return; }
  BoM.setGoal(goal);          // tree = new MaterialTree(goal)
  BoM.craftingMode = true;
  state = DRIVING;

onClientTick():                  // 必须在客户端线程，不能阻塞
  if (state != DRIVING) return;
  if (--cooldown > 0) return;          // 反作弊节流（§5）
  cooldown = Config.delayTicks();
  EmiPlayerInventory inv = currentInv();
  EmiFavorites.updateSynthetic(inv);     // 背包变了→重算→解锁更深层
  // 找第一个"可合成步骤"条目
      for (EmiFavorite.Synthetic e : EmiFavorites.syntheticFavorites) {
          if (e.getRecipe() == null) continue;            // 跳过原材料展示型条目（recipe 来自基类）
          if (e.state < 1) continue;                      // int 比较：-1=原材料行 / 0=未齐 / 仅 performFill state>=1
          boolean ok = EmiRecipeFiller.performFill(
              e.getRecipe(), screen, EmiCraftContext.Type.CRAFTABLE,
              EmiCraftContext.Destination.INVENTORY, amount);
          if (ok) return;                  // 本 tick 只做一步，下一步下个 tick
      }
  // 一轮没合成任何东西 → 要么完成，要么缺原材料
  if (noCraftableLeft()) state = DONE;   // 或 NEED_MATERIALS（提示缺料）
```

### 2.2 条目类型区分（Pi scout 必读）
`EmiFavorite.Synthetic` 是联合结构：
- recipe 型：`new EmiFavorite.Synthetic(recipe, batch, amount, originalAmount, state)`（`EmiFavorites.java:223`）→ 可合成步骤。
- ingredient 型：`new EmiFavorite.Synthetic(cost.ingredient, cost.amount, ...)`（`:230`/`:242`）→ "你需要这些原材料"展示行。
**驱动时只处理 `e.getRecipe() != null` 的条目（`recipe` 字段来自基类 `EmiFavorite`，经 `getRecipe()` 读）。**

### 2.3 终止条件
- `syntheticFavorites` 中无 `getRecipe() != null && state >= 1` 的条目 → 完成（或提示缺原材料）。
- 玩家松开快捷键 / 关闭界面 / 背包满（`Config.stopOnFull`）→ 停止。

---

## 3. 叶子约束（完全复用 EMI，不重写）
用户"只有原木和钻石"的偏好 = 在 EMI 里把相关原料**钉成默认配方**（`BoM.addResolution(ingredient, recipe)` 或 EMI UI 的"设为默认"）。`MaterialTree` 建树时通过 `BoM.getRecipe(ingredient)` 读取这些默认，**本模组自动继承**，无需新 UI。
- 可选增强：给本模组加一个"把悬停配方钉为该原料默认"的副键，调用 `BoM.addResolution`。（v1.1 功能）

---

## 4. 反作弊设计（公网服硬性要求）
- `performFill` 底层是真实 `clickSlot`，服务端看到合法点击；**唯一风险是速率**。
- 驱动循环**每个客户端 tick 最多执行一步合成**，步间 `cooldownTicks = Config.delayTicks`（默认 20 tick ≈ 1 秒）。
- 实现在 `clientTick` 事件里用**倒计时**推进，**绝不 `Thread.sleep`**（会冻结游戏主线程）。
- `delayTicks` 做成**配置文件项**，用户按自己服的反作弊宽松度调（严的服调到 30–40 tick）。
- 对比风险：EMI 原生树模式是人手点每步（同样安全）；本模组只是把"人手点"换成"机器以人速点"，速率可控 → 不越线。

---

## 5. 依赖与版本锁定（最易翻车处）
- 需要 EMI **完整 jar**（不是 `:api` 瘦身包），因为要用内部包 `bom`/`registry`/`runtime`。
- `build.gradle`：
  ```gradle
  dependencies {
      minecraft 'net.minecraftforge:forge:1.20.1-47.4.0'
      // EMI 1.1.24 Forge 的 jar 内部类已是官方(mojmap)名（javap 核实：net/minecraft/client/gui/screens 复数包即 mojmap），
      // 与本项目 mappings channel:'official' 一致，故直接 files() 引入，无需 fg.deobf（fg.deobf 对 files() 会失败报
      // "Cannot deobfuscate" 并污染 forge 编译 classpath）。升级 EMI 须同步回归 + 重钉此版本。
      implementation files('libs/emi-1.1.24+1.20.1+forge.jar')
  }
  ```
- EMI 版本必须**锁定确切的 1.20.1 Forge 构建**（当前 `emi-1.1.24+1.20.1+forge.jar`），放进 `libs/` 并用 `files(...)` 引入。**升级 EMI 须同步回归**（见 §10.4）。
- EMI 是本模组的**硬运行时依赖**（没装 EMI 就别加载驱动逻辑）——`EmiGuard` 类加载守卫处理此情况。
- **版本漂移风险**：EMI 内部包无稳定契约，EMI 更新若重命名/改签名，本模组会编译/运行失败 → 必须随 EMI 版本重新钉依赖并回归测试。

---

## 6. 风险登记
| 风险 | 状态 / 缓解 |
|---|---|
| EMI 内部 API 无稳定契约，升级即碎 | 锁定版本 `emi-1.1.24+1.20.1+forge`；`EmiGuard` 守卫；升级时按 §10.4 回归 |
| `EmiFavorite.Synthetic` 字段精确名 / `state` 类型 | **已核实（javap-11）**：`state` 是 `int`（非 `ProgressState` 枚举），`recipe` 来自基类 `EmiFavorite`，经 `getRecipe()` 读 |
| `EmiCraftContext.Type` 精确取值 | **已核实**：用 `EmiCraftContext.Type.CRAFTABLE` + `Destination.INVENTORY` |
| `performFill` 的 `amount` 语义 | **已核实**：`int amount` = 批次数（`e.batches`），底层 `clickSlot` 真实点击 |
| `fg.deobf` 对 EMI jar 失败 | **已翻车并修正**：EMI 1.20.1 jar 已是 mojmap，改用 `implementation files(...)` 不用 `fg.deobf`（详见 §5/§10.4） |
| Forge 1.20.1 类包路径重定位 | **已翻车并修正**：`MinecraftForge`/`ClientTickEvent`/`KeyMapping` 真实包见 §10.2 |
| javap 工具链陷阱 | **已踩坑**：须 `javap-11`（非 17）；`unzip -l` 读 jar（非 `jar tf`） |
| 公网服反作弊 | §4 节流；`delayTicks` 默认 20 可配 |
| 背包满 / 无进展卡死 | 连续 3 tick 无进展 → `NEED_MATERIALS` 停止；`Config.stopOnFull` 思路 |
| **同一类同时注册到 Mod 总线 + 游戏总线会崩** | **已翻车并修正**：`KeyHandler` 曾同时含 `RegisterKeyMappingsEvent`（IModBusEvent，属 Mod 总线）+ `onClientTick(ClientTickEvent)`（属游戏总线），被 `modBus.register(KeyHandler.class)` 触发 `IllegalArgumentException: ...not a subtype of IModBusEvent`。**修复：拆分**——`KeyMappings`（仅 `RegisterKeyMappingsEvent`）注册到 `modBus`，`KeyHandler`（仅 `ClientTickEvent`）注册到 `MinecraftForge.EVENT_BUS`；`DRIVE/PIN` 字段升 `public static` 供 `KeyMappings` 引用。Forge 1.20.1 铁律：**Mod 总线只接受 IModBusEvent 子类**，`TickEvent`/`ClientTickEvent` 等全是游戏总线事件。 |

---

## 7. Pi 四段实现提示词（用户本地编译）

### 7.1 scout（侦查，输出 API cheat-sheet）
> 你是对 EMI 源码做静态侦查的子代理。读取工作区 `emi/` 仓库（默认分支 1.21.1，但关注 `xplat` 跨版本通用部分；实现时用 1.20.1）。
> 必须确认并输出精确签名/字段：
> 1. `bom/MaterialTree.java`：目标配方字段名（文档推测为 `goal`，确认是否 `public EmiRecipe goal`）。
> 2. `runtime/EmiFavorites.java` 的 `EmiFavorite.Synthetic` 内部类：全部字段名与类型（特别是区分 recipe 型 vs ingredient 型的字段，以及 `state` 字段类型 `ProgressState`）。
> 3. `api/recipe/handler/EmiCraftContext.java`：`Type` 与 `Destination` 枚举的**全部枚举值**（如 `INVENTORY`/`CURSOR` 用于 Destination；Type 的取值）。
> 4. `registry/EmiRecipeFiller.java` 的 `performFill`：`amount` 参数语义（一次做几份？与 `handler.craft` 的关系）。
> 5. 从 Modrinth / CurseForge 确认 **EMI 最新的 1.20.1 Forge 版本字符串**（用于 `build.gradle` 依赖锁定）。
> 输出一份带 `文件:行号` 的 cheat-sheet，供 planner/worker 使用。不要写任何实现代码。

### 7.2 planner（架构，输出模块骨架 + 算法伪码）
> 基于 §1–§6 与本回合确认的 API cheat-sheet，产出 Forge 1.20.1 附属模组骨架：
> - `build.gradle`（含 EMI 完整 jar 依赖锁定、Forge 1.20.1 MDK 约定）、`mods.toml`、`gradle.properties`。
> - 五个包的类签名与职责（§2 结构），重点给出 `TreeDriver.onClientTick` 的完整伪码（含 updateSynthetic → 扫描 → performFill 单步 → 倒计时节流）。
> - `EmiGuard` 类加载守卫写法（`Class.forName("dev.emi.emi.bom.BoM")` 探测）。
> - `Config` 的 `delayTicks` 默认值与读取方式。
> 不要写完整实现，只给可落地的架构与接口契约。

### 7.3 worker（实现）
> 按 planner 骨架 + scout cheat-sheet 实现全部类。硬性要求：
> - 所有 EMI 内部调用必须对照 cheat-sheet 的精确签名，禁止臆测方法名。
> - 反作弊节流必须在 `clientTick` 里用倒计时实现，**禁止 `Thread.sleep`**。
> - `EmiGuard` 守卫：无 EMI 时本模组安静禁用，绝不抛异常崩溃。
> - 快捷键用 Forge `KeyMapping` 注册，按下启动、松开停止。
> 完成后给出本地 `gradlew build` 的产物路径。

### 7.4 reviewer（验收）
> 审查 worker 产物：
> 1. `gradlew build` 必须成功（EMI 依赖已锁定且能解析）。
> 2. 逐条核对：是否误用了 `api` 包之外的臆测 API（应全部来自 cheat-sheet）。
> 3. 反作弊延迟确实存在且默认人速（≥20 tick）。
> 4. `EmiGuard` 在缺 EMI 时不会崩溃。
> 5. 终止条件完备（完成 / 缺料 / 松键 / 关界面 / 背包满）。
> 给出"可编译通过 + 风险点"清单，交用户本地实测。

---

## 8. 状态
- 本文档是**架构规格**，交由 Pi 子代理链（scout→planner→worker→reviewer）实现，用户本地编译。
- 前身 `auto-chain-crafting-design.md`（从零写解析器）已**封存**——本方案证明"直接驱动 EMI 内部"即可，无需重写配方树解析器。

---

## 9. 已确认范围（用户 2026-07-17 拍板，Pi 实现时严格遵守）

| # | 分叉 | 决策 | 落点 |
|---|---|---|---|
| 1 | 触发形态 | **全局快捷键**（`KeyMapping` 按下启动 / 松开停止），按文档 §2.1 原样实现，**不改** | §2.1 `onKeyPress`/`onClientTick` |
| 2 | "钉悬停配方为默认"副键 | **v1.0 即做**（非原文档写的 v1.1）。新增一个副键，悬停某原料配方时按 → 调 `BoM.addResolution(ingredient, recipe)` 钉成该原料默认 | §3 末段由"v1.1"改为"v1.0 功能" |
| 3 | 作用界面范围 | **合成台界面 + 背包界面都触发**。`onKeyPress` 里 `hasValidHandler(screen)` 须同时认 `CraftingScreen` 与 `InventoryScreen`（及 EMI 的 `RecipeScreen`/`BoMScreen`），两者其一有效即可启动 | §2.1 判定分支 |

### 9.1 执行顺序须知（源码查证，非假设）
- EMI 配方树**自成品向下建、自原材料向上做**（叶子优先）。`TreeDriver` **无需自写拓扑排序**：每 tick 调 `EmiFavorites.updateSynthetic(inv)` 重算 → 只 `performFill` `state==2`（整批可合成）的条目；缺依赖的会 `canCraft=false` 自然失败 → 跳过，下轮重算后更深节点解锁。
- **多收藏不合并**：`updateSynthetic` 只处理当前 `BoM.tree`（单个 goal）。用户收藏栏标了 N 个成品 = N 棵独立树，合成模式一次一棵。本模组一次按键驱动"当前 `BoM.tree`"；要做多成品需用户逐个触发（或留作未来增强，不在 v1 范围）。
- **单成品多路径**：`BoM.setGoal(recipe)` 取具体一条为根；子材料各经 `BoM.getRecipe` 选一条（用户默认 > 默认），`used.contains(recipe)` 防环。无并行树、无多根。

---

## 10. As-Built 真实 API 契约（javap-11 权威，v1.0.0 落实于 2026-07-24）

> 本节是从 EMI 1.1.24 Forge (1.20.1) 真实字节码反查得到的**权威签名**，作为未来升级 EMI 的回归基线。1.1.24 与 1.1.22 签名一致，故本地实测时直接升级到 1.1.24。
> 工具链教训：`jar tf`（jdk-17）对 EMI jar 返回 0 条目（工具 bug），须用 `unzip -l`；`javap-17` 对部分 EMI 类（尤其 `EmiFavorite$Synthetic`）报"找不到类"，须用 **`C:/Program Files/Java/jdk-11/bin/javap.exe`**。

### 10.1 EMI 1.1.24 Forge (1.20.1) 已核实签名
| 类 | 成员 | 签名（javap-11 输出） |
|---|---|---|
| `dev.emi.emi.bom.BoM` | `tree` | `public static MaterialTree tree;` |
|  | `craftingMode` | `public static boolean craftingMode;` |
|  | `setGoal(EmiRecipe)` | `public static void setGoal(dev.emi.emi.api.recipe.EmiRecipe);` |
|  | `addResolution(EmiIngredient, EmiRecipe)` | `public static void addResolution(dev.emi.emi.api.stack.EmiIngredient, dev.emi.emi.api.recipe.EmiRecipe);` |
| `dev.emi.emi.api.EmiApi` | `getHoveredStack(boolean)` | `dev.emi.emi.api.stack.EmiStackInteraction` |
|  | `getHandledScreen()` | `net.minecraft.client.gui.screens.inventory.AbstractContainerScreen` |
| `dev.emi.emi.api.recipe.EmiPlayerInventory` | `of(Player)` | `public static EmiPlayerInventory of(net.minecraft.world.entity.player.Player);` |
| `dev.emi.emi.registry.EmiRecipeFiller` | `performFill(...)` | `public static boolean performFill(EmiRecipe, AbstractContainerScreen<T>, EmiCraftContext$Type, EmiCraftContext$Destination, int);` |
| `dev.emi.emi.runtime.EmiFavorites` | `syntheticFavorites` | `public static java.util.List<EmiFavorite$Synthetic> syntheticFavorites;` |
|  | `updateSynthetic(EmiPlayerInventory)` | `public static void updateSynthetic(dev.emi.emi.api.recipe.EmiPlayerInventory);` |
| `dev.emi.emi.runtime.EmiFavorite` | `recipe` | `protected final dev.emi.emi.api.recipe.EmiRecipe recipe;` |
|  | `getRecipe()` | `public dev.emi.emi.api.recipe.EmiRecipe getRecipe();` |
| `dev.emi.emi.runtime.EmiFavorite$Synthetic` | 字段 | `public final long batches; public final long amount; public final int state; public final long total;` |
|  | 构造 | `Synthetic(EmiRecipe,long,long,long,int)`（recipe 型）/ `Synthetic(EmiIngredient,long,long)`（ingredient 型） |
| `dev.emi.emi.api.stack.EmiStackInteraction` | `getStack()` | `public dev.emi.emi.api.stack.EmiIngredient getStack();`（**无 `getIngredient()`**） |
|  | `getRecipeContext()` | `public dev.emi.emi.api.recipe.EmiRecipe getRecipeContext();` |
| `dev.emi.emi.bom.ProgressState` | 枚举 | `UNSTARTED`(0) / `PARTIAL`(1) / `COMPLETED`(2)——但 `Synthetic.state` 是 `int` 存其序数值 |

> ⚠️ 包路径重定位（与旧版/记忆不同，**已核实**）：`EmiFavorite`/`EmiFavorites`/`EmiFavorite$Synthetic` 在 `dev.emi.emi.runtime`（非 `dev.emi.emi.favorite`）；`EmiCraftContext` 在 `dev.emi.emi.api.recipe.handler`（非 `dev.emi.emi.api`）；`EmiStackInteraction` 在 `dev.emi.emi.api.stack`（非 `...screen`）。

### 10.2 Forge 1.20.1-47.4.0 真实包路径（prior 记忆有误，已修正）
| 误记 | 真实 |
|---|---|
| `net.minecraftforge.MinecraftForge` | `net.minecraftforge.common.MinecraftForge` |
| `net.minecraftforge.client.event.ClientTickEvent` | `net.minecraftforge.event.TickEvent.ClientTickEvent`（且 `event.phase` / `Phase.END`） |
| `net.minecraftforge.client.settings.KeyMapping` | `net.minecraft.client.KeyMapping` |
| `RegisterKeyMappingsEvent` | `net.minecraftforge.client.event.RegisterKeyMappingsEvent`（这个本来就对） |

### 10.3 编译结果（2026-07-25）
- `./gradlew clean build` → **BUILD SUCCESSFUL**（ForgeGradle 6.0.+, mappings `official 1.20.1`, forge `1.20.1-47.4.0`，EMI 升级为 `1.1.24`）。
- 产物：`build/libs/emiautocraft-1.0.0.jar`（含 8 个类 + `mods.toml` + `pack.mcmeta`；新增 `util/Log` 日志类 + `ui/RecipeScreenButtonHandler` 按钮类）。
- 三分支全部落地：① 全局快捷键 DRIVE（默认 B，按下启动 / 松开停止）；② PIN（默认 N，悬停配方 `BoM.addResolution` 钉默认，v1.0 即做）；③ 合成台 + 背包界面（经 `EmiApi.getHandledScreen` 自动识别有效 `AbstractContainerScreen`）均触发。
- 反作弊节流：`clientTick` 倒计时，`cooldown = Config.delayTicks()`（默认 20），每 tick 最多一步，**无 `Thread.sleep`**。
- `EmiGuard`：`Class.forName` 探测 6 个 EMI 类（`BoM`/`MaterialTree`/`EmiFavorites`/`EmiFavorite`/`EmiRecipeFiller`/`EmiApi`），无 EMI 时安静禁用，不抛异常。
- **关键调用链修正**：`TreeDriver.start()` 在 `BoM.setGoal(recipe)` 与 `BoM.craftingMode = true` 之后，必须显式调用 `EmiFavorites.updateSynthetic(EmiPlayerInventory.of(player))`，否则 `syntheticFavorites` 为空，驱动会立即结束。

### 10.4 升级 EMI 的回归清单
1. 换 `libs/emi-*.jar` + 改 `build.gradle` 的 `implementation files(...)` 版本字符串。
2. 用 javap-11 重查 §10.1 全部签名（尤其 `performFill` 参数类型、`Synthetic` 字段、`EmiFavorites.syntheticFavorites` 类型）。
3. `./gradlew build` 必须成功，否则对照本表定位签名漂移。
4. **禁止 `fg.deobf`**（EMI 1.20.1 jar 已是 mojmap）；若未来 EMI 改用 SRG 名 jar，再评估。
5. 本地实测：打开一棵配方树 → 按 B 应逐行自动合成（人速）→ 松开 B 停止；悬停某原料配方按 N 应钉为默认。

### 10.5 日志系统（2026-07-25 新增）
- 新增 `util/Log`（轻量文件日志，**默认启用**），不依赖第三方日志框架。
- **落盘位置**：`<整合包版本文件夹（游戏目录）>/logs/emiautocraft.log`，即各启动器（CurseForge / Prism 等）每个实例独立的 `.minecraft/logs`。路径经 `Minecraft.getInstance().gameDirectory.toPath().resolve("logs")` 取得（`gameDirectory` 为 1.20.1 公开 `File` 字段，已编译核实）。
- 同时回显 `System.out`，会一并进入 MC 自身的 `logs/latest.log`；文件不可用时仍有控制台记录。
- 配置项（写入 `config/emiautocraft-client.toml`，均可改）：
  - `logEnabled`（默认 **true**）—— 关闭则完全不写。
  - `logDebug`（默认 **false**）—— 开启才记录步骤级 DEBUG（`performFill`、每 tick 摘要），避免日常刷屏。
- 日志点：模组初始化 + EMI 存在性、DRIVE 启动/停止、PIN 钉默认（成功 INFO / 失败 WARN）、驱动完成（DONE INFO）、连续无进展（NEED_MATERIALS WARN）。
- 写入失败 / 被禁用时绝不抛异常，不影响主线程；客户端 tick 单线程，无并发竞争。

### 10.6 RecipeScreen 自动合成按钮（2026-07-25 新增）
- 监听 `net.minecraftforge.client.event.ScreenEvent.Init.Post`（游戏总线事件），仅在 `event.getScreen() instanceof dev.emi.emi.screen.RecipeScreen` 时处理。
- 反射读取 `RecipeScreen` 的 `private List<RecipeTab> tabs`、`private int tab`、`private int page` 字段；`RecipeTab.getPage(int)` 返回 `List<RecipeDisplay>`；`RecipeDisplay.recipe` 为 `public final`，从而拿到当前显示的配方。
- 按钮位置：反射读取 `RecipeScreen.currentPage`（`private List<WidgetGroup> currentPage`），取第一个 `WidgetGroup` 的 `x/y/width/height`（均为 `public final`），放在 `widgetGroup.x + widgetGroup.width + 2, widgetGroup.y + 4`，即第一个配方显示区右侧、与 EMI 自己的 heart/grid/+ 按钮同列。若反射失败则兜底到屏幕右上角。
- 按钮大小 18×18，显示文字 "A"，tooltip "自动合成当前配方"。
- 点击后：取 `RecipeScreen.old`（`public AbstractContainerScreen<?> old`）——打开配方屏前的容器界面；调用 `Minecraft.getInstance().setScreen(old)` 关闭配方屏；以当前 recipe 为 goal 调用 `TreeDriver.start(recipe)` 启动自动合成。
- 新增类：`com.adimn.emiautocraft.ui.RecipeScreenButtonHandler`，注册到 `MinecraftForge.EVENT_BUS`（`ScreenEvent` 属游戏总线）。
- 相关 API 已核实：`Bounds.x()`/`y()`/`width()`/`height()`/`left()`/`right()`/`top()`/`bottom()`；`Button.builder(...).pos(...).size(...).tooltip(...).build()`；`ScreenEvent.Init.Post.addListener(GuiEventListener)`；`Minecraft.setScreen(Screen)`。

