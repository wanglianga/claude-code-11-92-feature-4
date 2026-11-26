# 港口靠泊协同服务（Berth Coordination）

船代提交船次（船长/吃水/货类/ETA 区间/作业时长/岸桥数），码头调度枚举并比较候选靠泊计划，
对 **泊位 + 引航员 + 拖轮 + 岸桥整组资源** 做暂留或原子确认；确认后的计划才下发引航、拖轮、岸桥班组。
系统在**整个靠泊操纵与装卸窗口**校验吃水下净空（UKC ≥ 1m）、货类限制、人员值班/交接、
设备工作区间/转场/周转，并提供潮汐修订、岸桥故障、改靠、ETA 迟到的影响分析与版本化重排；
已完成的引航/装卸记录在应用层与数据库触发器双层保证不可覆盖。

- 后端：Java（`--release 21`，已在预装 **JDK 25.0.1** 上构建运行）、Spring Boot 3.5.7、Spring JDBC、PostgreSQL
- 前端：`src/main/resources/static/` 原生 HTML/CSS/JS 单页（无需 Node 构建）
- 接口文档：springdoc-openapi，`/swagger-ui.html`
- 一键启动：`Dockerfile` + `docker-compose.yml`（PostgreSQL 18）

---

## 1. 题目场景与内置样例

固定样例时间为 **2026-11-26 晚**，重置接口/回放会反复恢复该数据：

- **B1 深水泊位**：可容船长 320m、图示水深 9.5m；深水船吃水 11.5m、UKC 要求 1m
  ⇒ 需要潮位 `h ≥ 11.5 + 1.0 − 9.5 = 3.0m`。
- 30 分钟一档的模拟潮汐中，**只有 S=20:00 这一个 15 分钟网格起点**能通过全窗口 UKC 校验
  （19:30 档潮位 2.8m 不够；21:30 档回落到 2.75m，20:15/20:30 起点也失败）。
- 该时段唯一有 DEEP 资质且值班的引航员 **PILOT-1 张远航**（值班 17:00–次日 02:00，交接 30m），
  唯一适配 300m/11.5m 船舶的拖轮 **TUG-1 港拖16**（周转 30m）；岸桥 QC-1~QC-4。
- **VOY-A 远集 A**：ETA `19:30–20:30`，作业 60m，需 2 台岸桥。
- **VOY-B 远集 B**：ETA `19:00–20:00`，已提交 20:00 靠泊，与 A 竞争同一组合。
- 另有 VOY-C/D/E 覆盖集装箱、滚装与“ETA 18–19 改 19–20”的开场故事。
- 次日 08:00 前后还有早潮窗口与 PILOT-3/TUG-3 早班，用于“等潮/等资源”的备选与重排。

### 1.1 feature-4：馈线临时降容到 1.2MW 的岸电分时分配（本题）

供电调度台（前端 Tab **⑥ 供电调度（馈线降容）**，API 前缀 `/api/power`）内置固定数值样例：

- 馈线 **FD-01** 正常容量 2.0MW，晚高峰临时降容到 **1.2MW** 安全上限。
- 三艘在泊船需量恰为 **0.6 / 0.5 / 0.4MW**，确认时间刻意为：普通船 PA-01 18:05、普通船 PA-02 18:25、冷藏船 PA-03 18:40（最晚）。
- **冷藏船“远洋冷王”** 载 40 个冷藏箱，必须连续保有 **≥0.4MW**，否则冷机停机、箱温回升，需把冷藏箱转移到岸上插电位，产生翻箱/拖车/插电位与货损风险。

录入告警后同时给出并对照**两种现实中都有用的顺序**（20:00–22:00、30 分钟一档共 4 档的分时表）：

