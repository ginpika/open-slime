# 知识图谱渲染检查与修复（2026-10-19）

范围：`GraphCanvas`、`GraphECharts`、共享 `graphLayout` 及图谱页的选中标识与视角适配传递。

## 已确认的缺陷与修复

| 位置 | 缺陷与影响 | 修复 |
| --- | --- | --- |
| 自研动画生命周期 | 卸载没有取消正在运行的 rAF，切换引擎后仍会继续模拟 | 卸载、清空图时停止循环，并清理校准计时器 |
| 两引擎的拓扑判断 | 依据 nodes/edges 数组引用判断变化；修改属性也执行 300 次布局，改变用户拖好的节点位置 | 比较实体 ID、关系 ID 与端点；显示名称等视觉更新与拓扑分开 |
| 静态增量展开 | seed 只是起点，所有旧节点仍参与积分，与“旧节点不动”的注释不符 | 静态排布固定有效 seed，只安置新增节点；开启物理后仍允许运动 |
| ECharts 选中 | 选中改变 option，调用 setOption 后重新创建 force 模拟 | 使用独立 highlight/downplay 动作，保留相邻边高亮 |
| ECharts 物理切换 | 直接写入 preservedPoints 私有缓存，且数据包围盒变化导致画面缩放与偏移 | 切换时读取当前坐标并替换 series；用公开像素转换保存观察中心与比例，开启等比适配 |
| ECharts 整图更新 | 未接收图谱页 fitOnUpdate，增量与整图更新无法区分 | 两引擎接收同一视角策略 |
| 自环 | SVG 端点重合生成零长度路径；ECharts 原生自环曲线同样退化 | SVG 使用三次曲线；ECharts 自环用随 rendered/roam 更新的 SVG 层显示并支持点击 |
| 多重边 | 每条边线性寻找槽位，密集关系每帧退化；相邻边标签间距过小 | 预计算稳定槽位、增加曲线间隔；箭头沿曲线切线裁切 |
| SVG 交互与边界 | 拖拽把节点中心吸到指针；缺少 pointercancel；多实例 marker ID 冲突；零尺寸 fit、长标签与自环未计入边界 | 保持抓取偏移、跟踪 pointerId、处理取消与捕获丢失、实例独立 marker；延迟零尺寸 fit，测量文本并计入曲线边界 |
| 共享布局数值 | 非有限 seed 会污染全图，单节点缩略图归一化偏离正中心 | 丢弃无效 seed，零跨度按实际中心归一化 |
| 自研大图斥力 | 每次初始布局 300 × O(n²)，明显阻塞主线程 | 180 节点起使用 Barnes–Hut 四叉树（theta=0.7）；小图继续精确计算；所有节点已固定时跳过模拟 |
| ECharts 物理拖拽松手 | 原生 force 在固定拖拽时只更新当前位置 p，历史位置 pp 仍停在拖拽前；松手时产生虚假速度，实际绘制帧中复现约 60px 向外跳跃 | 在实例级适配器中同步固定节点的 p/pp，并在释放后的第一次积分前同步最后拖拽位置；不重建 force、不清除其他节点速度 |
| ECharts 背景平移区域 | 等比适配后默认 selfRect 把平移响应限制在图内容矩形，四周空白不能拖动；图移出原区域后更难继续操作 | 显式设置 roamTrigger: global，让整个图表画布响应镜头平移与缩放 |

布局与关系几何仍为独立的纯函数，React 组件负责生命周期和交互。ECharts 继续负责普通节点、关系的绘制、拖拽与 force 模拟，SVG 只补充其退化自环。

## 设计取舍

- 保留两种引擎：自研 SVG 的路径与交互容易定制，ECharts 提供普通图元素和拖拽模拟。当前问题主要出在状态更新和组件生命周期；替换整套引擎会增加迁移与交互回归成本，不能直接消除这些接入问题。
- 斥力优先采用四叉树：直接改善共享算法，保留确定性和原有力参数。Worker 能隔离主线程阻塞，但不会自行降低算法复杂度，还需要序列化、取消与增量通信；若更大图实测仍阻塞，再增加这层边界。
- ECharts 自环采用小范围 SVG 补绘：保留原始实体/关系身份，不创建会污染布局和选中的虚拟实体。代价是需在绘制、缩放和平移时同步像素位置，因此增加了独立订阅与卸载清理。

已结合安装的 ECharts 源码核对 force 初始化、拖拽坐标写回和 view 坐标变换；其公开布局契约见 [Apache ECharts graph 配置说明](https://apache.googlesource.com/echarts-doc/+/1a18eeb06d0f47e21831f219be48e34d39d6b261/en/option/series/graph.md)。

## 验证与复现

```powershell
npm --prefix frontend run test:graph
npm --prefix frontend run test:graph:browser
npm --prefix frontend run bench:graph
npm --prefix frontend run build
```

浏览器脚本使用 Playwright 和本地 Chrome；若 Playwright 不在前端依赖中，设置 `MINIS_PLAYWRIGHT_DIR` 到包含 Playwright 的 `node_modules` 上级目录。脚本以 Vite 内存构建和路由拦截加载真实组件，不依赖后端和 loopback 服务。

- 纯函数回归：固定旧节点、确定性、无效 seed、重合节点、单节点居中、属性更新、标识冲突、自环、双向多重边，以及 256 节点近似结果对精确计算的总速度误差小于 3%。
- 真实 Chrome 交互：两引擎拖拽、自环点击、属性编辑后位置不变、静态增量展开、关系过滤与滚轮缩放、选中不重建 force、物理开关坐标与像素位置保持、冻结后不动、卸载后不再调度动画；使用 StrictMode 包装。追加物理开/关时从画布四角平移、移出原图边界后拖回，以及捕获松手后实际 rendered 帧检查异常外跳。
- 浏览器截图：`backend/target/graph-browser-check/graph-engines.png`。
- 前端类型检查和生产构建通过；构建仍报告现有大 bundle 提示。

同机 Node v22.12.0、相同星形图、300 次迭代的单次测量（不是浏览器帧率，也不是性能承诺）：

| 节点 | 原实现 | 修改后 |
| --- | ---: | ---: |
| 100 | 64 ms | 68 ms |
| 300 | 342 ms | 287 ms |
| 1000 | 6925 ms | 1283 ms |

## 边界

- 初始布局仍同步执行；千节点样本已明显加速，但没有证明万级图能流畅渲染。SVG DOM 更新及 ECharts 自身 force 的大图成本仍在，需要更大规模时应按实测引入 Worker 或分层显示。
- ECharts 内部适配包含带运行时检查的坐标读回，以及 `echartsForceDrag.ts` 中针对 6.1 原生惯性缺陷的实例级 beforeStep/setUnfixed 兼容处理。后者保留原固定节点坐标同步，仅同步拖拽节点的惯性历史；不修改依赖包或 preservedPoints 缓存。升级 ECharts 必须重跑松手逐帧与物理切换用例，并核对其 beforeStep 契约。
- 浏览器验证使用确定数据与真实绘制组件，没有验证生产知识库的所有实际分布，也未覆盖全页后端请求竞态。
- 主题或显示名称变化仍需更新 ECharts option，物理开启时会继续模拟；选中和附加属性更新已不再走此路径。
