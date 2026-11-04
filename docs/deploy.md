# 部署手册 · Docker Compose 一键起栈

## ▎1. 前置要求

### 1.1 软件

| 项 | 要求 | 检查命令 |
| --- | --- | --- |
| Docker Desktop | 已启用 WSL2 后端（Windows）/ 原生 Docker（macOS、Linux） | `docker version` |
| Docker Compose | v2（`docker compose` 子命令，非 `docker-compose`） | `docker compose version` |
| 磁盘 | 建议 ≥ 20 GB 可用（镜像 + 模型 + 索引） | `docker system df` |
| 浏览器 | 任意现代浏览器，用于访问演示页 | — |

### 1.2 Docker Desktop 资源建议

这套栈同时跑 7 个服务，其中 Elasticsearch 是 JVM 应用、Ollama 需要常驻加载 7B 级模型，两者对内存最敏感：

| 资源 | 建议值 | 说明 |
| --- | --- | --- |
| CPU | ≥ 4 核 | 模型推理与向量化都吃 CPU（无 GPU 时） |
| 内存 | ≥ 8 GB 分配给 Docker | 低于此值 ES 容器易被 OOM Kill；首次拉取 `qwen2.5:7b` 时内存峰值更高 |
| Swap | ≥ 2 GB | 防止瞬时峰值直接杀进程 |
| 磁盘 | ≥ 20 GB | 镜像层叠加 + 两份模型 + 索引数据 |

在 Docker Desktop → Settings → Resources 中调整。这是**起步建议**，实际占用随文档量、模型尺寸与并发变化：

> 待运行填入：本机 `docker stats` 下 7 个容器的稳态内存/CPU 占用，以及首次问答与稳态问答的差异。

## ▎2. 目录与文件

```
enterprise-rag/
├── docker-compose.yml        # 7 个服务的编排定义
├── .env                      # 端口、数据库口令、MinIO 凭据等变量
├── backend/                  # Spring Boot 3 应用（Java 21）
├── frontend/                 # Vue 3 + Vite，构建产物由 Nginx 托管
├── demo/                     # JDK 17 原生 HttpServer 演示版（零依赖）
│   ├── src/com/tanke/rag/    # Main / Store / Index / Answer / Text / Json
│   ├── ui/index.html         # 演示页
│   ├── data/docs.tsv         # 演示版持久化文件（首次启动生成）
│   └── smoke.py              # 六条关键路径的冒烟测试
└── docs/                     # 本文档集
```

> 当前仓库已落地 `demo/` 与 `docs/`；`docker-compose.yml` 与 `backend/`、`frontend/` 是生产形态的目标结构，按本文第 3 节的方式部署。想先看链路行为而不装 Docker，直接跳到第 8 节的演示版。
关键变量集中在 `.env`，至少包含：PostgreSQL 的 `rag/rag`（库名 `rag`）、MinIO 的 `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`、各服务端口。**不要把真实凭据提交进仓库**；`.env` 不入库，仓库只提供 `.env.example`。

## ▎3. 一键启动

```bash
# 1) 准备环境变量
cp .env.example .env          # Windows CMD: copy .env.example .env

# 2) 构建并后台启动全部服务
docker compose up -d --build

# 3) 查看状态，等待全部为 running/healthy
docker compose ps

# 4) 跟踪日志（首次启动建议观察，Ctrl+C 只退出日志，不停容器）
docker compose logs -f backend
```

启动顺序由 Compose 的依赖关系决定：`postgres` / `elasticsearch` / `minio` / `redis` / `ollama` 先起，`backend` 等依赖健康后启动，`frontend` 最后。首次构建需要拉取基础镜像并编译后端，耗时取决于网络。

全量服务与端口对照：

| 服务 | 端口 | 访问方式 |
| --- | --- | --- |
| `postgres` | 5432 | `psql -h localhost -U rag -d rag` |
| `elasticsearch` | 9200 | `http://localhost:9200` |
| `minio` | 9000 / 9001 | API `http://localhost:9000`，控制台 `http://localhost:9001` |
| `ollama` | 11434 | `http://localhost:11434` |
| `redis` | 6379 | `redis-cli -h localhost -p 6379` |
| `backend` | 8080 | `http://localhost:8080/api/health` |
| `frontend` | 8081 | `http://localhost:8081` |