| 顺序 | PA-01 | PA-02 | PA-03 冷藏 | 馈线逐档合计 | 货物安全 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| **按确认时间**（先保护既有供电承诺） | 0.60 | 0.50 | **0.10**（剩余） | 1.20MW ✅ | ❌ 0.10<0.40 | 总量不超限，但冷藏安全下限失守 → CRITICAL，必须转移冷藏箱 |
| **按货物最低安全负荷** | 0.60/0.30 错峰 | 0.20/0.50 错峰 | **0.40 连续** | 恒 1.20MW ✅ | ✅ | 保住冷藏；改动两艘普通船已确认作业并出具补偿依据 |

- 安全方案用“基础负荷 + 可转移起货机负荷错峰轮转”：偶数档 `(PA-01=0.6, PA-02=0.2)`、奇数档 `(0.3, 0.5)`，冷藏恒 0.4；每艘普通船的 300kW 克令吊/船吊各被削减两个 30 分钟档，合计削减电量 **0.600MWh**，按样例 **200 元/MWh** 补偿 **¥120**，每条削减都归因到具体 `power_commitment` 已确认作业并写明计算公式（补偿依据）。
- 每个方案都带**逐时段馈线总量核验**（∑各船实配 ≤ 上限）。并内置“**逐船独立降额陷阱**”反例：每船独立压到 ≤0.5MW 得 0.5+0.5+0.4=**1.4MW 仍 >1.2MW**，证明逐船降额不能代替馈线总量核验。
- 给出两种顺序各自的**恢复次序**（安全顺序冷藏第一步；确认顺序冷藏最后、累计 1.5MW 超降容上限，须等告警撤销）。
- 方案可下达**不可变回执** `power_receipt`（DB 触发器 `trg_power_receipt_immutable` 禁止 UPDATE/DELETE）。**告警撤销**只把告警置为 REVOKED、馈线恢复 2.0MW，并生成新的 `FULL_RESTORE` 方案（三船恢复 0.6/0.5/0.4、合计 1.5MW）与新回执；**旧方案与旧回执逐字保留、绝不修改**，重复撤销返回 409。

---

## 2. 核心设计决定：何时占用整组靠泊资源

两种策略都已实现，并可用接口参数或回放对照：

| 策略 | 行为 | 优点 | 代价 |
| --- | --- | --- | --- |
| **`TENTATIVE_HOLD`（默认）** | 调度“暂存”候选时，即在同一事务写入泊位/引航/拖轮/岸桥的 `TENTATIVE` 占用，TTL 默认 120s；到期由 500ms 扫描器自动释放 | 确认后资源不会被抢 | ETA 尚宽时资源可能空等 → 用短 TTL + 一键取消控制 |
| **`ATOMIC_CONFIRM`** | 暂存只保存方案、**不占资源**；确认时在一个事务内重校全部约束并插入 `FIRM` 占用 | 资源周转率高 | 已讨论方案可能在最后一步被别人锁走，确认必须原子重校+锁定 |

暂存接口可用 `"holdResources": false/true` 临时选择策略；`app.planning.hold-on-stage` 改全局默认。

**唯一性保证（不依赖应用锁）**：所有占用统一落在 `resource_occupation` 表。
PostgreSQL 触发器 `trg_occupation_overlap` 在写入前对 `resource_lock` 中该资源的锁父行 `SELECT … FOR UPDATE`，
再检查半开区间重叠；并发确认同一组合时恰好一个事务成功，另一个收到 `23P01/RESOURCE_OVERLAP` → HTTP 409。
另有部分唯一索引保证同一船次至多一个 `CONFIRMED` 方案。

---

## 3. 约束与时间轴模型

设 `S = berth_from`（靠泊操纵开始）：

```
引航段    [S-45, S+15]            占用区间前后各加 handover_min 交接缓冲
拖轮靠泊  [S-15, S+30]            占用尾部加 turnaround_min 周转
靠泊操纵  [S, S+30]               泊位占用开始
装卸作业  [S+30, S+30+duration]   每台岸桥占用至 opsEnd + transfer_min 转场
拖轮离泊  [opsEnd, opsEnd+30]     同样加周转缓冲
离泊段    [opsEnd, opsEnd+30]     泊位占用结束 = opsEnd+30
```

