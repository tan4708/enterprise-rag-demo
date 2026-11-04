# 接口文档 · HTTP API

## ▎1. 通用约定

| 项 | 约定 |
| --- | --- |
| 生产入口 | `http://localhost:8080`（backend 直连）、`http://localhost:8081`（Nginx 反代 `/api` 到 `backend:8080`） |
| 演示版入口 | `http://127.0.0.1:8765`（可用 `RAG_PORT` 环境变量或首个启动参数覆盖） |
| 路径前缀 | 统一 `/api`；前端静态资源与演示页在 `/` |
| 表单编码 | `application/x-www-form-urlencoded`（演示版全部接口、生产版问答）；生产版上传为 `multipart/form-data` |
| 响应编码 | `application/json; charset=utf-8`；流式为 `text/event-stream`，统一 UTF-8 |
| 跨域 | 演示版对 JSON 与 SSE 响应均返回 `Access-Control-Allow-Origin: *`；生产版由 Nginx 同源反代，无需跨域 |
| 流式缓冲 | SSE 响应带 `Cache-Control: no-cache` 与 `X-Accel-Buffering: no`，防止 Nginx / 代理层攒包 |

**接口路径在生产版与演示版之间保持一致**，这是刻意的：同一份前端、同一套 curl 脚本、同一个冒烟测试（`demo/smoke.py`）可以直接跑在两种形态上，差异只在"证据从哪来"。

**鉴权。** 生产版接口层有**双层权限**：系统角色（平台级功能权限）+ 组内角色（知识库组可见范围），两层都通过才进入业务逻辑。演示版为保持零依赖不校验身份。

**去重与限流。** 生产版对同一答题请求做 **single-flight** 去重（多实例下同一问题 + 同一证据集只调一次模型），并有限流、超时与取消；演示版无这些能力，接口行为上表现为"恒等返回、无速率限制"。

## ▎2. 接口总表

| # | 方法 | 路径 | 入参 | 出参 | 生产 | 演示 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | GET | `/` | 无 | HTML（Vue 产物 / 演示页） | ✔ | ✔ |
| 2 | GET | `/api/health` | 无 | `{"status":"ok","uptimeMs":n,"version":"demo-1.0"}` | ✔ | ✔ |
| 3 | GET | `/api/stats` | 无 | 文档数 / 切片数 / 词表大小 / 平均片长 / 检索器标识 | ✔ | ✔ |
| 4 | POST | `/api/docs` | 生产：`multipart` 字段 `file`；演示：form `title` + `content` | `{"id":1,"title":"...","chunks":27}` | ✔ | ✔ |
| 5 | GET | `/api/docs` | 无 | 文档数组 `[{"id","title","chunks","chars"}]` | ✔ | ✔ |
| 6 | POST | `/api/search` | form：`q` | `{"tookUs":n,"hits":[{"docId","seq","title","text","fused","bm25","cosine","bm25Rank","vecRank","coverage"}]}` | ✔ | ✔ |
| 7 | POST | `/api/ask` | form：`q` | 非流式答案：`{"answer","refused","reason","tookMs","citations":[...]}` | ✔ | ✔ |
| 8 | POST | `/api/chat` | form：`q`（+ 生产可选 `sessionId`） | SSE：`meta` → `delta`… → `done` | ✔ | ✔ |

`/api/search` 是**检索调试接口**（只跑召回与 RRF，不生成答案），排障时用来区分"检索没召回到"和"模型没答好"——这两类问题的修法完全不同。`/api/ask` 是 `/api/chat` 的非流式版本，返回同一个 `Answer` 结构，便于脚本化测试与评测集批跑。

## ▎3. 逐接口说明

### 3.1 `GET /api/health`

```json
{ "status": "ok", "uptimeMs": 128409, "version": "demo-1.0" }
```

`uptimeMs` 为进程启动至今的毫秒数。生产版在此基础上把依赖组件分开探测（PostgreSQL / Elasticsearch / MinIO / Redis / Ollama），任一不可用时能定位到具体组件而不是笼统 500；Spring Boot 的内置探针 `/actuator/health` 仍然保留，供容器 readiness/liveness 使用，两者不冲突：`/api/health` 面向业务排障，`/actuator/health` 面向编排。

