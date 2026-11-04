package com.tanke.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 中文友好分词（汉字二元组 + 英文/数字整词）与文本切片。 */
final class Text {
    /** 切片长度（字） */
    static final int CHUNK = 400;
    /** 相邻切片重叠（字） */
    static final int OVERLAP = 80;

    private Text() {}

    static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    /** 汉字相邻二元组 + 拉丁词，足够支撑无词典场景下的召回与 BM25。 */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        StringBuilder latin = new StringBuilder();
        char prev = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isCjk(c)) {
                if (latin.length() > 0) {
                    out.add(latin.toString().toLowerCase(Locale.ROOT));
                    latin.setLength(0);
                }
                if (prev != 0) out.add(new String(new char[]{prev, c}));
                prev = c;
            } else {
                prev = 0;
                if (Character.isLetterOrDigit(c)) {
                    latin.append(c);
                } else if (latin.length() > 0) {
                    out.add(latin.toString().toLowerCase(Locale.ROOT));
                    latin.setLength(0);
                }
            }
        }
        if (latin.length() > 0) out.add(latin.toString().toLowerCase(Locale.ROOT));
        return out;
    }

    static List<String> sentences(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n' || c == '!' || c == '?' || c == ';') {
                String t = cur.toString().trim();
                if (!t.isEmpty()) out.add(t);
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        String t = cur.toString().trim();
        if (!t.isEmpty()) out.add(t);
        return out;
    }

    static List<String> chunks(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String s = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (s.isEmpty()) return out;
        int i = 0;
        while (i < s.length()) {
            int end = Math.min(s.length(), i + CHUNK);
            out.add(s.substring(i, end).trim());
            if (end >= s.length()) break;
            i = end - OVERLAP;
        }
        return out;
    }

    static String snippet(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /**
     * 把片段拆成问答块：返回 {问题 或 null, 答案正文}。
     * 命中「问：…答：…」结构时，答案可以按对应问题的相似度加权复用。
     */
    static List<String[]> faqBlocks(String s) {
        List<String[]> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        String[] parts = s.split("问：|Q:");
        for (String part : parts) {
            if (part == null) continue;
            String p = part.trim();
            if (p.isEmpty()) continue;
            int i = p.indexOf("答：");
            if (i < 0) i = p.indexOf("A:");
            if (i < 0) {
                out.add(new String[]{null, p});
            } else {
                String question = p.substring(0, i).trim();
                String answer = p.substring(i + 2).trim();
                out.add(new String[]{question.isEmpty() ? null : question, answer});
            }
        }
        return out;
    }
}
