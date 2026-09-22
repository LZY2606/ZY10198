# 火山形变解译台（Volcano Deformation Studio）

本地服务（Kotlin + Ktor + SQLite + 单页 Web UI），用同一套 GNSS 三分量位移与
InSAR 视线（LOS）位移，并列考察 **Mogi 点源**、**Okada 张性岩墙**、
**受限平面源** 三类地下压力源候选，显式处理共同模式误差、观测几何与缺测导致的
多解性，并对每个解独立报告拟合指标——不用一个跨模型综合分数把候选压扁。

## 安装与演示

```bash
# 安装构建（跳过测试）
mvn -q -DskipTests package

# 演示：先跑自动化测试，再启动服务
mvn -q test
mvn -q exec:java -Dexec.mainClass=app.MainKt -Dexec.args='--port 5538'
```

浏览器访问 <http://127.0.0.1:5538>，页面标题为 **“火山形变解译台”**。
首次启动会自动把固定演示 fixture 导入到 `data/volcano-studio.sqlite`。

可选参数：`--port`、`--db <sqlite 路径>`、`--no-fixture`、`--web-root <目录>`。

## 页面工作流

1. **数据导入**：一键导入固定 fixture，或粘贴 CSV；可随时“清空数据库”。
2. 选择**模型类型**（点源 / 岩墙 / 受限平面源）、**物理边界**（深度、倾角、尺寸上下界）
   与**正则强度** λ，运行多起点反演。
3. 查看全部起点的**参数分布**、最优解的预测场与**空间残差**；边界活跃会明确标红。
4. 在“双候选差分”中填两个 `solutionId`，按**观测类型 / 区域 / 时段**比较残差与预测差。
5. 点任一观测点，弹窗给出该预测值的正演引擎版本、坐标变换版本、深度口径、LOS 与参数。
6. 导出：每个 run 一个 JSON（边界、种子、全部起点、参数、指标），每个解一个预测 CSV。

## 数据口径（Conventions）

所有口径都随数据/预测/导出记录持久化，引擎入口见
`src/main/kotlin/app/Geometry.kt` 的 `Conventions`：

- **坐标系**：局部切平面 ENU，x=East、y=North，单位 km；保留源 CRS 字符串（fixture
  为 `EPSG:32654 -> ENU local`）与变换版本 `ENU-LOCAL-v1`。
- **高程与深度符号不混用**：位移分量 u_z（高程方向）**向上为正**；所有源参数
  `depth` **地表向下为正**（地下深度恒正）。岩墙的 Okada `depth` 是矩形**上沿**
  深度，服务在内部由“中心深度 + 宽度 + 倾角”换算，并拒绝/惩罚上沿穿出地表的参数。
- **LOS 单位向量**：`(losE, losN, losU)` 沿“卫星→目标”方向，标量预测 = l·u。
  导入时强制 |l|=1，容差 `1e-6`，不通过直接拒绝（`LosNormalizationException`）；
  GNSS 三分量使用 (1,0,0)/(0,1,0)/(0,0,1) 基向量。
- **弹性常数**：泊松比 ν=0.25。Mogi 用 `u=(1−ν)/π · dV·R/R³`；岩墙为 Okada
  (1985/1992, BSSA) 矩形张性位错自由表面解析解，Kotlin 实现已与官方 DC3D 程序在
  直立岩墙、水平岩床、60°/70° 倾斜及 120° 走向上逐点核对到机器精度（见
  `DikeTest`，参考值固化在测试里）。
- **观测时段**：每条观测带 `epoch`；fixture 含 GNSS 2024-05/2024-07 两个时段与
  InSAR 2024-05~07 两轨。

## 共同模式误差（协方差，显式相关）

协方差结构 `C = D + S Sᵀ`（`Covariance.kt`）：白噪声对角阵 `D` 加上按
**协方差分组**共享的共同模式误差（CME / 轨道-大气残差），组内任意两观测协方差为
σ_cme²。典型分组：GNSS 按观测时段/台网，InSAR 按轨道/帧。

- 反演目标函数 `χ² = rᵀ C⁻¹ r + 正则`，用 Woodbury 引理在 g×g 小组矩阵上求解，
  **不把同一组观测重复抽样当作独立信息**；fixture 的噪声也是“白噪声 + 每组一个共享
  高斯抽样”真实相关结构（固定种子可重放）。