### 3.2 `GET /api/stats`

```json
{
  "docs": 5,
  "chunks": 27,
  "vocab": 1843,
  "avgChunkLen": 118.4,
  "embedding": "hash-projection-512d",
  "retriever": "bm25+vector+rrf(k=60)",
  "uptimeMs": 128409
}
```

演示版的 `embedding` 字段如实标注为哈希投影（512 维），而不是假装成 `bge-m3`——这是刻意的：它让人一眼看出"这是零依赖近似语义检索"，不会把演示效果误读为模型能力。生产版此处对应真实向量模型与 pgvector。（上例数值仅示意字段结构，实际值随入库文档变化。）

### 3.3 `POST /api/docs`

| 形态 | Content-Type | 字段 | 说明 |
| --- | --- | --- | --- |
| 生产 | `multipart/form-data` | `file` | 允许 pdf / docx / md / txt；原始文件落 MinIO 桶 `kb-docs` |
| 演示 | `application/x-www-form-urlencoded` | `title`、`content` | 两者均必填，缺失返回 400 |

生产链路：MinIO 存原文 → 解析抽取 → 清洗 → 切片（400 字 / 重叠 80 字，保留章节标题）→ `bge-m3` 向量化写 pgvector → 文本写 Elasticsearch（IK 分词）。

演示链路：`title`/`content` → `Text.chunks()` 切片 → 逐片计算哈希向量 + 写入 BM25 倒排 → 追加写入 `data/docs.tsv` 持久化。

响应：

```json
{ "id": 1, "title": "差旅报销制度", "chunks": 3 }
```

`id` 为自增整数（演示版重启后从 `docs.tsv` 恢复并延续）。生产版返回文档 id 字符串，语义等价。

### 3.4 `GET /api/docs`

返回**数组**（非分页对象）：

```json
[
  { "id": 1, "title": "项目简介 · 企业知识库 RAG 问答系统", "chunks": 2, "chars": 218 },
  { "id": 2, "title": "技术栈与架构 · 七个容器", "chunks": 2, "chars": 246 }
]
```

`chunks` 是切片数，`chars` 是原始正文字数——两者一起能快速判断切片是否异常（例如 `chunks` 远大于 `chars/400`，说明切分逻辑被改坏了）。演示版首次启动会灌入 5 篇示例文档（项目简介、技术栈、部署运维、检索参数、二次开发记录）以保证开箱可检索。

### 3.5 `POST /api/search`

只跑检索，不生成。用于验证召回质量：

```json
{
  "tookUs": 6718,
  "hits": [
    {
      "docId": 4, "seq": 1, "title": "检索参数与调优 · RRF 融合",
      "text": "切片策略是每片 400 字、相邻重叠 80 字…",
      "fused": 0.0328, "bm25": 6.417, "cosine": 0.255,
      "bm25Rank": 1, "vecRank": 1, "coverage": 0.772
    }
  ]
}
```

（上例的数值仅示意结构与量级，实际分值与语料强相关；`fused = 0.0328` 是两路都排第 1 时的理论值。）

`bm25Rank` / `vecRank` 为 0 表示该路未召回该切片；`fused` 是 RRF 融合分；`coverage` 是该片段对查询的 IDF 加权覆盖率，**仅作可观测指标输出，不参与拒答判定**（拒答看 `bm25`，见 4.3）。同时看两列排名，就能判断某条证据是"两路共识"还是"单路偏好"。

### 3.6 `POST /api/ask`

非流式作答，返回完整答案与引用：

```json
{
  "answer": "根据知识库：语义召回与关键词召回各取前 20 条候选，使用 RRF 融合… [1]",
  "refused": false,
  "reason": "",
  "tookMs": 3,
  "citations": [
    { "index": 1, "title": "检索参数与调优 · RRF 融合", "snippet": "…", "score": 0.0328 }
  ]
}
```

