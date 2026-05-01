#!/usr/bin/env python3
import argparse
import json
import os
import socket
import subprocess
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse


TASKS = {}
SERVICES = []
MAX_TASKS = 200


def now_ms():
    return int(time.time() * 1000)


def load_services(path):
    with open(path, "r", encoding="utf-8") as handle:
        services = json.load(handle)
    validate_services(services)
    return services


def validate_services(services):
    if not isinstance(services, list):
        raise ValueError("services config must be a list")
    seen = set()
    for service in services:
        service_id = service.get("id")
        if not service_id:
            raise ValueError("service is missing id")
        if service_id in seen:
            raise ValueError(f"duplicate service id: {service_id}")
        seen.add(service_id)
        mode = service.get("checkMode")
        if mode not in ("port", "process", "action"):
            raise ValueError(f"{service_id}: invalid checkMode {mode}")
        if mode == "port" and not service.get("port"):
            raise ValueError(f"{service_id}: port check requires port")
        if mode == "process" and not service.get("processMatch"):
            raise ValueError(f"{service_id}: process check requires processMatch")


def json_response(handler, status, payload):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def find_service(service_id):
    for service in SERVICES:
        if service.get("id") == service_id:
            return service
    return None


def check_port(port):
    started = now_ms()
    try:
        with socket.create_connection(("127.0.0.1", int(port)), timeout=0.75):
            return {
                "state": "running",
                "impact": impact_from_duration(now_ms() - started),
                "checkDurationMs": now_ms() - started,
            }
    except OSError:
        return {
            "state": "stopped",
            "impact": "idle",
            "checkDurationMs": now_ms() - started,
        }


def check_process(match):
    started = now_ms()
    if not match:
        return {"state": "not_configured", "impact": "idle", "checkDurationMs": 0}
    pids = pids_for_match(match)
    duration = now_ms() - started
    metrics = process_metrics(pids)
    return {
        "state": "running" if pids else "stopped",
        "impact": impact_from_metrics(metrics) if pids else "idle",
        "checkDurationMs": duration,
        "pids": pids,
        **metrics,
    }


def impact_from_duration(duration_ms):
    if duration_ms < 120:
        return "low"
    if duration_ms < 500:
        return "medium"
    return "high"


def service_status(service):
    mode = service.get("checkMode")
    if mode == "port":
        result = check_port(service.get("port"))
    elif mode == "process":
        result = check_process(service.get("processMatch"))
    else:
        result = {"state": "unknown", "impact": "idle", "checkDurationMs": 0}

    process_result = {}
    if service.get("processMatch") and mode != "process":
        process_result = check_process(service.get("processMatch"))

    return {
        "id": service.get("id"),
        "label": service.get("label", service.get("id")),
        "state": result["state"],
        "impact": max_impact(result.get("impact"), process_result.get("impact")),
        "checkDurationMs": result["checkDurationMs"],
        "port": service.get("port"),
        "pids": process_result.get("pids", result.get("pids", [])),
        "pidCount": process_result.get("pidCount", result.get("pidCount", 0)),
        "memoryKb": process_result.get("memoryKb", result.get("memoryKb", 0)),
        "uptimeSeconds": process_result.get("uptimeSeconds", result.get("uptimeSeconds")),
        "checkedAtMillis": now_ms(),
    }


