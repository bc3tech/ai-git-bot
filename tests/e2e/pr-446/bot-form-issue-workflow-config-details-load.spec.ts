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

test('Bot form loads issue workflow configuration details from the issue endpoint', async ({ page }) => {
  await openPage(page, '/');
  // Only opens the creation form; nothing is saved, so no cleanup is needed.
  await openPage(page, '/bots/new');
  await expect(page).toHaveURL(/\/bots\/new/);

  const select = page.locator('#issueWorkflowConfigurationId');
  await select.waitFor({ state: 'attached', timeout: 15000 });

  // Wait until options are populated (they might be loaded asynchronously)
  await expect
    .poll(
      async () =>
        (await select.locator('option').evaluateAll((opts) =>
          (opts as HTMLOptionElement[]).filter((o) => o.value !== '').length
        )),
      { timeout: 15000, message: 'Expected at least one non-empty issue workflow configuration option' }
    )
    .toBeGreaterThan(0);

  const value = await select.locator('option').evaluateAll(
    (opts) => (opts as HTMLOptionElement[]).find((o) => o.value !== '')!.value
  );
  await select.scrollIntoViewIfNeeded().catch(() => {});
  await select.selectOption(value, { force: true });
  await page.waitForTimeout(300);

  const btn = page.locator('#issueWorkflowConfigurationDetailsBtn');
  await expect(btn).toBeVisible({ timeout: 10000 });
  await expect(btn).toBeEnabled({ timeout: 10000 });

  const responsePromise = page.waitForResponse(
    (r) => /\/system-settings\/issue-workflow-configurations\/[^/]+\/selected-workflows/.test(r.url()),
    { timeout: 15000 }
  );
  await btn.click();
  const response = await responsePromise;

  expect(response.url()).toContain('/system-settings/issue-workflow-configurations/');
  expect(response.status()).toBe(200);

  const modal = page.locator('.modal.show');
  await expect(modal).toBeVisible({ timeout: 10000 });
  await page.waitForTimeout(500);
  await expect(modal).not.toContainText(/fail|error|fehlgeschlagen|fehler/i);

  // Close the modal
  await page.keyboard.press('Escape');
});
