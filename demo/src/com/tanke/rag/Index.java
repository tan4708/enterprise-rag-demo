package com.tanke.rag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 检索索引：BM25 关键词召回 + 哈希向量语义召回，再用 RRF 融合。
 * 对应生产栈里的 Elasticsearch(IK) + PostgreSQL(pgvector) 双路召回。
 * 另外给出「IDF 加权覆盖率」，用于判定证据是否充足（不足则拒答）。
 */
final class Index {
    /** 向量维度（哈希投影） */
    static final int DIM = 512;
    static final double K1 = 1.2;
    static final double B = 0.75;
    /** RRF 平滑常数 */
    static final int RRF_K = 60;
    /** 单路召回条数 */
    static final int RECALL_K = 20;

    static final class Chunk {
        final int docId;
        final int seq;
        final String title;
        final String text;
        final Map<String, Integer> tf;
        final int len;
        final double[] vec;

        Chunk(int docId, int seq, String title, String text) {
            this.docId = docId;
            this.seq = seq;
            this.title = title;
            this.text = text;
            // 标题参与检索（标题命中的片段更可能是答案所在）
            String indexed = title + "。" + text;
            List<String> tk = Text.tokens(indexed);
            Map<String, Integer> m = new HashMap<>();
            for (String t : tk) m.merge(t, 1, Integer::sum);
            this.tf = m;
            this.len = tk.size();
            this.vec = embed(indexed);
        }

        String label() { return title + " · 片段 " + (seq + 1); }
    }

    static final class Hit {
        final Chunk chunk;
        final double fused;
        final int bm25Rank;
        final int vecRank;
        final double bm25;
        final double cosine;
        /** 查询词 IDF 加权覆盖率：该片段覆盖了多少查询信息量 */
        final double coverage;

        Hit(Chunk chunk, double fused, int bm25Rank, int vecRank,
            double bm25, double cosine, double coverage) {
            this.chunk = chunk;
            this.fused = fused;
            this.bm25Rank = bm25Rank;
            this.vecRank = vecRank;
            this.bm25 = bm25;
            this.cosine = cosine;
            this.coverage = coverage;
        }
    }

    private final List<Chunk> chunks = new ArrayList<>();
    private final Map<String, List<Integer>> postings = new HashMap<>();

    synchronized void add(Chunk c) {
        int id = chunks.size();
        chunks.add(c);
        for (String t : c.tf.keySet()) postings.computeIfAbsent(t, k -> new ArrayList<>()).add(id);
    }

    synchronized int chunkCount() { return chunks.size(); }

    synchronized int vocabSize() { return postings.size(); }

    synchronized double avgChunkLen() {
        if (chunks.isEmpty()) return 0;
        long sum = 0;
        for (Chunk c : chunks) sum += c.len;
        return (double) sum / chunks.size();
    }

    /** 带符号哈希投影：把 token 映射到 DIM 维单位向量，用作轻量语义相似度。 */
    static double[] embed(String text) {
        double[] v = new double[DIM];
        for (String t : Text.tokens(text)) {
            int h = t.hashCode();
            int idx = Math.floorMod(h, DIM);
            double sign = (((h >>> 16) & 1) == 0) ? 1.0 : -1.0;
            v[idx] += sign;
        }
        double norm = 0;
        for (double x : v) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < DIM; i++) v[i] /= norm;
        return v;
    }

    private double idf(String t, int n, int df) {
        return Math.log(1 + (n - df + 0.5) / (df + 0.5));
    }

    /** 单个词的 IDF（未出现过的词给最大权重）。 */
    synchronized double idfOf(String t) {
        int n = Math.max(1, chunks.size());
        List<Integer> pl = postings.get(t);
        return idf(t, n, pl == null ? 0 : pl.size());
    }

    /** 词在多少片段中出现过（0 表示知识库完全没提过）。 */
    synchronized int dfOf(String t) {
        List<Integer> pl = postings.get(t);
        return pl == null ? 0 : pl.size();
    }

    private double[] bm25(List<String> qTokens) {
        int n = chunks.size();
        double[] sc = new double[n];
        if (n == 0) return sc;
        double sum = 0;
        for (Chunk c : chunks) sum += c.len;
        double avg = Math.max(1.0, sum / n);
        for (String t : qTokens) {
            List<Integer> pl = postings.get(t);
            if (pl == null) continue;
            double w = idf(t, n, pl.size());
            for (int id : pl) {
                Chunk c = chunks.get(id);
                int tf = c.tf.getOrDefault(t, 0);
                sc[id] += w * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * c.len / avg));
            }
        }
        return sc;
    }

    private double[] cosines(String q) {
        int n = chunks.size();
        double[] sc = new double[n];
        double[] qv = embed(q);
        for (int i = 0; i < n; i++) {
            double[] v = chunks.get(i).vec;
            double dot = 0;
            for (int d = 0; d < DIM; d++) dot += v[d] * qv[d];
            sc[i] = dot;
        }
        return sc;
    }

    private static int[] ranks(double[] score) {
        Integer[] idx = new Integer[score.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(score[b], score[a]));
        int[] r = new int[score.length];
        int rank = 0;
        for (Integer i : idx) {
            if (score[i] <= 0) break;
            r[i] = ++rank;
        }
        return r;
    }

    /** IDF 加权覆盖率：查询里有多少「信息量」被这个片段覆盖。 */
    private double coverage(Chunk c, List<String> qTokens, Map<String, Double> idfOf, double total) {
        if (total <= 0) return 0;
        double covered = 0;
        for (String t : qTokens) {
            if (c.tf.containsKey(t)) covered += idfOf.getOrDefault(t, 0.0);
        }
        return covered / total;
    }

    synchronized List<Hit> search(String q, int topk) {
        List<Hit> out = new ArrayList<>();
        if (chunks.isEmpty() || q == null || q.trim().isEmpty()) return out;
        int n = chunks.size();
        Set<String> qSet = new LinkedHashSet<>(Text.tokens(q));
        if (qSet.isEmpty()) return out;
        List<String> qTokens = new ArrayList<>(qSet);

        Map<String, Double> idfOf = new HashMap<>();
        double total = 0;
        for (String t : qTokens) {
            List<Integer> pl = postings.get(t);
            int df = pl == null ? 0 : pl.size();
            double w = df == 0 ? idf(t, n, 0) : idf(t, n, df);
            idfOf.put(t, w);
            total += w;
        }

        double[] bm = bm25(qTokens);
        double[] cs = cosines(q);
        int[] br = ranks(bm);
        int[] vr = ranks(cs);
        double[] fused = new double[n];
        for (int i = 0; i < n; i++) {
            if (br[i] > 0) fused[i] += 1.0 / (RRF_K + br[i]);
            if (vr[i] > 0) fused[i] += 1.0 / (RRF_K + vr[i]);
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(fused[b], fused[a]));
        for (int i = 0; i < n && out.size() < topk; i++) {
            int id = order[i];
            if (br[id] == 0 && vr[id] == 0) break;
            Chunk c = chunks.get(id);
            out.add(new Hit(c, fused[id], br[id], vr[id], bm[id], cs[id],
                    coverage(c, qTokens, idfOf, total)));
        }
        return out;
    }

    static String fmt(double d) { return String.format(Locale.ROOT, "%.3f", d); }
}
