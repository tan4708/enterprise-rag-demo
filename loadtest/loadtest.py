# -*- coding: utf-8 -*-
"""并发压测：对 /api/search 与 /api/ask 逐级加压，统计吞吐与 P50/P95/P99。

用法：
    python loadtest.py                 # 默认 1/4/8/16 并发 × 每级 200 次
    python loadtest.py 500             # 每级 500 次
结果同时写到 loadtest_result.txt 与 loadtest_result.json（本机实测，可复现）。
"""
import json
import statistics
import sys
import threading
import time
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8765"
LEVELS = [1, 4, 8, 16]
QUERIES = [
    "检索链路是怎么设计的？",
    "一共有哪七个容器，各自负责什么？",
    "部署时最容易踩的坑是什么？",
    "切片大小和重叠是多少？",
    "RRF 融合常数 k 取多少？",
]


def call(path, q):
    body = urllib.parse.urlencode({"q": q}).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=body,
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=30) as r:
        r.read()
    return (time.perf_counter() - t0) * 1000


def pct(vals, p):
    if not vals:
        return 0.0
    s = sorted(vals)
    k = max(0, min(len(s) - 1, int(round((p / 100.0) * (len(s) - 1)))))
    return s[k]


def run_level(path, concurrency, total):
    per_worker = max(1, total // concurrency)
    lat = []
    lock = threading.Lock()
    err = []

    def worker(wid):
        local = []
        for i in range(per_worker):
            try:
                local.append(call(path, QUERIES[(wid + i) % len(QUERIES)]))
            except Exception as e:  # noqa: BLE001
                with lock:
                    err.append(str(e))
        with lock:
            lat.extend(local)

    t0 = time.perf_counter()
    threads = [threading.Thread(target=worker, args=(w,)) for w in range(concurrency)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wall = (time.perf_counter() - t0)
    n = len(lat)
    return {
        "path": path,
        "concurrency": concurrency,
        "requests": n,
        "errors": len(err),
        "wall_s": round(wall, 3),
        "rps": round(n / wall, 1) if wall > 0 else 0,
        "p50_ms": round(pct(lat, 50), 2),
        "p95_ms": round(pct(lat, 95), 2),
        "p99_ms": round(pct(lat, 99), 2),
        "max_ms": round(max(lat), 2) if lat else 0,
        "mean_ms": round(statistics.mean(lat), 2) if lat else 0,
    }


def main():
    total = int(sys.argv[1]) if len(sys.argv) > 1 else 200
    rows = []
    for path in ("/api/search", "/api/ask"):
        for c in LEVELS:
            rows.append(run_level(path, c, total))
            print("%-12s c=%-2d rps=%-7.1f p50=%-6.1f p95=%-6.1f p99=%-6.1f err=%d"
                  % (path, c, rows[-1]["rps"], rows[-1]["p50_ms"],
                     rows[-1]["p95_ms"], rows[-1]["p99_ms"], rows[-1]["errors"]))

    lines = ["# 本机压测结果（demo 演示版，零依赖实现的检索 + 抽取式作答）", ""]
    lines.append("| 接口 | 并发 | 请求数 | RPS | P50 (ms) | P95 (ms) | P99 (ms) | 平均 (ms) | 最大 (ms) | 错误 |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for r in rows:
        lines.append("| %s | %d | %d | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %d |"
                     % (r["path"], r["concurrency"], r["requests"], r["rps"],
                        r["p50_ms"], r["p95_ms"], r["p99_ms"], r["mean_ms"], r["max_ms"],
                        r["errors"]))
    lines.append("")
    lines.append("> 说明：以上为单机本地环回、演示版的实测数据（python 多线程客户端，含客户端开销）；"
                 "生产栈的检索走 Elasticsearch + pgvector、作答走大模型，延迟由网络与模型推理主导，量级完全不同。")
    with open("loadtest_result.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    with open("loadtest_result.json", "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=2)
    print("OK -> loadtest_result.txt / loadtest_result.json")


if __name__ == "__main__":
    main()
