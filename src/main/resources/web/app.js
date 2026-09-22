"use strict";
let state = { dataset: null, observations: [], groups: [], lastSolutions: [], selectedSolution: null };

const $ = (id) => document.getElementById(id);
function toast(msg, bad) {
  const t = $("toast"); t.textContent = msg;
  t.style.borderColor = bad ? "var(--bad)" : "var(--ac2)";
  t.style.display = "block"; clearTimeout(t._h); t._h = setTimeout(() => t.style.display = "none", 4200);
}
async function api(path, opts) {
  const r = await fetch(path, opts);
  const txt = await r.text();
  let body; try { body = JSON.parse(txt); } catch (e) { body = txt; }
  if (!r.ok) { const m = body && body.error ? body.error + ": " + (body.detail || "") : txt; throw new Error(m); }
  return body;
}
async function loadConventions() {
  const c = await api("/api/conventions");
  $("convTags").innerHTML =
    `engine ${c.engineVersion.split(":")[0]} · ${c.coordVersion} · ν=${c.poissonRatio} · LOS 容差 ${c.losTolerance}`;
}
async function refreshDatasets(selectId) {
  const list = await api("/api/datasets");
  $("dsList").innerHTML = list.length ? list.map(d =>
    `<div class="clickable" style="padding:7px;border:1px solid var(--line);border-radius:6px;margin-bottom:6px" onclick="selectDataset(${d.id})">
      <b>${d.name}</b> <span class="muted">#${d.id}</span><br>
      <span class="small muted">${d.crs} · ${d.nObs} 观测（GNSS ${d.nGnss}/InSAR ${d.nInsar}）· ${d.nGroups} 个协方差分组 · LOS 偏差 ${d.losMaxDeviation.toExponString()}</span><br>
      ${d.epochs.map(e => `<span class="tag">${e}</span>`).join("")}
      ${d.regions.map(e => `<span class="tag">区域:${e}</span>`).join("")}
    </div>`).join("") : '<span class="muted">暂无数据集</span>';
  if (selectId) await selectDataset(selectId);
  else if (list.length && !state.dataset) await selectDataset(list[0].id);
}
async function selectDataset(id) {
  const d = (await api("/api/datasets")).find(x => x.id === id);
  const detail = await api(`/api/datasets/${id}/observations`);
  state.dataset = d; state.observations = detail.observations; state.groups = detail.groups;
  drawObservationMap();
  $("mapBar").innerHTML =
    `数据集 <b>${d.name}</b> (#${d.id}) ｜ CRS: ${d.crs} ｜ 坐标变换: ${d.transformVersion} ｜ hash ${d.sourceHash.slice(0, 12)}… ｜ ${d.note}`;
  toast("已选择数据集 #" + id);
}
async function loadFixture() {
  try { const r = await api("/api/datasets/import-fixture", { method: "POST" });
    await refreshDatasets(r.datasetId); toast("fixture 已导入，数据集 #" + r.datasetId);
  } catch (e) { toast(e.message, true); }
}
async function wipe() {
  if (!confirm("清空所有数据集与运行记录？")) return;
  await api("/api/admin/wipe", { method: "POST" });
  state = { dataset: null, observations: [], groups: [], lastSolutions: [], selectedSolution: null };
  await refreshDatasets(); $("solutions").innerHTML = ""; $("resid").innerHTML = ""; $("map").innerHTML = "";
  toast("数据库已清空，可重新导入复核");
}
async function importCsv() {
  try {
    const r = await api("/api/datasets/import-csv", { method: "POST", body: $("csv").value });
    await refreshDatasets(r.datasetId); toast("CSV 已导入 #" + r.datasetId);
  } catch (e) { toast(e.message, true); }
}

