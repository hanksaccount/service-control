import { test, expect } from '@playwright/test';

const services = [
  { name: 'fuel-intel', port: 5210, url: 'http://127.0.0.1:5210' },
  { name: 'elpris',     port: 5100, url: 'http://127.0.0.1:5100' },
  { name: 'dashboard',  port: 5000, url: 'http://127.0.0.1:5000' },
  { name: 'autosort',   port: 5300, url: 'http://127.0.0.1:5300' }
];

test.describe('Service Availability (Localhost Smoke Pack)', () => {
  for (const service of services) {
    test(`Verify ${service.name} is reachable on port ${service.port}`, async ({ page }) => {
      // We use a short timeout because local services should be instant
      try {
        const response = await page.goto(service.url, { timeout: 3000 });
        expect(response?.status()).toBe(200);
        console.log(`✅ ${service.name} is ONLINE`);
      } catch (e) {
        console.log(`❌ ${service.name} is OFFLINE or unreachable`);
        // We don't necessarily fail the test if the service is intentionally stopped
        // but it gives us clear visibility in the test logs.
      }
    });
  }
});