def pids_for_match(match):
    proc = subprocess.run(
        ["pgrep", "-f", match],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        return []
    return [int(line) for line in proc.stdout.splitlines() if line.strip().isdigit()]


def process_metrics(pids):
    memory_kb = 0
    uptimes = []
    for pid in pids:
        memory_kb += rss_kb(pid)
        uptime = uptime_seconds(pid)
        if uptime is not None:
            uptimes.append(uptime)
    return {
        "pidCount": len(pids),
        "memoryKb": memory_kb,
        "uptimeSeconds": int(max(uptimes)) if uptimes else None,
    }


def rss_kb(pid):
    status_path = Path(f"/proc/{pid}/status")
    try:
        for line in status_path.read_text(encoding="utf-8", errors="ignore").splitlines():
            if line.startswith("VmRSS:"):
                parts = line.split()
                return int(parts[1]) if len(parts) > 1 else 0
    except OSError:
        return 0
    return 0


def uptime_seconds(pid):
    try:
        stat = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8", errors="ignore")
        start_ticks = int(stat.split()[21])
        clock_ticks = os.sysconf(os.sysconf_names["SC_CLK_TCK"])
        system_uptime = float(Path("/proc/uptime").read_text().split()[0])
        return max(0, int(system_uptime - (start_ticks / clock_ticks)))
    except (OSError, IndexError, ValueError, KeyError):
        return None


def impact_from_metrics(metrics):
    memory_kb = metrics.get("memoryKb") or 0
    pid_count = metrics.get("pidCount") or 0
    if memory_kb > 250_000 or pid_count > 12:
        return "high"
    if memory_kb > 80_000 or pid_count > 4:
        return "medium"
    return "low"


def max_impact(*values):
    order = {"idle": 0, "low": 1, "medium": 2, "high": 3, "error": 4}
    clean = [value for value in values if value]
    if not clean:
        return "idle"
    return max(clean, key=lambda value: order.get(value, 0))


def start_service(service):
    command = service.get("startCommand")
    if command:
        return run_shell_command(service, "start", command)

    script = service.get("scriptPath")
    if not script:
        return make_task(service, "start", "failed", "Missing scriptPath")

    path = Path(script).expanduser()
    if not path.exists():
        return make_task(service, "start", "failed", f"Missing script: {path}")

    try:
        subprocess.Popen(
            [str(path)] if os.access(path, os.X_OK) else ["bash", str(path)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        return make_task(service, "start", "accepted", "")
    except OSError as exc:
        return make_task(service, "start", "failed", str(exc))


def stop_service(service):
    command = service.get("stopCommand")
    if not command:
        return make_task(service, "stop", "failed", "Missing stopCommand")
    return run_shell_command(service, "stop", command)


def run_shell_command(service, action, command):
    try:
        subprocess.Popen(
            ["bash", "-lc", command],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        return make_task(service, action, "accepted", "")
    except OSError as exc:
        return make_task(service, action, "failed", str(exc))


def make_task(service, action, state, message):
    prune_tasks()
    task_id = str(uuid.uuid4())
    task = {
        "taskId": task_id,
        "serviceId": service.get("id"),
        "action": action,
        "state": state,
        "message": message,
        "createdAtMillis": now_ms(),
    }
    TASKS[task_id] = task
    return task


def prune_tasks():
    if len(TASKS) < MAX_TASKS:
        return
    oldest = sorted(TASKS.values(), key=lambda task: task.get("createdAtMillis", 0))
    for task in oldest[: len(TASKS) - MAX_TASKS + 1]:
        TASKS.pop(task["taskId"], None)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        return

    def do_GET(self):
        parts = [part for part in urlparse(self.path).path.split("/") if part]

        if parts == ["health"]:
            json_response(self, 200, {"ok": True, "serviceCount": len(SERVICES)})
            return

        if parts == ["services"]:
            json_response(self, 200, {"services": SERVICES})
            return

        if parts == ["services", "status"]:
            json_response(self, 200, {"services": [service_status(s) for s in SERVICES]})
            return

        if len(parts) == 3 and parts[0] == "services" and parts[2] == "status":
            service = find_service(parts[1])
            if not service:
                json_response(self, 404, {"error": "service_not_found"})
                return
            json_response(self, 200, service_status(service))
            return

        if len(parts) == 2 and parts[0] == "tasks":
            task = TASKS.get(parts[1])
            if not task:
                json_response(self, 404, {"error": "task_not_found"})
                return
            json_response(self, 200, task)
            return

        json_response(self, 404, {"error": "not_found"})

    def do_POST(self):
        parts = [part for part in urlparse(self.path).path.split("/") if part]
        if len(parts) == 3 and parts[0] == "services":
            service = find_service(parts[1])
            if not service:
                json_response(self, 404, {"error": "service_not_found"})
                return
            if parts[2] == "start":
                json_response(self, 202, start_service(service))
                return
            if parts[2] == "stop":
                json_response(self, 202, stop_service(service))
                return
        json_response(self, 404, {"error": "not_found"})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5317)
    parser.add_argument("--config", default="services.json")
    args = parser.parse_args()

    global SERVICES
    SERVICES = load_services(args.config)

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"Service Control Agent listening on http://{args.host}:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