// ---- 地图 ----
function scale(points, w, h, pad) {
  const xs = points.map(p => p.x), ys = points.map(p => p.y);
  const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys);
  const k = Math.min((w - 2 * pad) / (maxX - minX || 1), (h - 2 * pad) / (maxY - minY || 1));
  return {
    sx: (x) => pad + (x - minX) * k + ((w - 2 * pad) - (maxX - minX) * k) / 2,
    sy: (y) => h - pad - (y - minY) * k - ((h - 2 * pad) - (maxY - minY) * k) / 2,
  };
}
function drawObservationMap() {
  const obs = state.observations;
  if (!obs.length) { $("map").innerHTML = ""; return; }
  const w = 880, h = 400, pad = 26;
  const pts = [...new Map(obs.map(o => [o.siteId, { x: o.x, y: o.y }])).values()];
  const sc = scale(pts, w, h, pad);
  const sites = {};
  obs.forEach(o => { (sites[o.siteId] = sites[o.siteId] || []).push(o); });
  let dots = "";
  for (const [sid, list] of Object.entries(sites)) {
    const x = sc.sx(list[0].x), y = sc.sy(list[0].y);
    const hasGnss = list.some(o => o.type.startsWith("gnss"));
    const hasIn = list.some(o => o.type === "insar_los");
    const color = hasGnss ? "#5cc8ff" : "#ff8f5c";
    dots += `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="${hasGnss ? 4.5 : 2.6}" fill="${color}" opacity="0.85"><title>${sid}</title></circle>`;
  }
  $("map").innerHTML = `<svg width="100%" viewBox="0 0 ${w} ${h}">
    <text x="10" y="18" fill="#8d9bb5" font-size="11">站点分布（蓝=GNSS 台站，橙=InSAR 像素；西南象限缺测）</text>
    <line x1="${w/2}" y1="${pad}" x2="${w/2}" y2="${h-pad}" stroke="#22304a"/>
    <line x1="${pad}" y1="${h/2}" x2="${w-pad}" y2="${h/2}" stroke="#22304a"/>
    <text x="${w-pad}" y="${h/2-5}" fill="#8d9bb5" font-size="11" text-anchor="end">E (km)</text>
    <text x="${w/2+5}" y="${pad+10}" fill="#8d9bb5" font-size="11">N (km)</text>
    ${dots}</svg>`;
}

// ---- 反演 ----
function boundsFor(type) {
  const dipMax = parseFloat($("bDipMax").value);
  const sizeMax = parseFloat($("bSizeMax").value);
  const b = {
    depthMin: parseFloat($("bDepthMin").value),
    depthMax: parseFloat($("bDepthMax").value),
    dipMin: 0.0, dipMax: dipMax,
    lengthMin: 0.2, lengthMax: sizeMax,
    widthMin: 0.2, widthMax: sizeMax,
  };
  if (type === "MOGI") return { depthMin: b.depthMin, depthMax: b.depthMax };
  return b;
}
async function runInversion() {
  if (!state.dataset) { toast("请先选择数据集", true); return; }
  const type = $("modelType").value;
  const req = {
    datasetId: state.dataset.id, modelType: type,
    regLambda: parseFloat($("lambda").value),
    seed: parseInt($("seed").value), starts: parseInt($("starts").value),
    maxIter: 400, bounds: { ...new PhysicalBounds(), ...boundsFor(type) },
  };
  toast(`正在运行 ${state.lastLabel || ""}多起点反演（${req.starts} 起点）…`);
  try {
    const res = await api("/api/runs", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify(req),
    });
    state.lastRun = res;
    renderSolutions(res);
    if (res.solutions.length) {
      state.selectedSolution = res.solutions[0];
      $("cmpA").value = res.solutions[0].solutionId;
      renderResiduals(res.solutions[0]);
      stateGrid = null; renderField();
    }
    toast(`完成：${type} run #${res.runId}，最优 χ²=${res.solutions[0]?.chi2.toFixed(1)}`);
  } catch (e) { toast(e.message, true); }
}
// 服务端 PhysicalBounds 默认值（与 Kotlin 默认对齐）
function PhysicalBounds() {
  return { xMin:-10,xMax:10,yMin:-10,yMax:10,depthMin:0.05,depthMax:12,
    strikeMin:0,strikeMax:360,dipMin:0,dipMax:90,lengthMin:0.2,lengthMax:12,
    widthMin:0.2,widthMax:12,strengthMin:-1e8,strengthMax:1e8 };
}

