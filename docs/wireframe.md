# Wireframe

```text
Widget
  -> ServiceManager.togglePower(serviceId)
  -> mark STARTING/STOPPING immediately
  -> ServiceExecutor.start/stop
  -> StatusChecker verifies port/process
  -> render RUNNING/STOPPED/FAILED
```

## Impact View

The app shows practical impact instead of a weak byte counter:

- active services
- pending services
- risky/failed services
- check latency
- pid count
- memory estimate from agent
- uptime from agent

Exact CPU/network attribution should live in the agent, not in the widget.
