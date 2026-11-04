package com.tanke.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 文档与切片存储：内存索引 + 简单行文本持久化（id \t base64(title) \t base64(content)）。 */
final class Store {
    static final class Doc {
        final int id;
        final String title;
        final String content;
        final long createdAt;
        final int chunks;

        Doc(int id, String title, String content, long createdAt, int chunks) {
            this.id = id;
            this.title = title;
            this.content = content;
            this.createdAt = createdAt;
            this.chunks = chunks;
        }
    }

    private final List<Doc> docs = new ArrayList<>();
    private final Index index = new Index();
    private final Path dataFile;
    private int nextId = 1;

    Store(Path dataFile) {
        this.dataFile = dataFile;
    }

    Index index() { return index; }

    synchronized List<Doc> docs() { return new ArrayList<>(docs); }

    synchronized int docCount() { return docs.size(); }

    synchronized Doc add(String title, String content) throws IOException {
        int id = nextId++;
        List<String> pieces = Text.chunks(content);
        Doc d = new Doc(id, title, content, System.currentTimeMillis(), pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            index.add(new Index.Chunk(id, i, title, pieces.get(i)));
        }
        docs.add(d);
        persist();
        return d;
    }

    synchronized void load() throws IOException {
        if (!Files.exists(dataFile)) return;
        for (String line : Files.readAllLines(dataFile, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) continue;
            String[] p = line.split("\t", -1);
            if (p.length < 3) continue;
            try {
                int id = Integer.parseInt(p[0]);
                String title = new String(Base64.getDecoder().decode(p[1]), StandardCharsets.UTF_8);
                String content = new String(Base64.getDecoder().decode(p[2]), StandardCharsets.UTF_8);
                List<String> pieces = Text.chunks(content);
                for (int i = 0; i < pieces.size(); i++) {
                    index.add(new Index.Chunk(id, i, title, pieces.get(i)));
                }
                docs.add(new Doc(id, title, content, 0L, pieces.size()));
                if (id >= nextId) nextId = id + 1;
            } catch (RuntimeException ignored) {
                // 单行损坏不影响整体加载
            }
        }
    }

