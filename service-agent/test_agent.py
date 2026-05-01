import tempfile
import unittest
from pathlib import Path

import agent


class AgentTest(unittest.TestCase):
    def test_validate_rejects_duplicate_ids(self):
        services = [
            {"id": "one", "checkMode": "action"},
            {"id": "one", "checkMode": "action"},
        ]
        with self.assertRaises(ValueError):
            agent.validate_services(services)

    def test_validate_rejects_port_without_port(self):
        services = [{"id": "web", "checkMode": "port"}]
        with self.assertRaises(ValueError):
            agent.validate_services(services)

    def test_impact_from_metrics(self):
        self.assertEqual(agent.impact_from_metrics({"memoryKb": 10, "pidCount": 1}), "low")
        self.assertEqual(agent.impact_from_metrics({"memoryKb": 90_000, "pidCount": 1}), "medium")
        self.assertEqual(agent.impact_from_metrics({"memoryKb": 260_000, "pidCount": 1}), "high")

    def test_make_task_prunes_old_tasks(self):
        agent.TASKS.clear()
        old_max = agent.MAX_TASKS
        agent.MAX_TASKS = 3
        try:
            service = {"id": "svc"}
            for _ in range(5):
                agent.make_task(service, "start", "accepted", "")
            self.assertLessEqual(len(agent.TASKS), 3)
        finally:
            agent.MAX_TASKS = old_max
            agent.TASKS.clear()

    def test_load_services_validates_shape(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / "services.json"
            config.write_text('[{"id":"svc","checkMode":"action"}]', encoding="utf-8")
            services = agent.load_services(config)
            self.assertEqual(services[0]["id"], "svc")


if __name__ == "__main__":
    unittest.main()