`"refused":true` 时 `citations` 为空、`reason` 给出拒答原因（如"证据不足：Top1 证据强度 0.546 < 1.800"，两种原因见 4.3）。**拒答是正常返回而非错误**，HTTP 状态仍为 200——前端要据此渲染提示，而不是当作失败重试。`refused` 是标准 JSON 布尔，脚本与前端直接用真值判断即可。

### 3.7 `POST /api/chat`

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `q` | 是 | 用户问题原文；追问消解在服务端完成 |
| `sessionId` | 否（生产） | 多轮会话标识，缺省时新建并在 `meta` 帧回传 |

一次请求内部完成：读 Redis 最近 N 轮上下文 → 必要时改写追问 → 双路召回（pgvector 余弦 TopK=20、ES BM25 TopK=20）→ RRF 融合（`score = Σ 1/(60 + rank_i)`）取 Top 5 → 证据约束 Prompt → 流式输出。

## ▎4. SSE 协议

### 4.1 帧格式

标准 SSE：`event:` 行 + `data:` 行，事件之间以空行分隔。实现见演示版 `sse()`：

```
event: meta
data: {...}

event: delta
data: {"t":"根据知识库："}

event: done
data: {...}
```

| 事件 | 时机 | 载荷 |
| --- | --- | --- |
| `meta` | 检索完成、开始输出前 | `refused`、`reason`、`citations[]`（`index` / `docId` / `seq` / `title` / `snippet` / `score`）；生产版另带 `sessionId` |
| `delta` | 输出过程中 | `{"t":"文本片段"}` |
| `done` | 结束 | `{"tookMs":n,"refused":bool}` |

**时序保证**：`meta` 一定先于任何 `delta`；`done` 是最后一个事件，之后服务端关闭连接。`delta` 条数不定，不得假设至少一条——拒答时正文很短，仍可能有多帧。

**先推 `meta` 的原因**：前端在正文出现前就能渲染证据卡片，用户对答案的信任建立在"先看到来源"，而不是等全文结束再补引用。

### 4.2 一次正常问答的完整帧

```
event: meta
data: {"refused":false,"reason":"","citations":[{"index":1,"docId":4,"seq":1,"title":"检索参数与调优 · RRF 融合","snippet":"切片策略是每片 400 字、相邻重叠 80 字，并保留章节标题作为元数据。","score":0.0328},{"index":2,"docId":1,"seq":1,"title":"项目简介 · 企业知识库 RAG 问答系统","snippet":"…","score":0.0164}]}

event: delta
data: {"t":"根据知识库："}

event: delta
data: {"t":"切片策略是每片 400 字"}

event: done
data: {"tookMs":421,"refused":false}
```

要点：

- `citations[].index` 与正文中的 `[n]` 一一对应；`index` 从 1 开始，最多 5 个（`MAX_CITATIONS = 5`）。
- `score` 是 RRF 融合分。两路都排第 1 的切片约为 `1/61 + 1/61 ≈ 0.0328`，只在单路排第 2 约为 `1/62 ≈ 0.0161`，这正是"两路共识优先"的直接体现。
- `refused` 是标准 JSON 布尔（`meta` 与 `done` 帧一致），脚本端 `if payload["refused"]` 即可。早期实现曾用 `String.valueOf(boolean)` 输出字符串，导致 `not "false"` 恒为假、冒烟矩阵假失败——这类坑已修掉，但值得记一笔：**协议字段的类型也是契约的一部分**。
- 演示版按每帧 8 字、间隔 15ms 输出以模拟流式，因此 `tookMs` 包含的是**拼接与 sleep 的时间**，不代表真实模型延迟。生产版按模型 token 推送，`tookMs` 才是端到端耗时。两者不可互相比对。

### 4.3 拒答帧

证据不足时，演示版**不调用任何模型**，直接返回拒答。`Answer.build` 有两道闸，按顺序判定：

| 闸 | 条件 | `reason` |
| --- | --- | --- |
| 1 | 问题里的词在知识库中一个都没出现过（全部 token 的 `df == 0`） | `证据不足：提问中的词在知识库中均未出现` |
| 2 | Top1 片段的 BM25 证据强度 `< MIN_BM25`（1.8） | `证据不足：Top1 证据强度 0.546 < 1.800` |

