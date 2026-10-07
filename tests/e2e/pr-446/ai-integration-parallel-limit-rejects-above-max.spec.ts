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

/** Fill every required field (except the parallel worker limit) with dummy values. */
async function fillRequiredFields(page: Page, name: string) {
  const form = page.locator('form').filter({ has: page.locator('#parallelWorkerLimit') }).first();
  const nameInput = form.locator('#name, input[name="name"]').first();
  if (await nameInput.count()) await nameInput.fill(name);

  const apiKey = form.locator('#apiKey, input[name="apiKey"]').first();
  if ((await apiKey.count()) && (await apiKey.isVisible())) await apiKey.fill('dummy-api-key');

  const fields = form.locator('input[required], select[required], textarea[required]');
  const count = await fields.count();
  for (let i = 0; i < count; i++) {
    const field = fields.nth(i);
    const id = (await field.getAttribute('id')) ?? '';
    if (id === 'parallelWorkerLimit') continue;
    if (!(await field.isVisible())) continue;
    const tag = await field.evaluate((el) => el.tagName.toLowerCase());
    if (tag === 'select') {
      const current = await field.inputValue();
      if (!current) {
        const values = await field.locator('option').evaluateAll((opts) =>
          (opts as HTMLOptionElement[]).map((o) => o.value).filter((v) => v !== '')
        );
        if (values.length) await field.selectOption(values[0]);
        await page.waitForTimeout(300);
      }
      continue;
    }
    const current = await field.inputValue();
    if (current) continue;
    const type = ((await field.getAttribute('type')) ?? 'text').toLowerCase();
    const lowered = `${id} ${(await field.getAttribute('name')) ?? ''}`.toLowerCase();
    if (type === 'number') await field.fill((await field.getAttribute('min')) ?? '1');
    else if (type === 'url' || lowered.includes('url')) await field.fill('https://api.example.com/v1');
    else if (lowered.includes('model')) await field.fill('dummy-model');
    else if (lowered.includes('name')) await field.fill(name);
    else await field.fill('dummy-value');
  }
}

test('A Parallel worker limit above 20 blocks form submission', async ({ page }) => {
  const name = `e2e-pwl-max-${Date.now()}`;
  await openPage(page, '/');
  await openPage(page, '/ai-integrations/new');
  await expect(page).toHaveURL(/\/ai-integrations\/new/);

  const input = page.locator('#parallelWorkerLimit');
  await input.waitFor({ state: 'visible', timeout: 15000 });

  await fillRequiredFields(page, name);
  await input.fill('21');

  const form = page.locator('form').filter({ has: input }).first();
  await form.locator('button[type="submit"], input[type="submit"]').last().click();
  await page.waitForTimeout(1000);

  await expect(page).toHaveURL(/\/ai-integrations\/new/);
  expect(await input.evaluate((el: HTMLInputElement) => el.validity.rangeOverflow)).toBe(true);
  // No state was persisted, as the form was not submitted.
});