- **UKC**：对 `[S, opsEnd]` 与每个重叠潮汐档校验 `chart_depth + tide_height − draft ≥ ukc_min(=1.0)`，并检查潮汐覆盖完整性。
- 泊位：船长 ≤ `max_length_m`，货类必须在 `berth_cargo_limit` 名单内。
- 引航员：船长/吃水/资质（深水大船需 `DEEP`）+ 占用区间（含交接）完整落在某个 `work_window`。
- 拖轮：船长/吃水适配 + 靠/离两区间（含周转）均不冲突、在班。
- 岸桥：需要 N 台不同岸桥，每台工作窗覆盖 `[opsStart, opsEnd+transfer]`，且无占用/故障重叠。
- 候选生成：15 分钟网格，从 `etaStart` 枚举到 `etaEnd + 960m`；ETA 窗口内不可行点全部保留并给出
  结构化原因（`UKC_VIOLATION`、`*_HELD_TENTATIVE`、`*_BOOKED_FIRM`、`PILOT_OFF_DUTY`、
  `INSUFFICIENT_CRANES` 等，含占用方船次/方案/暂留到期时间），可行点按时间先后取前 6。

---

## 4. 目录结构

```
pom.xml / Dockerfile / docker-compose.yml
scripts/replay.sh                 # 命令行一键验证（登录 + /api/replays/full + 断言）
src/main/resources/
  application.yml                 # DB 环境变量、TTL、候选网格配置
  schema.sql                      # 表/索引（幂等）；触发器由 Java 下发
  static/                         # 单页前端 index.html / app.js / styles.css
src/main/java/com/port/berth/
  PortBerthApplication.java
  config/                         # 鉴权拦截器、CORS、OpenAPI、PG 触发器初始化
  domain/                         # 枚举、Catalog 记录、Eval、ConstraintMath
                                  # PowerAllocationMath（kW 整数分配内核/总量核验/独立降额反例）
  repo/                           # CatalogDao / PlanDao（JdbcTemplate + 行映射）/ PowerDao
  service/                        # PlanningEngine / PlanService / HoldSweeper
                                  # ExecutionService / DisruptionService / ReplanService
                                  # ReplayService / SeedService / AuthService
                                  # PowerService（告警/双顺序方案/补偿/撤销新方案/回执）
                                  # PowerReplayService（供电 23 项断言）
  web/                            # REST 控制器、全局异常处理、PowerController
src/test/java/.../                # 4 个纯单元测试（约束数学 + 样例唯一窗口规格）
```

---

## 5. 一键启动

### 5.1 Docker Compose（推荐，干净环境）

```bash
cd /workspace/92-feature-4
docker compose up --build
```

- 应用：<http://localhost:8080>（单页操作界面）
- Swagger UI：<http://localhost:8080/swagger-ui.html>，OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- PostgreSQL 18：容器内 `db:5432`，宿主机映射 **5433**（避免与本机 5432 冲突），
  库/用户/密码 `berth/berth/berth123`，数据卷 `pgdata`
- 镜像构建内已配置 `docker/settings-docker.xml` 走阿里云 Maven 源

> 本任务容器内没有安装 Docker CLI/守护进程，因此镜像构建与 `docker compose up` 未在本会话实际执行；
> `docker-compose.yml` 已通过 YAML 解析校验，Dockerfile 为标准多阶段构建。应用本身已用等价方式
> （JDK 25 + 可执行 jar + 真实 PostgreSQL 18）完整验证，见第 7 节。

### 5.2 本地开发（使用任务容器预装运行时）

PostgreSQL 18 已在 `127.0.0.1:5432` 运行，开发库/账号 `ccdev/ccdev`：