## ▎4. 首次初始化

首次启动后有四件事必须确认，否则问答会以各种"看起来像 bug"的方式失败（检索为空、模型 404、上传 500）。

### 4.1 拉取 Ollama 模型

镜像可能已预拉，但换机器/清卷后需要重拉：

```bash
# 生成模型
docker compose exec ollama ollama pull qwen2.5:7b

# 向量模型
docker compose exec ollama ollama pull bge-m3

# 确认两个模型都在
docker compose exec ollama ollama list
```

注意要在**容器内**确认，宿主机装了 Ollama 也不代表容器里有。

### 4.2 确认 IK 分词插件

`elasticsearch` 服务镜像已装 `analysis-ik`。确认方式：

```bash
curl -s "http://localhost:9200/_cat/plugins?v"
```

输出中应出现 `analysis-ik`。再验证分词是否真的生效：

```bash
curl -s -X POST "http://localhost:9200/_analyze" \
  -H 'Content-Type: application/json' \
  -d '{"analyzer":"ik_max_word","text":"差旅报销制度"}'
```

返回的 token 列表里应能看到"差旅""报销""制度"等中文词，而不是被拆成单字或整串。

### 4.3 pgvector 扩展

库名 `rag`，用户 `rag`。扩展与表结构由 **Flyway** 在应用启动时管理，正常情况下无需手工执行。若日志出现 `type "vector" does not exist`，说明扩展未装，需在目标库内创建（注意必须连 `rag` 库，连默认的 `postgres` 库无效）：

```bash
docker compose exec postgres psql -U rag -d rag -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

验证：

```bash
docker compose exec postgres psql -U rag -d rag -c "SELECT extname FROM pg_extension;"
docker compose exec postgres psql -U rag -d rag -c "\d+ doc_chunk"
```

第二条应能看到 `embedding` 列为 `vector` 类型，且 HNSW 索引存在（Flyway 迁移已建）。表名以仓库 Flyway 迁移脚本为准，下文以 `doc_chunk` 为例。

### 4.4 MinIO 桶

桶名 `kb-docs`，存原始文件。用 `mc` 初始化（凭据取自 `.env`，不要硬编码）：

```bash
# 在宿主机安装 mc 后执行；或 docker run --rm 挂载 mc 镜像
mc alias set local http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc mb --ignore-existing local/kb-docs
mc ls local/kb-docs
```

也可以打开 `http://localhost:9001` 用控制台手动建桶，效果相同。桶不存在时上传会直接失败，而不是延后暴露。

## ▎5. 验证清单

按顺序逐项验证，每步都有命令与期望结果。跳过前面的步骤直接测问答，失败原因会很难定位。

| # | 检查项 | 命令 | 期望 |
| --- | --- | --- | --- |
| 1 | 容器全部就绪 | `docker compose ps` | 7 个服务均 `running`，有健康检查的为 `healthy` |
| 2 | PostgreSQL 可连 | `docker compose exec postgres pg_isready -U rag -d rag` | `accepting connections` |
| 3 | pgvector 已启用 | `docker compose exec postgres psql -U rag -d rag -c "SELECT extname FROM pg_extension;"` | 结果含 `vector` |
| 4 | 表结构与索引 | `docker compose exec postgres psql -U rag -d rag -c "\d+ doc_chunk"` | 存在 `embedding vector` 列与 HNSW 索引 |
| 5 | ES 集群健康 | `curl -s http://localhost:9200/_cluster/health?pretty` | `status` 为 `green` 或 `yellow`（单节点 + 副本时为 `yellow`，正常） |
| 6 | ES 插件 | `curl -s "http://localhost:9200/_cat/plugins?v"` | 含 `analysis-ik` |
| 7 | IK 分词生效 | `curl -s -X POST "http://localhost:9200/_analyze" -H 'Content-Type: application/json' -d '{"analyzer":"ik_max_word","text":"差旅报销制度"}'` | 输出中文词元 |
| 8 | Redis | `docker compose exec redis redis-cli ping` | `PONG` |
| 9 | MinIO 桶 | `mc ls local/kb-docs` | 命令成功（空桶也返回 0） |
| 10 | Ollama 在线 | `curl -s http://localhost:11434/api/tags` | 返回模型列表 JSON |
| 11 | 模型已拉 | `docker compose exec ollama ollama list` | 同时含 `qwen2.5:7b` 与 `bge-m3` |
| 12 | 后端健康 | `curl -s http://localhost:8080/api/health`；编排探针用 `curl -s http://localhost:8080/actuator/health` | 业务健康 `status` 为 `ok`（演示版）或 `UP`（生产版）；Spring 探针为 `{"status":"UP"}` |
| 13 | 前端可达 | `curl -s -I http://localhost:8081/` | `200`，且 `/api` 反代可通：`curl -s http://localhost:8081/api/health` |
| 14 | 上传链路 | `curl -s -X POST http://localhost:8080/api/docs -F "file=@./README.md"` | 返回 `docId` |
| 15 | 检索+生成 | `curl -N -s -X POST http://localhost:8080/api/chat -F "q=这份文档讲了什么"` | 首帧 `meta` 带证据，末帧 `done` 带引用 |
| 16 | 拒答行为 | 提问知识库外的问题（如"今天天气如何"） | `done` 帧的 `refused` 为 `true`，`meta` 中 `citations` 为空 |