const PARAM_LABEL = { x:"x (km)", y:"y (km)", depth:"深度 (km)", strike:"走向°", dip:"倾角°",
  length:"长度 (km)", width:"宽度 (km)", strength:"强度" };

function renderSolutions(res) {
  $("runMeta").innerHTML = `run <b>#${res.runId}</b> · ${res.type} · ${res.startCount} 个起点 · 各模型独立报告 χ²/BIC，不做跨模型综合排序`;
  const sols = res.solutions;
  state.lastSolutions = sols;
  const top = sols.slice(0, 10);
  let rows = top.map((s, i) => {
    const a = s.activeBounds;
    const boundTag = a.active
      ? `<span class="tag ${a.depthAtUpper ? "bad" : "warn"}">边界活跃${a.depthAtUpper ? "（深度上界）" : ""}</span>`
      : `<span class="tag ok">内部解</span>`;
    return `<tr class="clickable ${i===0?"best":""}" onclick="pickSolution(${s.solutionId})">
      <td class="l">#${s.solutionId} <span class="muted">rank${s.rank}/起点${s.startIndex}</span> ${boundTag}</td>
      <td>${s.chi2.toFixed(2)}</td>
      <td>${s.reducedChi2.toFixed(3)}</td>
      <td>${s.bic.toFixed(1)}</td>
      <td>${s.converged ? "✓" : "—"}</td>
      <td>${paramSummary(s.params, res.type)}</td>
    </tr>`;
  }).join("");
  // 参数分布（散点：全部起点的目标值与深度）
  const depthKey = "depth";
  const objMin = Math.min(...sols.map(s => s.objective)), objMax = Math.max(...sols.map(s => s.objective));
  const dMin = Math.min(...sols.map(s => s.params.depth)), dMax = Math.max(...sols.map(s => s.params.depth));
  const scatter = sols.map((s) => {
    const x = 30 + ((s.params.depth - dMin) / ((dMax - dMin) || 1)) * 820;
    const y = 200 - ((s.objective - objMin) / ((objMax - objMin) || 1)) * 170;
    const isBest = s.rank === 1;
    return `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="${isBest ? 6 : 3.2}"
      fill="${isBest ? "#ff6b7a" : "#5cc8ff"}" opacity="${isBest ? 1 : 0.6}">
      <title>起点${s.startIndex}: depth=${s.params.depth.toFixed(2)} obj=${s.objective.toFixed(1)}</title></circle>`;
  }).join("");
  $("solutions").innerHTML = `
    <table><thead><tr><th class="l">解</th><th>χ²</th><th>约化χ²</th><th>BIC</th><th>收敛</th><th class="l">参数</th></tr></thead>
      <tbody>${rows}</tbody></table>
    <div style="margin-top:12px">
      <div class="small muted" style="margin-bottom:4px">参数分布：深度 (km) → 目标函数（红=最优；点聚在边缘即提示边界效应）</div>
      <svg width="100%" viewBox="0 0 880 220">
        <line x1="30" y1="200" x2="850" y2="200" stroke="#2b3650"/>
        <line x1="30" y1="30" x2="30" y2="200" stroke="#2b3650"/>
        <text x="850" y="216" fill="#8d9bb5" font-size="11" text-anchor="end">depth ${dMin.toFixed(2)}–${dMax.toFixed(2)} km</text>
        ${scatter}
      </svg>
    </div>
    <div style="margin-top:8px">
      <a href="/api/runs/${res.runId}/export"><button class="sec">导出运行记录 JSON</button></a>
      <a href="/api/solutions/${sols[0].solutionId}/export"><button class="sec">导出最优解预测 CSV</button></a>
    </div>`;
}
function paramSummary(p, type) {
  const keys = type === "MOGI" ? ["x","y","depth","strength"]
    : ["x","y","depth","strike","dip","length","width","strength"];
  return keys.map(k => {
    const v = p[k];
    const txt = Math.abs(v) >= 1e4 ? v.toExponential(2) : v.toFixed(2);
    return `<span class="tag">${PARAM_LABEL[k]}: ${txt}</span>`;
  }).join("");
}
async function pickSolution(id) {
  const s = state.lastSolutions.find(x => x.solutionId === id);
  if (s) { state.selectedSolution = s; renderResiduals(s); renderField(); }
}