- 测试 `CovarianceTest` 用手算稠密协方差核对了 Woodbury 二次型，并验证 CME 只
  吸收组内共同模式、不影响零和（正交）残差模式。

## 正则与边界活跃

- 正则为归一化参数空间的 Tikhonov 向心项 `λ Σ (z−z₀)²`，λ 由滑杆给出（0=纯加权最小二乘）。
- 优化器是有界 Nelder–Mead 的多起点搜索（固定种子的分层覆盖 + 确定性扰动）。
- 若最优点落在任一参数边界容差内（归一化距离 ≤1e-3），解被标记
  **`activeBounds=true`**，并列出具体参数（深度/尺寸上下界）；深度贴上界时
  `depthAtUpper=true`。这样的解在 UI 与导出中显示“**边界活跃（非内部稳定解）**”，
  绝不表述为内部稳定解。
- 每个模型独立报告 χ²、约化 χ²、BIC；UI 不提供跨模型的“一个综合分数”排序。

## 固定 fixture 与验收场景

`src/main/kotlin/app/Fixture.kt`（种子 `20240517`，120 条观测：54 GNSS + 66 InSAR，
4 个协方差分组，`near/far` 两区域，西南象限缺测）：

- 真值是 6.5 km 深、dV=2.5×10⁷ m³ 的 Mogi 点源；
- **点源最优**深度≈6.43 km，约化 χ²≈1.18，是**内部解**（不碰边界）；
- **受限平面源**在用户给深度上界 6 km 时，最优深度=6.0 km、χ²≈135，与点源 χ²≈137
  近等价，并明确报告**边界活跃（深度上界）**——对应“观测几何/共同模式误差/缺测
  允许更深的等效源”的非唯一性。

以上由 `InversionFixtureTest` 自动断言。

## CSV 格式与清空后复核

固定 CSV 已随仓库提供：`fixtures/volcano-a.csv`（可用
`mvn exec:java -Dexec.mainClass=app.FixtureDumpKt > fixtures/volcano-a.csv` 重新生成）。
格式头示例：

```
# dataset: ...
# crs: EPSG:32654 -> ENU local
# transform: ENU-LOCAL-v1
# groups
# group,label,type,epoch,sigma_cme_m
G-E1,GNSS 第一时段,gnss,2024-05,0.006
# observations
# id,site,type,x_km,y_km,value_m,sigma_white_m,group,epoch,los_e,los_n,los_u,region
T-A84-P001,I0,insar_los,...,T-A84,2024-05~07,-0.46,..,0.88,near
```

清空数据库后复核有两种等价方式：

1. 页面“清空数据库”→“导入固定 fixture”，或
2. 页面粘贴 `fixtures/volcano-a.csv`（也可用
   `POST /api/datasets/import-csv`；内容 hash 与内置 fixture 一致）。

同种子重跑同一模型得到**逐位一致**的最优参数与 χ²（`PersistenceTest` 断言）。
运行记录可通过 `GET /api/runs/{id}/export`（JSON）与
`GET /api/solutions/{id}/export`（CSV）导出。

## 主要 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/conventions` | 引擎/坐标/深度/LOS 口径与版本 |
| POST | `/api/datasets/import-fixture` | 导入固定 fixture |
| POST | `/api/datasets/import-csv` | 导入 CSV 文本 |
| GET | `/api/datasets/{id}/observations` | 观测与协方差分组 |
| POST | `/api/runs` | 多起点反演（模型、边界、λ、种子、起点数） |
| GET | `/api/runs/{id}` | run、全部解、全部起点 |
| GET | `/api/solutions/{id}/predictions` | 含溯源元数据的逐观测预测/残差 |
| POST | `/api/compare` | 两候选按 TYPE/REGION/EPOCH 差分 |
| GET | `/api/runs/{id}/export`、`/api/solutions/{id}/export` | 导出 JSON / CSV |
| POST | `/api/admin/wipe` | 清空所有数据与运行记录 |

## 目录

- `src/main/kotlin/app`：几何约定、三类正演模型、Okada 移植、协方差、多起点优化、
  fixture、CSV、SQLite 仓库、Ktor 服务与导出。
- `src/main/resources/web`：单页操作界面（原生 HTML/JS/SVG，无构建步骤）。
- `src/test/kotlin/app`：22 个 JUnit5 测试（物理参考值、LOS/深度口径、协方差、
  近等价与边界活跃、CSV/清空重放、HTTP API）。
- `fixtures/volcano-a.csv`：固定演示观测场。