第 2 道闸是纯词法命中判定做不到的：一个问题可以命中几个高频词（"怎么""什么"），却完全没落在文档实际讲的内容上。**为什么用 BM25 而不是覆盖率**：曾尝试以"问题词的 IDF 加权覆盖率 < 0.34"作判据，实测无法区分——切片大小问题（该作答）cov=0.082，而"这个系统的开发者是谁"（该拒答）cov=0.074，只差 0.008。根因是提问里那些 `df=0` 的二元组被赋予了最大 IDF，撑爆分母，把本该作答的问题也压到 0.1 以下。改用 Top1 的绝对 BM25 分数就干净了：1.8 这个阈值由 `demo/probe_scores.py` 在 12 个标注问题上实测标定（打分表见 `demo/probe_scores.txt`），也提成了具名常量 `MIN_BM25`，换语料要重标定。

```
event: meta
data: {"refused":true,"reason":"证据不足：Top1 证据强度 0.546 < 1.800","citations":[]}

event: delta
data: {"t":"当前知识库中没有检索到足够支撑该问题的证据，已拒答（避免无依据生成）。可以换个问法，或先在左侧上传相关文档。"}

event: done
data: {"tookMs":2,"refused":true}
```

两处细节值得注意：拒答时 `citations` 为空数组而非缺省；正文里给出**下一步建议**（换问法或补文档），而不是冷冰冰一句"无法回答"——拒答要让人知道下一步做什么，否则用户只会反复重试同一个问题。

### 4.4 取消

客户端断开即取消：生产版在流式回调里检测写失败并中止模型推理，释放算力；没有单独的取消接口。演示版的 `delta` 循环一旦写响应体抛异常就终止，不会继续 sleep 空转。

## ▎5. curl 示例

> Windows PowerShell 中 `curl` 是 `Invoke-WebRequest` 的别名，请使用 `curl.exe`。以下命令在 CMD / Git Bash / `curl.exe` 下均可直接执行。
>
> 示例默认指向演示版 `8765`（无需 Docker 即可跑通）；测生产版把端口换成 `8080` 或 `8081`。

### 5.1 健康检查

```bash
curl -s http://127.0.0.1:8765/api/health
# 生产：curl -s http://localhost:8080/api/health
```

期望 `{"status":"ok","uptimeMs":...,"version":"demo-1.0"}`。

### 5.2 上传文档

```bash
# 演示形态：标题 + 正文（注意用 -d，不是 -F）
curl -s -X POST http://127.0.0.1:8765/api/docs \
  -d "title=差旅报销制度" \
  -d "content=一线城市住宿标准为每晚 600 元，需提供发票；二线城市为每晚 400 元。"

# 生产形态：上传文件
curl -s -X POST http://localhost:8080/api/docs \
  -F "file=@./员工手册.pdf"
```

期望返回 `{"id":6,"title":"差旅报销制度","chunks":1}`。

**这里有个容易踩的坑**：演示版的 `readForm()` 只按 `application/x-www-form-urlencoded`（`k=v&k=v`）解析请求体，用 `-F` 发出的 `multipart/form-data` 体无法被拆出字段，`title` / `content` 会取到空串而直接返回 400。所以演示版一律用 `-d`；`-F` 只服务于生产版的真实文件上传。两者路径相同、编码不同，写脚本时要注意切换。

### 5.3 流式问答

```bash
curl -N -s -X POST http://127.0.0.1:8765/api/chat \
  -d "q=切片策略是什么"
```

`-N` 关闭缓冲，否则 curl 会把整条流攒到最后一次打印，看不出流式效果。观察点：首帧必须是 `event: meta`，`citations` 非空，末帧 `event: done`。

多轮追问（生产版验证消解）：

```bash
curl -N -s -X POST http://localhost:8080/api/chat \
  -d "q=那二线城市呢" -d "sessionId=s_2049"
```

服务端应把"那二线城市呢"结合上一轮还原成完整问题；演示版无会话，会按字面检索。

### 5.4 文档列表

```bash
curl -s http://127.0.0.1:8765/api/docs
```