let stateGrid = null;
async function renderField() {
  const sol = state.selectedSolution, run = state.lastRun;
  if (!sol || !run) return;
  const comp = $("fieldComponent").value;
  const paramsArr = (run.type === "MOGI"
    ? ["x","y","depth","strength"] : ["x","y","depth","strike","dip","length","width","strength"])
    .map(k => sol.params[k]);
  if (!stateGrid || stateGrid.solId !== sol.solutionId) {
    const q = new URLSearchParams({
      solutionId: sol.solutionId, modelType: run.type,
      params: paramsArr.join(";"), span: "8", step: "0.5",
    });
    stateGrid = { solId: sol.solutionId, grid: (await api("/api/grid?" + q.toString())).grid };
  }
  const g = stateGrid.grid;
  const vals = g.map(p => p[comp]);
  let vmax = Math.max(...vals.map(v => Math.abs(v))), vmin = Math.min(...vals);
  const w=420,h=400,pad=30;
  const sc = scale(g, w, h, pad);
  const cells = g.map(p => {
    const t = vmax > 0 ? p[comp] / vmax : 0;
    const col = t >= 0 ? `rgba(255,143,92,${Math.min(0.95,0.15+Math.abs(t)*0.85)})`
                       : `rgba(92,200,255,${Math.min(0.95,0.15+Math.abs(t)*0.85)})`;
    return `<rect x="${(sc.sx(p.x)-2.6).toFixed(1)}" y="${(sc.sy(p.y)-2.6).toFixed(1)}" width="5.2" height="5.2" fill="${col}"/>`;
  }).join("");
  $("field").innerHTML = `<svg width="100%" viewBox="0 0 ${w} ${h}">
    <text x="10" y="18" fill="#8d9bb5" font-size="11">预测连续场（${comp}，峰值 ${(vmax*1000).toFixed(1)} mm）</text>${cells}</svg>`;
  $("fieldMeta").textContent = run.type + " 解 #" + sol.solutionId;
}

