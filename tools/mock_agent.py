"""
실험용 '내 에이전트' 웹훅 서버 (표준 라이브러리만 사용).

허브가 POST /brief 로 허용된 차량 컨텍스트(JSON)를 보내면,
한 줄에 한 문장씩 text/plain 으로 답한다.

실행:  python3 tools/mock_agent.py            (기본 포트 8080)
에뮬레이터에서는 http://10.0.2.2:8080/brief 로 접근한다.

실험 E2에서 확인할 것:
- 서버 로그에 찍힌 요청 본문에 허용한 항목(remain_minutes)만 들어오는가
- --delay 옵션으로 응답을 늦췄을 때 허브가 4초 뒤 실패 문장을 읽는가
"""
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

DELAY_SECONDS = 0.0


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/brief":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8")
        print(f"[요청] {raw}", flush=True)
        try:
            ctx = json.loads(raw)
        except json.JSONDecodeError:
            ctx = {}

        if DELAY_SECONDS:
            time.sleep(DELAY_SECONDS)

        remain = ctx.get("remain_minutes")
        lines = ["내 에이전트 브리핑입니다."]
        if remain is not None:
            lines.append(f"도착까지 약 {remain}분 남았습니다.")
        lines.append("오늘 마감인 작업은 기획서 제출 하나입니다.")
        lines.append("어제 요청한 자료 검토는 아직 답이 없습니다.")
        if "destination" in ctx:
            lines.append("주의: 허용하지 않은 목적지 정보가 전달되었습니다.")

        body = "\n".join(lines).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--delay", type=float, default=0.0, help="응답 지연(초). 타임아웃 실험용")
    args = parser.parse_args()
    DELAY_SECONDS = args.delay
    print(f"mock agent: http://0.0.0.0:{args.port}/brief  delay={args.delay}s", flush=True)
    HTTPServer(("0.0.0.0", args.port), Handler).serve_forever()
