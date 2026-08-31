# 搜索（SEARCH）

## 索引内容（spec 22）
文件名、文件夹、标签、文本框、PDF 文本、Office 提取文本、OCR 文本、手写识别文本、转写文本、页面标题。

## 技术方案
- **存储**：Room FTS4 虚表 `search_index`（source/documentId/pageId/content，rowid）。注意：`docid` 是 FTS 保留列名，列名必须用 `documentId`。
- **中文**（spec 22.2）：`TextNormalizer`（全半角统一、大小写、空白折叠）+ `NGramTokenizer`（CJK 连续字转 2-gram，拉丁按词）解决中文无空格分词；连续中文词查询以 bigram AND 命中；拉丁词前缀 `*`。
- **增量索引**（spec 35）：文本框/OCR/手写/Office/转写完成时只替换对应 document+page+source 段（`IndexDao.replaceSearchSegment`），绝不全库重建。
- **排序**：`SearchRanking` 源类型权重（文件名 > 标题 > 标签 > 文本框 > PDF/Office > 手写 > 转写 > OCR）× 词覆盖度；低置信 OCR 降权 70%。
- **执行**：feature-search `SearchQueryBuilder`（防抖 300ms、可取消、后台线程）；结果合并排序后点击跳转到页并按 boundingBox 高亮 2-3 秒（编辑器侧实现）。

## UI
筛选 tab：全部/笔记/PDF/Office/OCR/手写/转写；结果含类型图标、标题、命中上下文 snippet（高亮）、路径、时间。
