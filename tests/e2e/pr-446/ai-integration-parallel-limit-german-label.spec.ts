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

test.afterEach(async ({ page }) => {
  // Restore English so other tests are unaffected
  try {
    await page.goto('/?lang=en');
    await page.waitForLoadState('domcontentloaded');
  } catch {
    // ignore
  }
});

test('Parallel worker limit label is translated in German', async ({ page }) => {
  await openPage(page, '/');
  // Switch to German
  await openPage(page, '/?lang=de');
  // Navigate to the new form (keep lang param as well for robustness)
  await openPage(page, '/ai-integrations/new?lang=de');
  await expect(page).toHaveURL(/\/ai-integrations\/new/);

  const input = page.locator('#parallelWorkerLimit');
  await input.waitFor({ state: 'visible', timeout: 15000 });

  const label = page.locator('label[for="parallelWorkerLimit"]');
  await expect(label).toContainText('Parallele Worker-Obergrenze');
});