```bash
# 连通性检查
/opt/postgresql-18/bin/psql "postgresql://ccdev:ccdev@127.0.0.1:5432/ccdev" -c 'select 1'

# 若数据库进程未运行，可尝试：
# /opt/postgresql-18/bin/pg_ctl -D /tmp/cc-services/postgres -l /tmp/pg.log start

cd /workspace/92-feature-4
export JAVA_HOME=/opt/jdk-25
mvn spring-boot:run
# 或：mvn package && /opt/jdk-25/bin/java -jar target/berth-coordination.jar
```

零额外配置即可启动：默认连接 `jdbc:postgresql://127.0.0.1:5432/ccdev`（`ccdev/ccdev`），
首次启动自动执行 `schema.sql`、创建两个 PL/pgSQL 触发器并播种样例数据（幂等）。
也可用环境变量覆盖：`DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD/PORT/HOLD_ON_STAGE/HOLD_TTL_SECONDS`。

---

## 6. 测试账号

| 用户名 | 密码 | 角色 | 能做什么 |
| --- | --- | --- | --- |
| `agent` | `agent123` | AGENT 船代 | 提交船次、补报 ETA、撤回授权 |
| `dispatch` | `dispatch123` | DISPATCHER 调度 | 生成候选、暂存/确认/取消、扰动录入、改靠/重排、**供电告警录入/双顺序分配/撤销**、回放、重置 |
| `pilot` | `pilot123` | TEAM 引航班组 | 看板、开工/完成/登记作业（TEAM 还可下达/签收供电调度回执） |
| `tug` | `tug123` | TEAM 拖轮班组 | 同上 |
| `crane` | `crane123` | TEAM 岸桥班组 | 同上（含部分装卸量登记） |
| `admin` | `admin123` | ADMIN | 全部权限 |

