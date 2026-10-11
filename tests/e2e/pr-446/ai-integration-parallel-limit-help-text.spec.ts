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

test('Parallel worker limit shows its help text', async ({ page }) => {
  await openPage(page, '/');
  await openPage(page, '/ai-integrations/new');
  await expect(page).toHaveURL(/\/ai-integrations\/new/);

  const input = page.locator('#parallelWorkerLimit');
  await input.waitFor({ state: 'visible', timeout: 15000 });

  // The first .form-text element following the input in document order
  const helpText = input.locator('xpath=following::*[contains(concat(" ", normalize-space(@class), " "), " form-text ")][1]');
  await expect(helpText).toBeVisible();
  await expect(helpText).toContainText('Maximum number of jobs that may run at once for this AI integration');
});