期望返回 JSON 数组，元素含 `id` / `title` / `chunks` / `chars`。

### 5.5 附：检索调试与冒烟测试

```bash
# 只看召回与 RRF 排名，验证检索侧是否正常
curl -s -X POST http://127.0.0.1:8765/api/search -d "q=七个容器分别负责什么"

# 仓库自带冒烟脚本：健康 / 统计 / 检索 / 问答 / 拒答 / SSE 六项一次性验证
cd demo
python smoke.py
cat smoke_result.txt        # Windows CMD / PowerShell: type smoke_result.txt
```

`smoke.py` 覆盖六条关键路径，其中"拒答路径"用知识库外的问题（训练多模态大模型）触发，是验证防幻觉是否真的生效的最快方式。脚本把结果写进 `smoke_result.txt` 而不是打到 stdout，避免 Windows 终端编码把中文输出搞乱。

## ▎6. 错误响应

统一错误体：

```json
{ "error": "title 与 content 均不能为空" }
```

| HTTP | 场景 | 调用方处理 |
| --- | --- | --- |
| 400 | 参数缺失/非法（`q` 为空、`title` 或 `content` 缺失、文件类型不支持） | 修正入参，不重试 |
| 401 / 403 | 未登录 / 系统角色或组内角色不足（仅生产版） | 重新登录或提示无权限，不重试 |
| 404 | 路径不存在（演示版返回 `{"error":"not found"}`） | 核对路径前缀 `/api` |
| 413 | 上传文件超出限制（仅生产版） | 压缩或拆分 |
| 429 | 触发限流（仅生产版） | 退避后重试 |
| 500 | 未预期异常，`error` 字段带简述 | 记录 traceId 后反馈 |
| 503 / 504 | 依赖不可用 / 生成超时（仅生产版） | 退避重试，先查 `/api/health` |

排障顺序：先 `/api/health` → 再 `/api/search`（判断检索是否正常）→ 最后 `/api/ask`（判断生成与拒答是否符合预期）。这三步能把"召回问题"和"生成问题"分开，避免在错误的方向上调 Prompt。

## ▎7. 生产版与演示版的实现差异（读接口前必看）

| 维度 | 生产版 | 演示版 |
| --- | --- | --- |
| 语义召回 | pgvector + HNSW + `bge-m3` | 512 维哈希投影 + 余弦暴力扫描 |
| 关键词召回 | Elasticsearch 8 + IK + BM25 | 进程内倒排 + BM25（k1=1.2, b=0.75） |
| 中文分词 | IK（`ik_max_word`） | 汉字二元组 + 拉丁词（无词典） |
| 生成 | Ollama `qwen2.5:7b` 流式 | 无模型调用，抽取式拼接证据句子 |
| 会话 | Redis 多轮 + 追问消解 | 无状态 |
| 存储 | MinIO + PG + ES + Redis | `data/docs.tsv` 行文本（base64 字段） |
| 权限/限流/去重 | 有 | 无 |

演示版是**抽取式**的：它从 Top 5 证据里挑与问题词重叠最高的句子（最多用 3 个片段、最多 5 条引用）拼成答案并标 `[n]`，因此没有生成模型的延迟与幻觉，也无法验证 Prompt 遵循度。它的职责是验证链路结构与拒答闸门，不是验证回答质量——这一点在对外介绍时应说明清楚，不要拿演示版的"毫秒级响应"当系统指标。

## ▎8. 待实测

以下数据的采集入口已就位，但数值必须在真实环境跑出来再填，本文不写推测值：`demo/smoke.py` 会把每次冒烟的逐项结果（状态码、`tookUs`、`tookMs`、首字节与总耗时、SSE 事件计数）写进 `demo/smoke_result.txt`，可直接作为采集模板。

> 待运行填入：`/api/search` 的 `tookUs` 在 1 万 / 10 万切片规模下的分布（P50/P95）。
>
> 待运行填入：`/api/chat` 生产版首帧（`meta`）延迟、首 token 延迟、完整回答耗时。
>
> 待运行填入：自建评测集上的拒答准确率（该拒答的拒答、不该拒答的未拒答）。