登录：

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"dispatch","password":"dispatch123"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
```

除 `POST /api/auth/login` 与 `GET /api/meta` 外，`/api/**` 都需要
`Authorization: Bearer <token>`；角色不足返回 403。Swagger 中点 **Authorize** 填 token。

---

## 7. 本题验证方式与实际验证结论

### 7.1 自动化验证（本会话实际执行）

```bash
mvn test
# Tests run: 10, Failures: 0, Errors: 0, Skipped: 0  → BUILD SUCCESS
# 其中 PowerAllocationMathTest 6 个用例锁定本题数值与“总量核验不可被逐船降额替代”

mvn package
# 生成 target/berth-coordination.jar（约 28 MB，Spring Boot fat jar）
```

```bash
# 启动 jar 后执行（默认 http://127.0.0.1:8080）
bash scripts/replay.sh
# ==> 断言：69/69 通过；allPassed=True
# ALL_REPLAY_CHECKS_PASSED

# 只验证本题供电场景（23 条断言，更快）
bash scripts/power-check.sh
# ==> 供电断言：23/23 通过；allPassed=True
# ALL_POWER_CHECKS_PASSED
```

该脚本真实登录、调用 `POST /api/replays/full`（约 12 秒，含真实 2s TTL 等待、
500ms 扫描释放与双线程并发确认竞速；full 现额外包含 feature-4 供电块），
递归统计全部断言并以退出码反映结果。`power-check.sh` 只调 `POST /api/replays/power`。

此外实际执行并通过的验证（JDK 25 + 真实 PostgreSQL 18 + 上述 fat jar）：

- **供电 API 真实数值核对**：登录后 `POST /api/power/alarms {"deratedCapacityMw":1.2,"slotMinutes":30}`，
  返回 CONFIRMATION_ORDER 分配 `[600,500,100]`、CARGO_SAFETY 错峰表
  PA-01 `[600,300,600,300]` / PA-02 `[200,500,200,500]` / PA-03 恒 `400`，
  每档馈线合计均为 1200kW；补偿合计 0.600MWh / ¥120；撤销后出现 FULL_RESTORE 且旧回执快照不变。
- **供电 UI 渲染冒烟**：用 jsdom 加载真实 `index.html/app.js` 并灌入上述真实响应夹具，
  20 项断言全部通过（双方案卡、0.10MW、安全失守红格、冷藏箱转移、1.40MW 反例、
  恢复次序、补偿表、回执号、撤销后第三张恢复卡、基线三船），且无未捕获前端错误；
  `node --check src/main/resources/static/app.js` 亦通过。
- **权限/文档冒烟**：匿名 `GET /api/power/overview` 返回 401；AGENT `POST /api/power/alarms` 返回 403；
  `/v3/api-docs` 含 8 个 `/api/power/**` 路径与 1 个 `/api/replays/power` 路径；`/`、`/app.js`、`/styles.css`、
  `/swagger-ui/index.html` 均实际返回 200。
- **全新空库验证（本会话实际执行）**：在任务 PostgreSQL 18 上另建空库 `pw_fresh_92`，
  以 `DB_NAME=pw_fresh_92 PORT=8081 java -jar target/berth-coordination.jar` 启动最终制品，
  schema/三个触发器/播种（含供电样例）一次成功，`bash scripts/replay.sh http://127.0.0.1:8081`
  仍为 **69/69**（验证了 Compose 首次启动的初始化路径；验证后已删除该临时库并停止实例）。
- **HTTP 权限/业务冒烟 8/8（基线）**：匿名 401、公开 meta、船代提交船次、船代暂存 403、
  VOY-E ETA 补报状态变 `ETA_UPDATED`、TEAM 看板 200、错误密码 400、OpenAPI 200。
- 前端所有按钮处理函数均有定义。
- 回放中包含**数据库层自证不可变**：对 `work_record` 与 **`power_receipt`** 直接
  `UPDATE/DELETE` 均被触发器拒绝（供电块还会验证强删失败后回执仍在库、重复撤销被 409 拒绝）。

### 7.2 界面手工验证路径

1. 打开 <http://localhost:8080>，用 `dispatch/dispatch123` 登录（登录页可点账号芯片填充）。
2. **② 调度工作台** → 选 `VOY-A` → “生成/刷新候选计划”：可见唯一绿色可行卡
   `B1 20:00`，分配 `PILOT-1/TUG-1/QC-1/QC-2`；其余红色卡给出 UKC/值班等原因。
   - 设 TTL=2 秒点“暂存并暂留资源”，卡片变 `STAGED` 并显示到期时间；不确认，数秒后刷新可见 `EXPIRED`。
   - 对 `VOY-B` 生成候选，可见 20:00 卡被 A 的暂留/确认阻挡及结构化等待原因。
   - 可试“仅暂存不占资源”，再让 B 先确认，最后 A “直接原子确认”会收到 409。
3. **③ 班组看板**：只有 `CONFIRMED` 方案出现；可开工、登记部分作业量、完成分段。
4. **④ 模拟与扰动**：修订 21:00 潮位为 `2.20`、插入 QC-2 故障窗口、查看影响项；
   回到调度页对确认方案“扰动后重排”，或用确认卡上的“改靠/改时”。
5. **⑤ 一键回放验证**：按钮分别运行顺序集/扰动集/**⚡供电降容集**/完整集，页面渲染每条断言与完整 JSON。
6. **① 船代申报**：可新建船次、对 VOY-E 把 ETA 18–19 补报为 19–20、撤回授权。
7. **⑥ 供电调度（本题主界面）**：
   - 进入即可见馈线 FD-01 状态、三船需量/安全下限/已确认用电作业，以及“逐船独立降额反而 1.4MW 超载”的提示。
   - 点“**⚡ 输入馈线告警并出方案**”：并排出现两张方案卡。左卡“按确认时间”里冷藏船每格仅 `0.10` 且红框标安全失守、货物影响为 CRITICAL/必须转移冷藏箱；右卡“按货物安全”里冷藏恒 `0.40`，普通船按半小时档 `0.60/0.20`、`0.30/0.50` 错峰，底部合计恒 `1.20/1.20`。
   - 每张卡含受影响货物、被削减的已确认作业与补偿金额/公式、恢复次序；安全方案默认已签发回执，可对任一方案“下达并生成回执”。
   - 底部回执台账可“查看快照”。点右上“**↩ 告警撤销·生成恢复新方案**”：出现第三张蓝色 FULL_RESTORE 卡（三船全额、合计 1.5MW），顶部提示旧方案/回执保留；底部旧回执仍在，快照内容不变。

