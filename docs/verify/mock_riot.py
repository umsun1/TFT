"""검증용 가짜 Riot API. 들어온 요청의 도착 시각을 기록해 1초/2분 구간 최대 호출 수를 계산한다."""
import json
import ssl
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

lock = threading.Lock()
arrivals = []           # (time, client_ip, path)
force_429 = {"count": 0, "retry_after": None}
latency = {"sec": 0.0}
PAGES = {"n": 3}        # 소환사 1명의 매치 목록 페이지 수(페이지당 100개)


def max_in_window(times, window):
    best, j = 0, 0
    for i, t in enumerate(times):
        while times[j] <= t - window:
            j += 1
        best = max(best, i - j + 1)
    return best


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def send_json(self, code, body, headers=None):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = self.path.split("?")[0]
        query = self.path.split("?")[1] if "?" in self.path else ""

        # ── 제어용 (기록하지 않음)
        if path == "/__stats":
            with lock:
                rows = list(arrivals)
            times = [r[0] for r in rows]
            by_ip = {}
            for _, ip, _ in rows:
                by_ip[ip] = by_ip.get(ip, 0) + 1
            per_ip_120 = {ip: max_in_window([r[0] for r in rows if r[1] == ip], 120.0) for ip in by_ip}
            return self.send_json(200, {
                "total": len(rows),
                "byClient": by_ip,
                "max1s": max_in_window(times, 1.0),
                "max120s": max_in_window(times, 120.0),
                "max120sByClient": per_ip_120,
                "spanSec": round(times[-1] - times[0], 1) if times else 0,
                "lastArrivalAgoSec": round(time.time() - times[-1], 1) if times else None,
            })
        if path == "/__reset":
            with lock:
                arrivals.clear()
            force_429["count"] = 0
            return self.send_json(200, {"ok": True})
        if path == "/__429":            # /__429?count=1&retry=20  (retry 생략 시 헤더 없음)
            params = dict(p.split("=") for p in query.split("&") if "=" in p)
            force_429["count"] = int(params.get("count", 1))
            force_429["retry_after"] = params.get("retry")
            return self.send_json(200, {"ok": True})
        if path == "/__config":         # /__config?latencyMs=150&pages=12
            params = dict(p.split("=") for p in query.split("&") if "=" in p)
            if "latencyMs" in params:
                latency["sec"] = int(params["latencyMs"]) / 1000.0
            if "pages" in params:
                PAGES["n"] = int(params["pages"])
            return self.send_json(200, {"latencySec": latency["sec"], "pages": PAGES["n"]})

        # ── Riot API 흉내
        with lock:
            arrivals.append((time.time(), self.client_address[0], path))
        if latency["sec"]:
            time.sleep(latency["sec"])

        if force_429["count"] > 0:
            force_429["count"] -= 1
            headers = {"X-Rate-Limit-Type": "application"}
            if force_429["retry_after"] is not None:
                headers["Retry-After"] = force_429["retry_after"]
            return self.send_json(429, {"status": {"status_code": 429}}, headers)

        if path.startswith("/riot/account/v1/accounts/by-riot-id/"):
            name, tag = path.split("/")[-2:]
            return self.send_json(200, {"puuid": "mock-" + name, "gameName": name, "tagLine": tag})
        if path.startswith("/tft/league/v1/by-puuid/"):
            return self.send_json(200, [{"queueType": "RANKED_TFT", "tier": "GOLD", "rank": "II",
                                         "leaguePoints": 50, "wins": 10, "losses": 10}])
        if path.startswith("/tft/summoner/v1/summoners/"):
            return self.send_json(200, {"puuid": path.split("/")[-1], "profileIconId": 1, "summonerLevel": 30})
        if path.startswith("/tft/match/v1/matches/by-puuid/"):
            puuid = path.split("/")[-2]
            params = dict(p.split("=") for p in query.split("&") if "=" in p)
            start = int(params.get("start", 0))
            page = start // 100
            if page >= PAGES["n"]:
                return self.send_json(200, [])
            size = 100 if page < PAGES["n"] - 1 else 50
            return self.send_json(200, ["MOCK_%s_%d" % (puuid, start + i) for i in range(size)])
        if path.startswith("/tft/match/v1/matches/"):
            return self.send_json(404, {"status": {"status_code": 404}})
        if path.startswith("/tft/league/v1/entries/"):
            return self.send_json(200, [])
        if path.startswith("/tft/league/v1/"):
            return self.send_json(200, {"entries": []})
        return self.send_json(404, {"status": {"status_code": 404}})


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", 443), Handler)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain("/certs/mock.crt", "/certs/mock.key")
    server.socket = ctx.wrap_socket(server.socket, server_side=True)
    print("mock riot listening on 443", flush=True)
    server.serve_forever()