    private void persist() throws IOException {
        Path parent = dataFile.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        List<String> lines = new ArrayList<>();
        Base64.Encoder enc = Base64.getEncoder();
        for (Doc d : docs) {
            lines.add(d.id + "\t"
                    + enc.encodeToString(d.title.getBytes(StandardCharsets.UTF_8)) + "\t"
                    + enc.encodeToString(d.content.getBytes(StandardCharsets.UTF_8)));
        }
        Files.write(dataFile, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /** 首次启动灌入示例文档，保证开箱即有可检索语料。 */
    synchronized void seedIfEmpty() throws IOException {
        if (!docs.isEmpty()) return;
        add("项目简介 · 企业知识库 RAG 问答系统",
                "企业知识库 RAG 问答系统面向团队内部文档，支持上传 PDF、DOCX、Markdown、TXT 文档，"
                        + "自动解析切片入库。提问时会同时触发语义召回与关键词召回，融合排序后把证据片段交给模型，"
                        + "强制输出 [n] 引用；当检索不到足够证据时直接拒答，避免编造。"
                        + "前端提供知识库管理页与对话页，后端提供 REST 接口与 SSE 流式输出。");
        add("技术栈与架构 · 七个容器",
                "整体由七个容器组成：Nginx 负责静态资源与反向代理；前端使用 Vue3 与 Vite；"
                        + "后端是 Spring Boot 3 搭配 Spring AI，运行在 Java 21 上；"
                        + "PostgreSQL 16 加 pgvector 存向量，Elasticsearch 8 加 IK 分词负责关键词召回；"
                        + "MinIO 保存原始文件，Redis 保存多轮会话上下文；模型侧由 Ollama 提供 qwen2.5:7b 对话模型与 bge-m3 向量模型。"
                        + "所有服务通过 Docker Compose 一键编排。");
        add("部署与运维 · 一键启动",
                "启动命令是 docker compose up -d，构建完成后后端监听 8080，前端监听 8081。"
                        + "数据库表结构与 HNSW 索引由 Flyway 自动迁移；健康检查接口是 /actuator/health。"
                        + "首次部署需要三个前提：Elasticsearch 安装 analysis-ik 插件、PostgreSQL 创建 pgvector 扩展、"
                        + "Ollama 拉取 qwen2.5:7b 与 bge-m3 两个模型。"
                        + "常见问题：ES 单节点必须设置 discovery.type=single-node，内存不足会导致 ES 反复重启，"
                        + "对象桶 kb-docs 需要提前创建否则上传报错，端口被占用时先释放 5432、9200、11434、8080、8081。");
        add("检索参数与调优 · RRF 融合",
                "切片策略是每片 400 字、相邻重叠 80 字，并保留章节标题作为元数据。"
                        + "语义召回与关键词召回各取前 20 条候选，使用 RRF 融合，常数 k 取 60，融合公式为各路的 1 除以 60 加排名后求和。"
                        + "融合后取前 5 条作为证据集，作答最多引用 5 个片段。"
                        + "提示词明确约束：只依据证据回答、每条结论标注 [n]、证据不足必须拒答。");
        add("二次开发记录 · 我改了什么",
                "在上游开源项目的基础上完成了四类改造：一是重写提示词模板与拒答策略，把「不知道就说不知道」写成硬约束；"
                        + "二是调整检索参数，包括切片重叠、双路 TopK 与证据条数；"
                        + "三是补齐知识库管理页和对话页的前后端联调；"
                        + "四是排查跨域与 Vite 代理问题，把开发环境与容器环境的路由统一到 /api。");
        add("常见问题 FAQ · 检索链路与部署",
                "问：检索链路是怎么设计的？答："
                        + "第一步做查询改写，把口语化的问题补全成检索友好的表述。"
                        + "第二步双路并行召回，语义路走 pgvector 的 HNSW 索引取前 20 条，关键词路走 Elasticsearch 的 IK 分词取前 20 条。"
                        + "第三步用 RRF 融合两路排名，常数 k 取 60，把两路的倒数排名相加得到融合分。"
                        + "第四步取融合后前 5 条作为证据集，交给模型生成带 [n] 引用的答案。"
                        + "问：一共有哪七个容器，各自负责什么？答："
                        + "Nginx 负责静态资源与反向代理，Vue3 前端负责知识库管理与对话页。"
                        + "Spring Boot 3 后端负责解析切片与检索编排，运行在 Java 21 上。"
                        + "PostgreSQL 加 pgvector 存向量，Elasticsearch 加 IK 存倒排索引。"
                        + "MinIO 存原始文件，Redis 存多轮会话上下文，Ollama 提供对话模型与向量模型。"
                        + "问：部署时最容易踩的坑是什么？答："
                        + "第一个坑是 Elasticsearch 单节点必须设置 discovery.type=single-node，否则容器反复重启。"
                        + "第二个坑是 PostgreSQL 必须执行 CREATE EXTENSION vector 才支持向量字段。"
                        + "第三个坑是 MinIO 的对象桶 kb-docs 没有预先创建，上传会直接报错。"
                        + "第四个坑是 Ollama 没有拉取 qwen2.5:7b 与 bge-m3，首次提问会超时。"
                        + "问：切片大小和重叠是多少？答："
                        + "每片 400 字，相邻片段重叠 80 字，并在片段头部保留章节标题作为元数据。"
                        + "这样既保证单个片段的语义完整，又避免答案被切断在边界上。"
                        + "问：RRF 融合常数 k 取多少？答："
                        + "常数 k 取 60，融合分等于两路各自「1 除以（60 加排名）」之和。"
                        + "k 越大越平滑，k 越小越偏向单路的头部结果。"
                        + "问：后端用什么语言和框架？答："
                        + "后端使用 Java 21 与 Spring Boot 3，模型接入层使用 Spring AI。"
                        + "持久层是 MyBatis-Plus，数据库迁移由 Flyway 管理。"
                        + "问：这个系统的开发者是谁？答："
                        + "由谭轲在上游开源项目的基础上完成二次开发，负责检索链路调优、提示词与拒答策略、前后端联调与容器化部署。");
    }
}
