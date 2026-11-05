#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""仓库自举：用 GitHub API 校验 token、取用户名、创建仓库（已存在则复用）。

由 tools/push.ps1 调用，也可以单独用：

    set GH_PAT=<token>            # Windows
    export GH_PAT=<token>         # bash
    python tools/gh_bootstrap.py enterprise-rag-demo

环境变量：
    GH_PAT      必填，GitHub Personal Access Token
    GH_OWNER    可选，仓库归属（默认取 token 对应用户）
    GH_PRIVATE  "1" 建私有仓库，默认公开

输出（供脚本解析）：
    USER <login>
    CREATED <html_url> | EXISTS <html_url>
    CLONE <clone_url>
    META ok <n>/2 ...          # About 描述与 topics 的配置结果，失败也只是 warn

退出码：0 成功；2 token 无效；3 建仓库失败。
只用标准库，不依赖 requests——PowerShell 的 HTTPS 在部分 Windows 机器上会
报 SEC_E_NO_CREDENTIALS，python 的 urllib 反而稳定，所以这段放 python 里。
"""
import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"

# 仓库元信息（About 里看到的那几行）——建仓库时设一次，已存在也会被纠正
DESCRIPTION = (
    "企业知识库 RAG 问答系统（JDK 17 零依赖可运行演示）：BM25 + 向量双路召回、"
    "RRF 融合、证据约束作答、证据不足即拒答、SSE 流式输出；附冒烟与压测脚本"
)
HOMEPAGE = ""  # 没有线上部署，留空
TOPICS = [
    "rag",
    "retrieval-augmented-generation",
    "knowledge-base",
    "hybrid-search",
    "bm25",
    "rrf",
    "vector-search",
    "sse",
    "java",
    "zero-dependencies",
]


def _headers(token):
    return {
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "User-Agent": "enterprise-rag-bootstrap",
    }


def call(token, url, data=None, method=None):
    body = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=body, headers=_headers(token), method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        payload = (e.read() or b"").decode("utf-8", errors="replace")
        return e.code, {"raw": payload}
    except urllib.error.URLError as e:
        return 0, {"raw": str(e.reason)}


def ensure_meta(token, owner, repo):
    """把描述 / homepage / topics 对齐到本文件里的常量。失败只警告，不影响推送主流程。"""
    ok = 0
    status, body = call(
        token,
        "%s/repos/%s/%s" % (API, owner, repo),
        {"description": DESCRIPTION, "homepage": HOMEPAGE},
        method="PATCH",
    )
    if status == 200:
        ok += 1
    else:
        print("META warn 描述设置失败 %s %s" % (status, body.get("raw", "")[:160]))

    status, body = call(
        token,
        "%s/repos/%s/%s/topics" % (API, owner, repo),
        {"names": TOPICS},
        method="PUT",
    )
    if status == 200:
        ok += 1
    else:
        print("META warn topics 设置失败 %s %s（需 repo 或 Administration 写权限）"
              % (status, body.get("raw", "")[:160]))

    print("META ok %d/2 topics=%s" % (ok, ",".join(TOPICS) if ok == 2 else "-"))


def main(argv):
    token = os.environ.get("GH_PAT", "").strip()
    if not token:
        print("ERR_ARG 没有 GH_PAT 环境变量")
        return 1
    repo = (argv[1] if len(argv) > 1 else "").strip() or "enterprise-rag-demo"
    owner = os.environ.get("GH_OWNER", "").strip()
    private = os.environ.get("GH_PRIVATE", "0") == "1"

    status, me = call(token, API + "/user")
    if status != 200:
        print("ERR_AUTH %s %s" % (status, me.get("raw", "")[:200]))
        return 2
    login = me.get("login", "")
    print("USER " + login)
    owner = owner or login

    status, body = call(token, "%s/repos/%s/%s" % (API, owner, repo))
    if status == 200:
        print("EXISTS " + body.get("html_url", ""))
        print("CLONE " + body.get("clone_url", ""))
        ensure_meta(token, owner, repo)
        return 0

    status, created = call(
        token,
        API + "/user/repos",
        {
            "name": repo,
            "private": private,
            "has_issues": True,
            "has_wiki": False,
            "has_projects": False,
            "description": DESCRIPTION,
        },
    )
    if status in (200, 201):
        print("CREATED " + created.get("html_url", ""))
        print("CLONE " + created.get("clone_url", ""))
        ensure_meta(token, owner, repo)
        return 0
    print("ERR_CREATE %s %s" % (status, created.get("raw", "")[:300]))
    return 3


if __name__ == "__main__":
    sys.exit(main(sys.argv))
