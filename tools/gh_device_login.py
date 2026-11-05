#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GitHub OAuth 设备流登录：拿到一个带 repo 权限的 token，写到临时文件（不打印到终端）。

为什么用它：这台机器上没有任何 GitHub 凭据，而 git push 需要一个 token。
设备流不需要用户手动创建 PAT，只要在浏览器里点一次"授权"。

    python tools/gh_device_login.py

终端会打印两行：
    CODE <ABCD-1234>           ← 要输入到 https://github.com/login/device 的码
    URL  <verification_uri>
授权成功后写文件并打印：
    TOKEN_FILE <path>
    ACCOUNT <login>

注意：授权页面显示的 OAuth 应用名是 GitHub CLI——这里借用的是它的公开 client_id
（设备流必须有 client_id，自建 app 需要先注册）。只申请 repo 一个 scope。
"""
import json
import os
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser

CLIENT_ID = "178c6fc778ccc68e1d6a"
SCOPE = "repo"
DEVICE_URL = "https://github.com/login/device/code"
TOKEN_URL = "https://github.com/login/oauth/access_token"
API = "https://api.github.com"


def post(url, fields):
    data = urllib.parse.urlencode(fields).encode("utf-8")
    req = urllib.request.Request(
        url, data=data, headers={"Accept": "application/json", "User-Agent": "enterprise-rag-push"}
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        return {"error": "http_%s" % e.code, "raw": (e.read() or b"").decode("utf-8", "replace")[:200]}
    except Exception as e:  # noqa: BLE001 - 网络异常统一降级
        return {"error": "network", "raw": repr(e)[:200]}


def main():
    # github.com 的 TLS 在这台机器上偶发断连/超时，开头这段也要重试
    start = {}
    for attempt in range(24):
        start = post(DEVICE_URL, {"client_id": CLIENT_ID, "scope": SCOPE})
        if "user_code" in start:
            break
        print("WAIT 连 github.com 失败（第 %d 次），5 秒后重试：%s"
              % (attempt + 1, str(start.get("raw", ""))[:70]), flush=True)
        time.sleep(5)
    if "user_code" not in start:
        print("FAIL 无法发起设备流：%s" % json.dumps(start, ensure_ascii=False)[:300])
        return 2

    code = start["user_code"]
    uri = start.get("verification_uri", "https://github.com/login/device")
    interval = int(start.get("interval", 5))
    deadline = time.time() + int(start.get("expires_in", 900))

    print("CODE " + code, flush=True)
    print("URL  " + uri, flush=True)
    try:
        webbrowser.open(uri)
    except Exception:  # noqa: BLE001 - 打不开浏览器不影响流程
        pass

    polls = 0
    while time.time() < deadline:
        time.sleep(interval)
        polls += 1
        r = post(
            TOKEN_URL,
            {
                "client_id": CLIENT_ID,
                "device_code": start["device_code"],
                "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
            },
        )
        token = r.get("access_token")
        if token:
            path = os.path.join(tempfile.gettempdir(), "rag_gh_token.txt")
            with open(path, "w", encoding="utf-8") as f:
                f.write(token)
            login = ""
            try:
                req = urllib.request.Request(
                    API + "/user",
                    headers={"Authorization": "Bearer " + token, "User-Agent": "enterprise-rag-push"},
                )
                with urllib.request.urlopen(req, timeout=15) as resp:
                    login = json.loads(resp.read().decode("utf-8")).get("login", "")
            except Exception:  # noqa: BLE001 - 取用户名失败不影响 push
                pass
            print("TOKEN_FILE " + path, flush=True)
            print("ACCOUNT " + login, flush=True)
            print("SCOPE " + str(r.get("scope", "")), flush=True)
            return 0

        err = r.get("error")
        if err == "authorization_pending":
            if polls % 6 == 0:
                print("WAIT 仍在等待授权（已等 %d 秒）" % (polls * interval), flush=True)
            continue
        if err == "network" or err == "http_0":
            # 这台机器到 github.com 的 TLS 偶发断连（RemoteDisconnected），重试而不是退出
            print("WAIT 网络抖动，继续重试：%s" % str(r.get("raw", ""))[:80], flush=True)
            time.sleep(2)
            continue
        if err == "slow_down":
            interval += 5
            print("WAIT 轮询过快，间隔调整为 %d 秒" % interval, flush=True)
            continue
        print("FAIL %s %s" % (err, json.dumps(r, ensure_ascii=False)[:300]), flush=True)
        return 3

    print("FAIL 设备码过期（15 分钟），请重跑本脚本", flush=True)
    return 3


if __name__ == "__main__":
    sys.exit(main())
