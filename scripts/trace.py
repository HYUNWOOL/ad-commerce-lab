"""Jaeger에서 트레이스 하나를 서비스·시간 트리로 찍는다(ADR-0005).

사용:
  python scripts/trace.py            # 가장 최근의 POST /api/orders 트레이스
  python scripts/trace.py <traceId>  # 지정한 트레이스
"""
import datetime as dt
import json
import sys
import urllib.parse
import urllib.request

JAEGER = "http://localhost:16686/api/v3"


def get(path: str) -> dict:
    with urllib.request.urlopen(JAEGER + path, timeout=5) as r:
        return json.load(r)


def spans_of(result: dict) -> list[dict]:
    out = []
    for rs in result["resourceSpans"]:
        service = next(a["value"]["stringValue"] for a in rs["resource"]["attributes"] if a["key"] == "service.name")
        for ss in rs["scopeSpans"]:
            for s in ss["spans"]:
                out.append({**s, "service": service})
    return out


def latest_order_trace() -> str:
    now = dt.datetime.now(dt.timezone.utc)
    q = urllib.parse.urlencode({
        "query.service_name": "gateway",
        "query.operation_name": "http post /api/orders/**",
        "query.start_time_min": (now - dt.timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "query.start_time_max": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "query.num_traces": 20,
    })
    spans = spans_of(get("/traces?" + q)["result"])
    return max(spans, key=lambda s: int(s["startTimeUnixNano"]))["traceId"]


def main() -> None:
    trace_id = sys.argv[1] if len(sys.argv) > 1 else latest_order_trace()
    spans = spans_of(get(f"/traces/{trace_id}")["result"])
    children: dict[str, list[dict]] = {}
    for s in spans:
        children.setdefault(s.get("parentSpanId", ""), []).append(s)
    ids = {s["spanId"] for s in spans}
    roots = [s for s in spans if s.get("parentSpanId", "") not in ids]
    t0 = min(int(s["startTimeUnixNano"]) for s in spans)
    services = sorted({s["service"] for s in spans})
    print(f"trace {trace_id}: 스팬 {len(spans)}개, 서비스 {len(services)}개 {services}")

    def walk(s: dict, depth: int) -> None:
        start = (int(s["startTimeUnixNano"]) - t0) / 1e6
        took = (int(s["endTimeUnixNano"]) - int(s["startTimeUnixNano"])) / 1e6
        print(f"{start:9.1f}ms {took:8.1f}ms  {'  ' * depth}{s['service']}: {s['name']}")
        for c in sorted(children.get(s["spanId"], []), key=lambda x: int(x["startTimeUnixNano"])):
            walk(c, depth + 1)

    for r in sorted(roots, key=lambda x: int(x["startTimeUnixNano"])):
        walk(r, 0)


if __name__ == "__main__":
    main()
