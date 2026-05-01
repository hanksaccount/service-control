# Local Service Agent

The local agent decouples service execution from the Android UI/widget. It
exposes a small HTTP API for start/stop/status calls and reports practical
impact signals for each service.

## Run

```sh
python3 service-agent/agent.py --host 127.0.0.1 --port 5317 --config service-agent/services.json
```

## Test

```sh
python3 -m unittest discover -s service-agent -p "test_*.py"
```

## Status Payload

```json
{
  "id": "autosort",
  "label": "AutoSort",
  "state": "running",
  "impact": "low",
  "checkDurationMs": 2,
  "port": 5300,
  "pids": [1234],
  "pidCount": 1,
  "memoryKb": 3368,
  "uptimeSeconds": 42
}
```

Use these fields for the app/widget impact view instead of weak byte counters.
