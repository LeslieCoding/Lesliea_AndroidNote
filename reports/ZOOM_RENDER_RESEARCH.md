# 高倍放大书写清晰度 — 竞品渲染策略调研报告

日期：2026-08-26 ｜ 对应版本：v1.4.15 改造依据

## 用户问题

放大倍数稍大后书写/已有笔迹变模糊，无法做到"放大记小字"。

## 竞品策略调研结论

### 1. GoodNotes / Notability（iPad 笔记双雄）
- 墨水是**矢量数据**（vector-based ink），笔画以路径+压感参数存储，可任意缩放不失真。
  来源：[Astropad: Goodnotes vs. Notability](https://astropad.com/blog/goodnotes-vs-notability/)（"It uses vector-based ink, meaning you can scale up or scale down your creation"）
- 显示时按**当前缩放级别实时重栅格化**，而不是把低分辨率位图放大。

### 2. Concepts（无限画布标杆）
- 官方自述为 **vector-raster hybrid**：每一笔都是可编辑矢量；显示层的栅格缓存始终由矢量数据按当前 zoom 重新生成，"drawings remain crisp even when enlarged"。
  来源：[Concepts vs Procreate](https://concepts.app/en/concepts-vs-procreate/)、[Concepts 官网](https://concepts.app/)

### 3. Saber（开源，Flutter 跨平台）
- 笔迹视为**矢量路径**而非像素点："笔迹可以无损缩放，放大后依然清晰"；分层渲染不同类型笔迹。
  来源：[Saber 技术原理简析](https://blog.gitcode.com/2da7852df6754bd009b626dc0928e826.html)

### 4. Xournal++（开源桌面笔记）
- 高 DPI 下靠**按输出设备分辨率重渲染**（DPI 自适应 + 从矢量 stroke 模型重绘可见区），而非位图拉伸。
  来源：[Xournal++ 渲染优化指南](https://blog.csdn.net/gitblog_00292/article/details/160296867)

## 提炼出的工程原则（集成标准）

1. **矢量是唯一事实源**：笔迹存储为矢量路径（本项目已满足），任何显示位图都只是缓存。
2. **显示缓存必须按当前 zoom 从矢量重栅格化**：禁止把低分辨率位图放大取样后显示（这正是模糊根源）。
3. **重栅格化粒度 = 屏幕可见区域**（视窗），分辨率 = 设备物理像素；zoom/pan 变化后防抖重渲。
4. **阈值要激进**：只要有效放大倍数超过位图缓存分辨率 ×1.0（而非 ×1.25 留余量），就切到视窗级矢量重渲；缩放交互中允许短暂模糊，停顿 ≤300ms 内必须变清晰。
5. **书写中的活笔迹**始终按屏幕分辨率矢量绘制（覆盖层），不经过位图缓存。

## 对本项目的改造要求

- 核查 `InkView.java` 视窗渲染路径：视窗位图必须**从矢量笔迹数据重新栅格化**，不得从封顶 4.58x 的整页位图放大合成。
- 视窗启用阈值从 ×1.25 降至 ×1.0（或更低），消除"模糊过渡带"。
- 10x / 20x / 32x 真机验证：笔迹边缘锐利、笔锋尖细、位置正确。