---

## 8. 一键回放到底核对了什么

`POST /api/replays/orderings`、`/api/replays/disruptions`、`/api/replays/power`、`/api/replays/full`
（DISPATCHER/ADMIN；full = 顺序集 + 扰动集 + 供电块）每次先重置固定样例，再输出带断言与完整时间线的报告：

| 回放块 | 核对点（全部 PASS） |
| --- | --- |
| **顺序1：A 暂存后未确认** | A 的 B1 20:00 候选可行且选到 P1/T1/QC1/QC2；A `STAGED`；B 同时点不可行且原因含 `*_HELD_TENTATIVE`；B 强确认 409；TTL 到期 A `EXPIRED`、≥5 条占用全 `RELEASED`、扫描延迟 <3s；A 确认数 0；释放后 B 可行并成为唯一 `CONFIRMED` |
| **顺序2：A 暂留期内确认** | A `CONFIRMED` 且唯一；B 被 `*_BOOKED_FIRM` 阻挡；B 暂存/确认均 409；B 得到等待 >120m 的次日备选；B 确认数 0 |
| **顺序3：B 先确认** | B 直接原子确认成功；A 候选显示 FIRM 阻挡；A 暂存、最后一步确认均 409；确认计数 B=1/A=0 |
| **并发竞速** | 两线程同时确认 A/B 同一组合：恰好 1 个成功，库中 A/B 合计仅 1 个 `CONFIRMED`（触发器+资源锁行兜底） |
| **策略对照** | `holdResources=false` 时暂存零占用；B 先确认；A 事后确认 409（“最后一步失效”），并附两种策略权衡说明 |
| **潮汐修订** | 先写 1 条引航完成记录 + 1 条 15 分钟装卸台账；21:00 潮位 3.2→2.2 后分析出指向装卸段的 `UKC_VIOLATION`；重排模式 `RESCHEDULE`、次日 08:00 新方案 `CONFIRMED`、只排剩余 45m；2 条记录保留；DB 阻止 UPDATE/DELETE |
| **岸桥故障** | QC-2 20:45–22:30 故障生成 `CRANE_FAULT`；重排为 `SAME_SLOT_REASSIGN`，新分配含 QC-3 不含 QC-2；引航记录保留 |
| **改靠指令** | 对 VOY-D 下达改靠 B2↔B3：新版本确认且泊位变化；≥4 条 `BERTH_CHANGE` 逐段影响说明 |
| **ETA 迟到** | ETA 最早时间晚于计划靠泊点时生成 ≥4 条 `ETA_LATE`（引航/拖轮/靠泊/装卸），随后重排出 `CONFIRMED v2` |
| **⚡ 馈线降容供电（23 项）** | 三船需量 600/500/400；确认顺序给冷藏仅 100kW 且安全不可行/CRITICAL 转移；安全顺序冷藏恒 400、普通船 600/200↔300/500 错峰、每档总量 1200；削减归因 PC-0102/PC-0202、0.6MWh/¥120；独立降额反例 1400>1200；两种恢复次序；自动回执；撤销生成 FULL_RESTORE(1500) 且旧回执快照逐字不变；DB 触发器阻止改/删回执、强删后仍在；重复撤销 409 |

---

## 9. 主要 HTTP API