第 15、16 项是本系统的**功能核心**：能答且带引用、不能答且拒答。两项都通过，才算部署成功。

## ▎6. 常见故障

| 现象 | 可能原因 | 排查命令 | 处理 |
| --- | --- | --- | --- |
| `elasticsearch` 反复重启，日志含 `OutOfMemoryError` 或容器 `Exited (137)` | 堆内存超出 Docker 可用内存（137 = OOM Kill） | `docker compose logs elasticsearch`、`docker inspect --format='{{.State.OOMKilled}}' <容器名>` | 提高 Docker Desktop 内存配额；或在 compose 中显式限制 `ES_JAVA_OPTS`（如 `-Xms1g -Xmx1g`），两者取较小值生效 |
| ES 起来了但中文检索召回为空、只能命中完整原文 | IK 插件未加载（镜像被换、或用了官方纯净镜像） | `curl -s "http://localhost:9200/_cat/plugins?v"` | 换回已装 `analysis-ik` 的镜像并重建索引；直接往索引写数据前先确认分词器，否则要 reindex |
| 后端启动失败，日志含 `type "vector" does not exist` | pgvector 扩展缺失，或 Flyway 迁移跑在了错误的库 | `docker compose exec postgres psql -U rag -d rag -c "SELECT extname FROM pg_extension;"` | 在 `rag` 库执行 `CREATE EXTENSION IF NOT EXISTS vector;`；确认 `spring.datasource.url` 指向 `rag` 而非 `postgres` |
| 问答报 `model ... not found` / 404 | Ollama 容器内没拉模型（宿主机拉过不算） | `docker compose exec ollama ollama list` | `docker compose exec ollama ollama pull qwen2.5:7b`、`... pull bge-m3` |
| `docker compose up` 报端口已被占用 | 5432 / 8080 / 8081 / 9000 / 9200 / 11434 / 6379 与本机已有服务冲突（常见：本机装了 PostgreSQL 或另一份 Ollama） | `netstat -ano \| findstr :5432`（Windows）、`lsof -i :5432`（macOS/Linux） | 停掉冲突进程，或在 `.env` 中改宿主端口映射（容器内端口不变，前端反代目标随之调整） |
| 上传返回 500，日志含 `SignatureDoesNotMatch` / `Access Denied` | MinIO 凭据不一致：`.env` 的 root 用户/口令与 `backend` 中配置的 accessKey 不同，或 `mc alias` 用了旧凭据 | `docker compose exec minio mc admin info local`、核对 `.env` 与 backend 配置 | 统一 `.env` 与 backend 的 MinIO 凭据后 `docker compose up -d` 重建；已建 alias 需重新 `mc alias set` |
| 后端日志 `UnknownHostException: elasticsearch` | 服务不在同一 Docker 网络内，或服务名写错 | `docker compose exec backend getent hosts elasticsearch` | 用 compose 中的**服务名**做主机名（不是 `localhost`），确认 7 个服务在同一个网络 |
| 首次提问等待很久后 504 | 模型冷加载（首次加载 7B 模型到内存）+ 生成超时设置偏紧 | `docker compose logs -f ollama`、`docker compose logs backend` | 先手工预热：`curl -s http://localhost:11434/api/generate -d '{"model":"qwen2.5:7b","prompt":"hi","stream":false}'`，或用更宽松的超时后重试 |

