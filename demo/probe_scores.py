# -*- coding: utf-8 -*-
"""阈值标定探针：对一批问题打印 Top3 的 bm25 / cosine / coverage / fused。"""
import json
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8765"
OUT = "probe_scores.txt"

QS = [
    "检索链路是怎么设计的？",
    "一共有哪七个容器，各自负责什么？",
    "部署时最容易踩的坑是什么？",
    "切片大小和重叠是多少？",
    "RRF 融合常数 k 取多少？",
    "后端用什么语言和框架？",
    "MinIO 是干什么用的？",
    "如何训练一个多模态大模型？",
    "今天北京的天气怎么样？",
    "帮我写一首关于春天的诗",
    "这个系统的开发者是谁？",
    "ES 内存不足会怎样？",
]


def search(q):
    body = urllib.parse.urlencode({"q": q}).encode("utf-8")
    req = urllib.request.Request(BASE + "/api/search", data=body,
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode("utf-8"))["hits"]


lines = []
for q in QS:
    hs = search(q)
    lines.append("Q: " + q)
    for i, h in enumerate(hs[:3]):
        lines.append("   #%d bm25=%7.3f cos=%.3f cov=%.3f fused=%.5f | %s"
                     % (i + 1, h["bm25"], h["cosine"], h["coverage"], h["fused"], h["title"]))
    lines.append("")

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
print("OK ->", OUT)
