package com.tanke.rag;

/** 极简 JSON 输出工具：只负责安全转义与拼装，避免引入第三方依赖。 */
final class Json {
    private Json() {}

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    static String str(String s) { return "\"" + esc(s) + "\""; }

    static String num(double d) { return String.format(java.util.Locale.ROOT, "%.4f", d); }

    static String kv(String k, String v) { return str(k) + ":" + str(v); }

    static String kvNum(String k, double v) { return str(k) + ":" + num(v); }

    static String kvBool(String k, boolean v) { return str(k) + ":" + (v ? "true" : "false"); }

    static String kvInt(String k, long v) { return str(k) + ":" + v; }
}
