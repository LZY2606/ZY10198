# 火山形变解译台

本地 Kotlin/Ktor/SQLite 服务，用同一合成观测场并列解释点源（Mogi）与岩墙/受限平面源候选。系统不把候选压成一个综合“胜利分数”，而是分别返回数据加权 RMS、正则代价、按观测类型/区域指标、参数多起点分布、预测场、空间残差和血缘版本。

## 构建与演示

```bash
mvn -q -DskipTests package
mvn -q test
mvn -q exec:java -Dexec.mainClass=app.MainKt -Dexec.args='--port 5538'
```

访问：

```text
http://127.0.0.1:5538
```

页面标题为“火山形变解译台”。默认 SQLite 文件为 `data/volcano.db`；可用 `--db /path/to.db` 覆盖，测试和重放不需要外部服务。

## 固定 fixture

固定数据由 `app.Fixture` 确定性生成并在数据库为空时自动导入：

- 12 个 GNSS 站点，每站 E/N/U 三分量，共 36 行。
- 10 个 InSAR 点位，每行保存 E/N/U LOS 单位向量和 LOS 位移，共 10 行。
- 两个观测时段：`2025A` 与 `2025B`。
- 五个协方差分组：GNSS E、N、U 各一组；升轨 InSAR 和降轨 InSAR 各一组。
- 区域：`summit`、`north`、`south`、`east`、`west`。

真实生成源是一个深 7.8 km、长 5 km、宽 3 km、直立走向的受限平面源。固定观测噪声来自确定性短序列，不使用随机数，因此测试和页面重放完全一致。

## 坐标与符号口径

- 水平坐标：以火山 summit 为原点的本地 ENU，单位 km；东向为 `x`，北向为 `y`。
- 高程：`elevationKm` 的正方向为 `up`。
- 深度：`depthKm` 明确为相对本地地形基准“positive downward”，不使用负高程表达深度。
- CRS 记录名称、单位、高程口径、深度口径和 `identity-km-enu-1.0` 坐标转换版本。
- 几何版本固定为 `geometry-enu-1.0`；每条预测都保存几何版本和坐标转换版本。
- InSAR LOS 在导入时必须满足 `sqrt(losE^2 + losN^2 + losU^2)=1`，默认容差 `1e-6`，否则导入失败。预测不偷偷重归一化。

## 协方差与共同模式误差

协方差矩阵按组显式构造：

```text
C_ij = diagonalSigma_i^2 [i=j]
     + commonModeVariance [同组]
    + commonModeVariance * exp(-distance_km / correlationLengthKm) [i!=j、同组且配置了相关长度]
```

GNSS 同分量/同时段的共同模式误差表现为非对角常数项；InSAR 还使用指数空间相关项。反演目标为：

```text
r^T C^-1 r + lambda * priorNormalization
```

系统不会通过复制观测行来“制造独立样本”。响应中的 `effectiveObservations` 和协方差页给出每组原始行数、有效独立信号估计、CME sigma 与协方差模型。

## 模型

- `POINT`：点压力 Mogi 核，参数为 `xKm`、`yKm`、`depthKm`、`strength`。
- `DIKE`：受限矩形张开面，使用矩形网格积分的 Mogi Green function；参数额外包括 `lengthKm`、`widthKm`、`strikeDeg`、`dipDeg`。
- 多起点：默认 12 个确定性起点，含固定深部点源和平面源几何；随后做坐标下降、边界末轮抛光。
- 正则：用户提供非负 `regularization`（lambda），默认 0.02；响应分别报告数据失配、数据加权 RMS 和正则代价。

## 边界活跃语义

固定场在默认深度上界 8 km 下产生近等价的点源/岩墙解释：

- 点源最优点落在深度上界，响应 `boundaryActive=true`，并列出 `depthKm upper 8.0/8.0`。
- 页面明确显示“边界活跃，不是内部稳定解”。
- 岩墙候选在深度内部接近生成源，尺寸若触及上/下界也会独立列出。

## API

- `GET /api/dataset`：查看当前导入的完整数据口径。
- `POST /api/fit`：提交 `modelType`、`regularization`、`bounds`、`starts`。
- `GET /api/runs`：运行列表。
- `GET /api/runs/{id}`：运行响应。
- `GET /api/runs/{id}/predictions`：逐站点预测和残差。
- `GET /api/compare?a=<pointRun>&b=<dikeRun>`：按观测类型和区域差分两个候选。
- `GET /api/runs/{id}/export`：导出 dataset、run、predictions 的 JSON 运行记录。
- `POST /api/admin/reimport`：在一个事务中删除运行和数据集，再重新导入固定 fixture。

## 清空后复核

可直接删除 SQLite 文件后重启，系统会自动重新导入；也可在运行中点“清空并重新导入 fixture”或调用：

```bash
curl -X POST http://127.0.0.1:5538/api/admin/reimport
```

随后重新生成点源和岩墙两个候选，检查点源深度边界、LOS norm、协方差非对角项和导出记录即可完成复核。

## 自动化测试

- LOS 单位向量、高程/深度符号口径。
- 岩墙恢复、点源近等价且深度上界活跃。
- 同组 CME 非对角协方差与有效信号数小于原始行数。
- 页面标题、反演、候选差分、导出、清空重导入。
