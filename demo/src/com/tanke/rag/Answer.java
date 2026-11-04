package com.tanke.rag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 证据约束作答：只用检索到的片段组织答案，逐句标注 [n] 引用。
 * 两道闸：①问题里的词知识库完全没提过 → 拒答；②Top1 片段的 BM25 证据强度低于阈值 → 拒答。
 * 命中 FAQ 结构（问：…答：…）时，优先复用与提问最匹配的那组问答。
 */
final class Answer {
    static final int USE_CHUNKS = 3;
    static final int MAX_CITATIONS = 5;
    /** 拒答阈值：Top1 片段 BM25 证据强度下限（由 probe_scores 实测标定） */
    static final double MIN_BM25 = 1.8;
    /** FAQ 里存的“问”与用户提问的相似度下限，达到即复用该组答案 */
    static final double FAQ_QUESTION_HIT = 0.25;

    static final class Citation {
        final int index;
        final int docId;
        final int seq;
        final String title;
        final String snippet;
        final double score;

        Citation(int index, int docId, int seq, String title, String snippet, double score) {
            this.index = index;
            this.docId = docId;
            this.seq = seq;
            this.title = title;
            this.snippet = snippet;
            this.score = score;
        }
    }

    static final class Result {
        final boolean refused;
        final String text;
        final List<Citation> citations;
        final String reason;

        Result(boolean refused, String text, List<Citation> citations, String reason) {
            this.refused = refused;
            this.text = text;
            this.citations = citations;
            this.reason = reason;
        }
    }

    private static final class Cand {
        final String text;
        final Index.Hit hit;
        final double score;
        /** 0 = 命中 FAQ 问法（优先），1 = 普通段落句子 */
        final int tier;

        Cand(String text, Index.Hit hit, double score, int tier) {
            this.text = text;
            this.hit = hit;
            this.score = score;
            this.tier = tier;
        }
    }

    private Answer() {}

    private static Result refuse(String reason) {
        return new Result(true,
                "当前知识库中没有检索到足够支撑该问题的证据，已拒答（避免无依据生成）。"
                        + "可以换个问法，或先在左侧上传相关文档。",
                new ArrayList<Citation>(), reason);
    }

    /** 词的 IDF 加权重合度：分子是句子覆盖的查询信息量，分母做句长归一。 */
    private static double overlap(List<String> sentTokens, Set<String> qTokens, Index index) {
        Set<String> distinct = new HashSet<>();
        for (String t : sentTokens) {
            if (qTokens.contains(t)) distinct.add(t);
        }
        if (distinct.isEmpty()) return 0;
        double idfSum = 0;
        for (String t : distinct) idfSum += index.idfOf(t);
        return idfSum / Math.sqrt(Math.max(1, sentTokens.size()));
    }

    static Result build(String q, List<Index.Hit> hits, Index index) {
        if (hits.isEmpty()) return refuse("证据不足：无召回结果");
        List<String> qList = new ArrayList<>(new LinkedHashSet<>(Text.tokens(q)));
        boolean anyInVocab = false;
        for (String t : qList) {
            if (index.dfOf(t) > 0) {
                anyInVocab = true;
                break;
            }
        }
        if (!anyInVocab) return refuse("证据不足：提问中的词在知识库中均未出现");

        double top1Bm25 = hits.get(0).bm25;
        if (top1Bm25 < MIN_BM25) {
            return refuse("证据不足：Top1 证据强度 " + Index.fmt(top1Bm25) + " < " + Index.fmt(MIN_BM25));
        }

        Set<String> qSet = new HashSet<>(qList);
        List<Cand> pool = new ArrayList<>();
        int rank = 0;
        for (Index.Hit h : hits) {
            rank++;
            if (rank > 5) break;
            double rankFactor = 1.0 / (1.0 + (rank - 1) * 0.12);
            List<String[]> blocks = Text.faqBlocks(h.chunk.text);
            double bestQSim = 0;
            for (String[] b : blocks) {
                if (b[0] == null) continue;
                bestQSim = Math.max(bestQSim, overlap(Text.tokens(b[0]), qSet, index));
            }
            boolean faqStrong = bestQSim >= FAQ_QUESTION_HIT;
            for (String[] block : blocks) {
                String question = block[0];
                String answer = block[1];
                if (faqStrong) {
                    // 命中 FAQ：只复用与提问最匹配的那组问答，按原文顺序整组给出
                    double qSim = question == null ? 0 : overlap(Text.tokens(question), qSet, index);
                    if (qSim < bestQSim * 0.7) continue;
                    int idx = 0;
                    for (String s : Text.sentences(answer)) {
                        if (s.length() < 6) continue;
                        pool.add(new Cand(s, h, 1000 - idx - rank, 0));
                        idx++;
                    }
                    continue;
                }
                // 没命中 FAQ 结构：普通段落按 IDF 加权重合度取句，问答块降权
                double boost = question == null ? 1.0 : 0.6;
                for (String s : Text.sentences(answer)) {
                    if (s.length() < 8) continue;
                    double sc = overlap(Text.tokens(s), qSet, index) * rankFactor * boost;
                    if (sc <= 0) continue;
                    pool.add(new Cand(s, h, sc, 1));
                }
            }
        }
        pool.sort((a, b) -> {
            if (a.tier != b.tier) return Integer.compare(a.tier, b.tier);
            return Double.compare(b.score, a.score);
        });

        Set<String> seen = new HashSet<>();
        List<Citation> cites = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        for (Cand c : pool) {
            if (cites.size() >= USE_CHUNKS) break;
            if (!seen.add(c.text)) continue;
            int n = cites.size() + 1;
            cites.add(new Citation(n, c.hit.chunk.docId, c.hit.chunk.seq, c.hit.chunk.title,
                    Text.snippet(c.text, 140), c.hit.fused));
            if (body.length() > 0) body.append(' ');
            body.append(c.text).append(" [").append(n).append(']');
        }
        if (cites.isEmpty()) return refuse("证据不足：片段与提问无有效重合");
        return new Result(false, "根据知识库：" + body, cites, "");
    }
}
