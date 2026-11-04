# -*- coding: utf-8 -*-
"""RAG demo 冒烟测试：健康检查 / 统计 / 检索 / 问答 / 拒答 / SSE 流式，结果写 UTF-8 文件。"""
import json
import time
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8765"
OUT = "smoke_result.txt"
lines = []


def log(*a):
    lines.append(" ".join(str(x) for x in a))


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=15) as r:
        return r.status, r.read().decode("utf-8")


def post_form(path, data):
    body = urllib.parse.urlencode(data).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=body,
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=30) as r:
        raw = r.read().decode("utf-8")
    return raw, (time.perf_counter() - t0) * 1000


# 1. health / stats
st, body = get("/api/health")
log("[health]", st, body)

st, body = get("/api/stats")
log("[stats]", st, body)

st, body = get("/api/docs")
docs = json.loads(body)
log("[docs] count=%d" % len(docs))
for d in docs:
    log("   -", d["id"], d["title"], d["chunks"], "chunks", d["chars"], "chars")

# 2. search
body, ms = post_form("/api/search", {"q": "检索链路怎么设计的"})
hits = json.loads(body)
log("[search] tookUs=%s wall=%.1fms hits=%d" % (hits.get("tookUs"), ms, len(hits["hits"])))
for h in hits["hits"][:3]:
    log("   rank bm25=%s vec=%s fused=%.4f bm25=%.3f cos=%.3f cov=%.3f | %s"
        % (h["bm25Rank"], h["vecRank"], h["fused"], h["bm25"], h["cosine"],
           h["coverage"], h["title"]))

# 3. ask (should answer with citations)
body, ms = post_form("/api/ask", {"q": "检索链路是怎么设计的？"})
a = json.loads(body)
log("[ask] wall=%.1fms refused=%s cites=%d" % (ms, a["refused"], len(a["citations"])))
log("   answer:", a["answer"][:220])
for c in a["citations"]:
    log("   cite[%s]" % c["index"], c["title"], "score=%.4f" % c["score"])

# 4. refusal path
body, ms = post_form("/api/ask", {"q": "如何训练一个多模态大模型？"})
r = json.loads(body)
log("[refuse] wall=%.1fms refused=%s reason=%s" % (ms, r["refused"], r["reason"]))
log("   answer:", r["answer"][:160])

# 5. SSE chat
t0 = time.perf_counter()
body = urllib.parse.urlencode({"q": "七个容器分别负责什么？"}).encode("utf-8")
req = urllib.request.Request(BASE + "/api/chat", data=body,
                             headers={"Content-Type": "application/x-www-form-urlencoded"})
events = {"meta": 0, "delta": 0, "done": 0}
first_byte_ms = None
text = []
done_ms = None
with urllib.request.urlopen(req, timeout=30) as resp:
    buf = b""
    while True:
        chunk = resp.read1(4096) if hasattr(resp, "read1") else resp.read(1)
        if not chunk:
            break
        if first_byte_ms is None:
            first_byte_ms = (time.perf_counter() - t0) * 1000
        buf += chunk
        while b"\n\n" in buf:
            frame, buf = buf.split(b"\n\n", 1)
            ev = "message"
            data = ""
            for line in frame.decode("utf-8").split("\n"):
                if line.startswith("event:"):
                    ev = line[6:].strip()
                elif line.startswith("data:"):
                    data += line[5:].strip()
            if ev in events:
                events[ev] += 1
            if ev == "delta" and data:
                text.append(json.loads(data)["t"])
            if ev == "done" and data:
                done_ms = (time.perf_counter() - t0) * 1000
                log("[sse] done frame:", data)
log("[sse] events=%s firstByte=%.1fms total=%.1fms" % (events, first_byte_ms or -1, done_ms or -1))
log("[sse] answer:", "".join(text)[:200])

# 6. 检索质量矩阵：Top1 是否命中正确文档 + 覆盖率 + 是否拒答
def refused_of(payload):
    v = payload.get("refused")
    if isinstance(v, bool):
        return v
    return str(v).strip().lower() in ("true", "1")


MATRIX = [
    ("检索链路是怎么设计的？", "常见问题 FAQ", True),
    ("一共有哪七个容器，各自负责什么？", "常见问题 FAQ", True),
    ("部署时最容易踩的坑是什么？", "常见问题 FAQ", True),
    ("切片大小和重叠是多少？", "常见问题 FAQ", True),
    ("如何训练一个多模态大模型？", None, False),
    ("今天北京的天气怎么样？", None, False),
]
log("[matrix] 问题 -> Top1 文档 | 覆盖率 | 是否作答")
ok = 0
for q, expect, should_answer in MATRIX:
    body, ms = post_form("/api/search", {"q": q})
    hs = json.loads(body)["hits"]
    top = hs[0] if hs else None
    cov = top["coverage"] if top else 0.0
    title = top["title"] if top else "-"
    body2, _ = post_form("/api/ask", {"q": q})
    ans = json.loads(body2)
    answered = not refused_of(ans)
    hit_ok = (expect is None and not answered) or \
             (expect is not None and answered and expect in title)
    ok += 1 if hit_ok else 0
    log("   %-22s -> %-18s cov=%.3f answered=%-5s %s"
        % (q, title[:18], cov, answered, "OK" if hit_ok else "FAIL"))
log("[matrix] pass %d/%d" % (ok, len(MATRIX)))

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
print("OK ->", OUT)
