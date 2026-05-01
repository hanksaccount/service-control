# Service Control

Android shell for local service control plus a small local agent.

## Shape

```text
Android App + Glance Widget
  -> ServiceManager
  -> ServiceExecutor
  -> Termux bridge or Local Agent
  -> Shell scripts / services
```

The widget is intentionally thin. It sends a service id and lets
`ServiceManager` decide the current state. This avoids stale widget state after
start/stop taps.

## Local Agent

```sh
python3 service-agent/agent.py --host 127.0.0.1 --port 5317 --config service-agent/services.json
```

Run tests:

```sh
python3 -m unittest discover -s service-agent -p "test_*.py"
```

## CI

GitHub Actions runs agent unit tests and an Android build using:

- Android Gradle Plugin 9.0.1
- Gradle 9.1.0
- compile/target SDK 36
- Kotlin 2.3.20
- Compose BOM 2026.03.00
- Glance 1.1.1

## Android Backend Switch

Termux bridge remains the default compatibility mode. To use the local agent,
set app preferences:

- `use_local_agent=true`
- `agent_base_url=http://127.0.0.1:5317`

Once enabled, both actions and status checks use the agent.
