package com.tanke.rag;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 零依赖演示服务：JDK 内置 HttpServer，接口路径与生产版保持一致。 */
public final class Main {
    private static final long START = System.currentTimeMillis();
    private static Store store;

    public static void main(String[] args) throws Exception {
        int port = 8765;
        String envPort = System.getenv("RAG_PORT");
        if (envPort != null && !envPort.isEmpty()) port = Integer.parseInt(envPort);
        if (args.length > 0) port = Integer.parseInt(args[0]);

        store = new Store(Paths.get("data", "docs.tsv"));
        store.load();
        store.seedIfEmpty();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 128);
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.max(8, Runtime.getRuntime().availableProcessors() * 2));
        server.setExecutor(pool);
        server.createContext("/", Main::route);
        server.start();

        System.out.println("[rag-demo] listening on http://127.0.0.1:" + port);
        System.out.println("[rag-demo] docs=" + store.docCount()
                + " chunks=" + store.index().chunkCount()
                + " vocab=" + store.index().vocabSize());
    }

    private static void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        try {
            if (path.equals("/") || path.equals("/index.html")) {
                sendHtml(ex);
            } else if (path.equals("/api/health")) {
                sendJson(ex, 200, "{" + Json.kv("status", "ok") + ","
                        + Json.kvNum("uptimeMs", System.currentTimeMillis() - START) + ","
                        + Json.kv("version", "demo-1.0") + "}");
            } else if (path.equals("/api/stats")) {
                sendJson(ex, 200, statsJson());
            } else if (path.equals("/api/docs")) {
                if ("POST".equalsIgnoreCase(method)) handleAddDoc(ex);
                else sendJson(ex, 200, docsJson());
            } else if (path.equals("/api/search")) {
                handleSearch(ex);
            } else if (path.equals("/api/ask")) {
                handleAsk(ex);
            } else if (path.equals("/api/chat")) {
                handleChat(ex);
            } else {
                sendJson(ex, 404, "{" + Json.kv("error", "not found") + "}");
            }
        } catch (Exception e) {
            try {
                sendJson(ex, 500, "{" + Json.kv("error", String.valueOf(e.getMessage())) + "}");
            } catch (Exception ignore) {
                // 响应已提交，忽略
            }
        }
    }

    private static String statsJson() {
        Index idx = store.index();
        return "{" + Json.kv("docs", String.valueOf(store.docCount())) + ","
                + Json.kv("chunks", String.valueOf(idx.chunkCount())) + ","
                + Json.kv("vocab", String.valueOf(idx.vocabSize())) + ","
                + Json.kvNum("avgChunkLen", idx.avgChunkLen()) + ","
                + Json.kv("embedding", "hash-projection-" + Index.DIM + "d") + ","
                + Json.kv("retriever", "bm25+vector+rrf(k=" + Index.RRF_K + ")") + ","
                + Json.kvNum("uptimeMs", System.currentTimeMillis() - START) + "}";
    }

    private static String docsJson() {
        StringBuilder sb = new StringBuilder("[");
        List<Store.Doc> docs = store.docs();
        for (int i = 0; i < docs.size(); i++) {
            Store.Doc d = docs.get(i);
            if (i > 0) sb.append(',');
            sb.append("{").append(Json.kv("id", String.valueOf(d.id))).append(",")
                    .append(Json.kv("title", d.title)).append(",")
                    .append(Json.kv("chunks", String.valueOf(d.chunks))).append(",")
                    .append(Json.kv("chars", String.valueOf(d.content.length()))).append("}");
        }
        return sb.append(']').toString();
    }

    private static void handleAddDoc(HttpExchange ex) throws IOException {
        Map<String, String> form = readForm(ex);
        String title = form.getOrDefault("title", "").trim();
        String content = form.getOrDefault("content", "").trim();
        if (title.isEmpty() || content.isEmpty()) {
            sendJson(ex, 400, "{" + Json.kv("error", "title 与 content 均不能为空") + "}");
            return;
        }
        Store.Doc d = store.add(title, content);
        sendJson(ex, 200, "{" + Json.kv("id", String.valueOf(d.id)) + ","
                + Json.kv("title", d.title) + ","
                + Json.kv("chunks", String.valueOf(d.chunks)) + "}");
    }

    private static void handleSearch(HttpExchange ex) throws IOException {
        Map<String, String> form = readForm(ex);
        String q = form.getOrDefault("q", "").trim();
        long t0 = System.nanoTime();
        List<Index.Hit> hits = store.index().search(q, 5);
        long us = (System.nanoTime() - t0) / 1000;
        StringBuilder sb = new StringBuilder("{");
        sb.append(Json.kvNum("tookUs", us)).append(",").append(Json.str("hits")).append(":[");
        for (int i = 0; i < hits.size(); i++) {
            Index.Hit h = hits.get(i);
            if (i > 0) sb.append(',');
            sb.append("{").append(Json.kv("docId", String.valueOf(h.chunk.docId))).append(",")
                    .append(Json.kv("seq", String.valueOf(h.chunk.seq + 1))).append(",")
                    .append(Json.kv("title", h.chunk.title)).append(",")
                    .append(Json.kv("text", Text.snippet(h.chunk.text, 160))).append(",")
                    .append(Json.kvNum("fused", h.fused)).append(",")
                    .append(Json.kvNum("bm25", h.bm25)).append(",")
                    .append(Json.kvNum("cosine", h.cosine)).append(",")
                    .append(Json.kv("bm25Rank", String.valueOf(h.bm25Rank))).append(",")
                    .append(Json.kv("vecRank", String.valueOf(h.vecRank))).append(",")
                    .append(Json.kvNum("coverage", h.coverage)).append("}");
        }
        sb.append("]}");
        sendJson(ex, 200, sb.toString());
    }

    private static void handleAsk(HttpExchange ex) throws IOException {
        Map<String, String> form = readForm(ex);
        String q = form.getOrDefault("q", "").trim();
        long t0 = System.nanoTime();
        List<Index.Hit> hits = store.index().search(q, Index.RECALL_K);
        List<Index.Hit> top = hits.size() > 5 ? hits.subList(0, 5) : hits;
        Answer.Result r = Answer.build(q, top, store.index());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        StringBuilder sb = new StringBuilder("{");
        sb.append(Json.kv("answer", r.text)).append(",")
                .append(Json.kvBool("refused", r.refused)).append(",")
                .append(Json.kvNum("tookMs", ms)).append(",")
                .append(Json.kv("reason", r.reason)).append(",")
                .append(Json.str("citations")).append(":[");
        for (int i = 0; i < r.citations.size(); i++) {
            Answer.Citation c = r.citations.get(i);
            if (i > 0) sb.append(',');
            sb.append("{").append(Json.kv("index", String.valueOf(c.index))).append(",")
                    .append(Json.kv("title", c.title)).append(",")
                    .append(Json.kv("snippet", c.snippet)).append(",")
                    .append(Json.kvNum("score", c.score)).append("}");
        }
        sb.append("]}");
        sendJson(ex, 200, sb.toString());
    }

    private static void handleChat(HttpExchange ex) throws IOException {
        Map<String, String> form = readForm(ex);
        String q = form.getOrDefault("q", "").trim();

        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, 0);

        long t0 = System.nanoTime();
        List<Index.Hit> hits = store.index().search(q, Index.RECALL_K);
        List<Index.Hit> top = hits.size() > 5 ? hits.subList(0, 5) : hits;
        Answer.Result r = Answer.build(q, top, store.index());

        try (OutputStream os = ex.getResponseBody()) {
            StringBuilder meta = new StringBuilder();
            meta.append("{").append(Json.kvBool("refused", r.refused)).append(",")
                    .append(Json.kv("reason", r.reason)).append(",")
                    .append(Json.str("citations")).append(":[");
            for (int i = 0; i < r.citations.size(); i++) {
                Answer.Citation c = r.citations.get(i);
                if (i > 0) meta.append(',');
                meta.append("{").append(Json.kv("index", String.valueOf(c.index))).append(",")
                        .append(Json.kv("docId", String.valueOf(c.docId))).append(",")
                        .append(Json.kv("seq", String.valueOf(c.seq + 1))).append(",")
                        .append(Json.kv("title", c.title)).append(",")
                        .append(Json.kv("snippet", c.snippet)).append(",")
                        .append(Json.kvNum("score", c.score)).append("}");
            }
            meta.append("]}");
            sse(os, "meta", meta.toString());
            os.flush();

            String text = r.text;
            int step = 8;
            for (int i = 0; i < text.length(); i += step) {
                String piece = text.substring(i, Math.min(text.length(), i + step));
                sse(os, "delta", "{" + Json.kv("t", piece) + "}");
                os.flush();
                try {
                    Thread.sleep(15);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            sse(os, "done", "{" + Json.kvNum("tookMs", ms) + ","
                    + Json.kvBool("refused", r.refused) + "}");
            os.flush();
        }
    }

    private static void sse(OutputStream os, String event, String data) throws IOException {
        os.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> readForm(HttpExchange ex) throws IOException {
        Map<String, String> m = new HashMap<>();
        byte[] body = ex.getRequestBody().readAllBytes();
        String s = new String(body, StandardCharsets.UTF_8).trim();
        if (s.isEmpty()) return m;
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendHtml(HttpExchange ex) throws IOException {
        Path p = Paths.get(System.getProperty("rag.ui", "ui/index.html"));
        byte[] bytes;
        if (Files.exists(p)) {
            bytes = Files.readAllBytes(p);
        } else {
            bytes = ("<h1>RAG demo</h1><p>找不到 " + p.toAbsolutePath() + "</p>")
                    .getBytes(StandardCharsets.UTF_8);
        }
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