排障通用顺序：`docker compose ps` → `/api/health` 定位组件 → `docker compose logs <服务>` 看首个异常栈 → 按上表对应处理。**先定位再动手**，不要用 `docker compose down -v` 试探——那会删掉数据卷，索引与文档需全部重建。

## ▎7. 停止、清空与重来

```bash
# 停止（保留数据卷）
docker compose stop

# 停止并移除容器（保留数据卷）
docker compose down

# 彻底重来：连数据卷一起删除（会丢 pg 数据、ES 索引、MinIO 文件、Redis 会话）
docker compose down -v
```

`down -v` 之后需要重新执行第 4 节的全部初始化（含拉模型），并且要重新上传文档才能问答。仅在确认数据可弃时使用。

## ▎8. 演示版：不起容器直接跑

仓库内 `demo/` 是 JDK 17 原生 `HttpServer` 的零依赖实现，同样跑通"切片 → 双路召回 → RRF → 证据约束 → SSE → 拒答"这条链路，且接口路径与生产版一致：

```bash
# 需要 JDK 17+，无需 Maven / Gradle / Docker
cd demo

# 编译（产物到 out/）
javac -d out src/com/tanke/rag/*.java

# 启动，默认端口 8765
java -cp out com.tanke.rag.Main

# 换端口：环境变量或首个参数
# RAG_PORT=9000 java -cp out com.tanke.rag.Main      (Windows CMD: set RAG_PORT=9000)
# java -cp out com.tanke.rag.Main 9000
```

启动后终端会打印 `[rag-demo] listening on http://127.0.0.1:8765`，文档数 / 切片数 / 词表大小到 `/api/stats` 查。**工作目录必须是 `demo/`**：数据文件按相对路径 `data/docs.tsv` 读写，演示页按 `ui/index.html` 读取（可用 `-Drag.ui=<路径>` 覆盖）。

| 路径 | 用途 |
| --- | --- |
| `http://127.0.0.1:8765/` | Web 演示页（`ui/index.html`） |
| `GET /api/health` | 健康检查 |
| `GET /api/stats` | 统计（文档数 / 切片数 / 词表 / 平均片长） |
| `POST /api/docs` | form：`title`、`content` |
| `GET /api/docs` | 文档列表（JSON 数组） |
| `POST /api/search` | form：`q`，返回召回与 RRF 排名 |
| `POST /api/ask` | form：`q`，非流式答案 |
| `POST /api/chat` | form：`q`，返回 SSE |

演示版默认端口 **8765**，与生产 `backend` 的 8080 错开，两者可同时运行。它把文档持久化在 `demo/data/docs.tsv`（首次启动会自动灌入 5 篇示例文档），删掉该文件即等于清空知识库。

冒烟验证（六条关键路径，含拒答与 SSE）：

```bash
cd demo
python smoke.py
cat smoke_result.txt        # Windows: type smoke_result.txt；PowerShell 7+ 可用 Get-Content
```

演示版不含权限、限流、多实例去重这些生产能力——那部分见 `production.md` 的改造清单。

## ▎9. 部署后的实测记录位

以下数据必须在本机实际测量后填入，仓库不预置任何推测值。演示版的同类数据可由 `demo/smoke.py` 直接采集（结果落在 `demo/smoke_result.txt`），生产栈的数据需要在此基础上换到真实中间件后重测。

> 待运行填入：`docker compose up -d --build` 从零到全部 healthy 的耗时（区分是否已有镜像缓存）。
>
> 待运行填入：7 个容器的稳态资源占用（`docker compose stats` 的 CPU / MEM 列）。
>
> 待运行填入：单份文档上传到可检索的耗时与切片数。
>
> 待运行填入：一次问答的首帧（`meta`）延迟、首 token 延迟、完整回答耗时。