// ---- 残差、溯源、预测场 ----
async function renderResiduals(sol) {
  const preds = await api(`/api/solutions/${sol.solutionId}/predictions`);
  state.preds = preds;
  const w = 880, h = 330, pad = 30;
  const maxR = Math.max(...preds.map(p => Math.abs(p.residual)), 1e-9);
  const pts = [...new Map(preds.map(p => [p.observationId.split("-").slice(0,1).join("") + p.x.toFixed(2) + p.y.toFixed(2), { x: p.x, y: p.y }])).values()];
  const sc = scale(pts, w, h, pad);
  const dots = preds.map(p => {
    const t = Math.abs(p.residual) / maxR;
    const col = p.residual >= 0 ? `rgba(255,143,92,${0.25 + 0.75*t})` : `rgba(92,200,255,${0.25 + 0.75*t})`;
    const r = p.type === "insar_los" ? 2.6 : 4.2;
    return `<circle class="clickable" cx="${sc.sx(p.x).toFixed(1)}" cy="${sc.sy(p.y).toFixed(1)}" r="${r}" fill="${col}"
      onclick='showTrace("${p.observationId}")'><title>${p.observationId} 残差 ${(p.residual*1000).toFixed(2)} mm</title></circle>`;
  }).join("");
  const byType = groupStats(preds, r => r.type);
  const byRegion = groupStats(preds, r => r.region);
  const a = sol.activeBounds;
  $("resid").innerHTML = `
    <div class="twocol">
      <div>
        <div class="small muted" style="margin-bottom:4px">空间残差（橙=正、蓝=负；点任意点查看溯源）</div>
        <svg width="100%" viewBox="0 0 ${w} ${h}">
          <line x1="${w/2}" y1="10" x2="${w/2}" y2="${h-pad}" stroke="#22304a"/>
          <line x1="${pad}" y1="${h/2}" x2="${w-pad}" y2="${h/2}" stroke="#22304a"/>
          ${dots}
        </svg>
        <div id="trace" class="small muted">点击任一观测点显示该预测值的几何/坐标变换溯源。</div>
      </div>
      <div>
        <div><b>边界状态：</b>${a.active
          ? `<span class="badge-active">边界活跃</span>（非内部稳定解）`
          : `<span class="badge-inner">内部解</span>`}</div>
        ${a.details.length ? `<pre class="meta">${a.details.join("\n")}</pre>` : ""}
        <div class="small muted">按观测类型残差 RMS：</div>
        ${statTable(byType)}
        <div class="small muted" style="margin-top:8px">按区域残差 RMS：</div>
        ${statTable(byRegion)}
      </div>
    </div>`;
}
function groupStats(preds, keyFn) {
  const g = {};
  preds.forEach(p => {
    const k = keyFn(p);
    g[k] = g[k] || { n: 0, ss: 0, sum: 0 };
    g[k].n++; g[k].ss += p.residual * p.residual; g[k].sum += p.residual;
  });
  return Object.entries(g).map(([k, v]) => ({ key: k, n: v.n, rms: Math.sqrt(v.ss / v.n), mean: v.sum / v.n }));
}
function statTable(rows) {
  return `<table><thead><tr><th class="l">组</th><th>n</th><th>均值(mm)</th><th>RMS(mm)</th></tr></thead><tbody>${
    rows.map(r => `<tr><td class="l">${r.key}</td><td>${r.n}</td><td>${(r.mean*1000).toFixed(2)}</td><td>${(r.rms*1000).toFixed(2)}</td></tr>`).join("")
  }</tbody></table>`;
}
function showTrace(obsId) {
  const p = state.preds.find(x => x.observationId === obsId);
  if (!p) return;
  const los = p.losE == null ? "GNSS 分量基向量"
    : `LOS=(${p.losE.toFixed(4)}, ${p.losN.toFixed(4)}, ${p.losU.toFixed(4)}) |l|=1`;
  $("trace").innerHTML = `<pre class="meta">观测 ${p.observationId}（${p.type}, 时段 ${p.epoch}, 区域 ${p.region}）
位置 ENU(${p.x}, ${p.y}) km  ｜  ${los}
观测 ${(p.observed*1000).toFixed(3)} mm ｜ 预测 ${(p.predicted*1000).toFixed(3)} mm ｜ 残差 ${(p.residual*1000).toFixed(3)} mm
正演引擎: ${p.engineVersion}
坐标版本: ${p.coordVersion} ｜ 坐标变换: ${p.transformVersion}
深度口径: ${p.depthSignConvention}
反演参数: ${JSON.stringify(mapRound(p.params))}</pre>`;
}
function mapRound(m) { const o = {}; for (const k in m) o[k] = +m[k].toFixed(4); return o; }

async function compare() {
  const a = parseInt($("cmpA").value), b = parseInt($("cmpB").value);
  if (!(a > 0 && b > 0)) { toast("请填写两个候选 solutionId", true); return; }
  try {
    const mode = $("cmpMode").value;
    const res = await api("/api/compare", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ solutionA: a, solutionB: b, mode }),
    });
    const sameEngine = res.engineVersionA === res.engineVersionB;
    $("compareOut").innerHTML = `<table><thead><tr><th class="l">${modeLabel(mode)}</th><th>n</th>
      <th>RMS_A(mm)</th><th>RMS_B(mm)</th><th>预测差 RMS(mm)</th></tr></thead><tbody>${
      res.groups.map(g => `<tr><td class="l">${g.key}</td><td>${g.count}</td>
        <td>${(g.rmsA*1000).toFixed(2)}</td><td>${(g.rmsB*1000).toFixed(2)}</td>
        <td>${(g.rmsPredictionDifference*1000).toFixed(2)}</td></tr>`).join("")
    }</tbody></table>
    <div class="small muted" style="margin-top:6px">
      A 引擎: ${res.engineVersionA}<br>B 引擎: ${res.engineVersionB}
      ${sameEngine ? "" : "<br><span class='tag warn'>两候选引擎/版本不同，差分仅供参考</span>"}
    </div>`;
  } catch (e) { toast(e.message, true); }
}
function modeLabel(m) { return { TYPE: "观测类型", REGION: "区域", EPOCH: "时段" }[m]; }

loadConventions();
refreshDatasets();