| 方法/路径 | 角色 | 说明 |
| --- | --- | --- |
| `POST /api/auth/login` | 公开 | 登录拿 token |
| `GET /api/meta` | 公开 | 船舶/泊位/潮汐/人员/设备/故障/演示账号 |
| `GET/POST /api/voyages` | 登录 / AGENT+ | 船次列表/提交 |
| `GET /api/voyages/{id}` | 登录 | 船次 + 全部方案详情 + 记录 + 事件 |
| `PATCH /api/voyages/{id}/eta` | AGENT+ | 补报 ETA；若有确认方案同时做 ETA 迟到影响分析 |
| `POST /api/voyages/{id}/withdraw` | AGENT+ | 撤回授权并释放该船全部资源 |
| `POST /api/voyages/{id}/candidates/generate` | DISPATCHER+ | 枚举候选并落库 |
| `GET /api/plans`、`GET /api/plans/{id}` | 登录 | 方案/分段/原因/分配 |
| `POST /api/plans/{id}/stage` | DISPATCHER+ | body：`{"holdResources":true,"holdTtlSeconds":120}` |
| `POST /api/plans/{id}/confirm` | DISPATCHER+ | 事务内重校 + 原子锁定 FIRM |
| `POST /api/plans/{id}/cancel` | DISPATCHER+ | 取消暂存并释放 |
| `POST /api/segments/{id}/start|complete|log` | TEAM/DISPATCHER | 开工/完成/部分作业台账 |
| `GET /api/teams/board` | 登录 | 仅 CONFIRMED 方案的引航/拖轮/岸桥/泊位看板 |
| `PUT /api/simulation/tides/{slotId}` | DISPATCHER+ | 修订潮汐并分析 |
| `POST /api/simulation/crane-faults` | DISPATCHER+ | 插入设备故障窗口并分析 |
| `POST /api/plans/{id}/replan` | DISPATCHER+ | 扰动重排（默认自动确认） |
| `POST /api/plans/{id}/change-berth` | DISPATCHER+ | 改靠/改时指令 |
| `GET /api/disruptions[/{id}]` | 登录 | 扰动与影响项 |
| `GET /api/power/overview` | 登录 | **供电总览**：馈线、三船与已确认作业、当前告警双方案、回执台账、独立降额反例 |
| `POST /api/power/alarms` | DISPATCHER+ | **录入馈线降容告警**并生成 CONFIRMATION_ORDER + CARGO_SAFETY 双方案（默认 1.2MW / 30 分钟档），自动下达安全方案回执 |
| `GET /api/power/alarms`、`/alarms/{id}` | 登录 | 告警列表 / 详情（分配表、逐时段总量核验、货物影响、削减补偿、恢复次序） |
| `POST /api/power/alarms/{id}/revoke` | DISPATCHER+ | **撤销告警**：生成 FULL_RESTORE 新方案与新回执，旧方案/回执不变 |
| `GET /api/power/plans/{id}`、`POST /api/power/plans/{id}/issue` | 登录 / DISPATCHER+、TEAM | 方案明细 / 下达不可变供电回执 |
| `GET /api/power/receipts[/{id}]` | 登录 | 不可变回执台账 / 含签发时方案快照 |
| `POST /api/replays/power` | DISPATCHER+ | **本题供电场景 23 条断言自检** |
| `POST /api/replays/orderings|disruptions|full` | DISPATCHER+ | 一键回放（full 已含 power 块） |
| `POST /api/admin/reset` | DISPATCHER+ | 清空并恢复固定样例 |

---

## 10. 配置与运维说明

- `app.planning.hold-on-stage`、`app.planning.default-hold-ttl-seconds`、
  `app.planning.candidate-step-minutes`、`app.planning.max-wait-minutes` 见 `application.yml`，
  均可用同名环境变量覆盖（`HOLD_ON_STAGE`、`HOLD_TTL_SECONDS` …）。
- `HoldSweeper` 每 500ms 扫描 `STAGED` 且到期的方案，释放其 `TENTATIVE` 占用并写 `HOLD_EXPIRED` 事件。
- `ccdev/ccdev` 是任务容器的隔离开发账号，**不要直接作为生产配置**；Compose 使用独立的 `berth` 库与密码。
- 服务不依赖 Redis/港口实机/第三方账号；外部潮汐与设备状态全部来自库内模拟表和模拟录入接口。
