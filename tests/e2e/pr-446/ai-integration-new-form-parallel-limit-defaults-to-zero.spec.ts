import { test, expect, Page } from '@playwright/test';

async function dismissOverlay(page: Page) {
  for (let i = 0; i < 3; i++) {
    const closer = page
      .locator(
        '.introjs-skipbutton, .shepherd-cancel-icon, .driver-popover-close-btn, .tour-close, [data-role="end"], ' +
          '.popover button.btn-close, .tooltip-overlay button, [aria-label="Close"]:visible'
      )
      .or(page.getByRole('button', { name: /^(close|skip|skip tour|got it|done|finish|end tour|end|schließen|überspringen|beenden)$/i }))
      .first();
    try {
      await closer.waitFor({ state: 'visible', timeout: 2000 });
      await closer.click({ timeout: 2000 });
      await page.waitForTimeout(300);
    } catch {
      break;
    }
  }
  await page.keyboard.press('Escape').catch(() => {});
}

async function openPage(page: Page, path: string) {
  await page.goto(path);
  await page.waitForLoadState('domcontentloaded');
  await dismissOverlay(page);
}

test('New AI integration form shows the Parallel worker limit field with default 0', async ({ page }) => {
  // Step: login disabled by default - open preview directly
  await openPage(page, '/');
  // Step: navigate to /ai-integrations/new
  await openPage(page, '/ai-integrations/new');
  await expect(page).toHaveURL(/\/ai-integrations\/new/);

  // Step: locate input by label
  const input = page.locator('#parallelWorkerLimit');
  await input.waitFor({ state: 'visible', timeout: 15000 });
  await expect(page.locator('label[for="parallelWorkerLimit"]')).toContainText('Parallel worker limit');
  await expect(page.getByLabel('Parallel worker limit')).toBeVisible();

  // Assertion
  await expect(input).toBeVisible();
  await expect(input).toHaveValue('0');
});
