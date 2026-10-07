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

async function submitForm(page: Page) {
  const form = page.locator('form').filter({ has: page.locator('#parallelWorkerLimit') }).first();
  await form.locator('button[type="submit"], input[type="submit"]').last().click();
  await page.waitForURL((url) => /\/ai-integrations\/?$/.test(url.pathname), { timeout: 15000 });
  await page.waitForLoadState('domcontentloaded');
  await dismissOverlay(page);
}

function rowFor(page: Page, name: string) {
  return page.locator('tr, .list-group-item, .card').filter({ hasText: name }).last();
}

async function openEdit(page: Page, name: string) {
  const row = rowFor(page, name);
  await expect(row).toBeVisible({ timeout: 15000 });
  await row
    .locator('a, button')
    .filter({ hasText: /edit|bearbeiten/i })
    .or(row.locator('a[href*="/edit"], [title*="dit" i], [aria-label*="dit" i]'))
    .first()
    .click();
  await page.waitForURL(/\/ai-integrations\/.+/, { timeout: 15000 });
  const input = page.locator('#parallelWorkerLimit');
  await input.waitFor({ state: 'visible', timeout: 15000 });
  return input;
}

async function deleteIntegration(page: Page, name: string) {
  try {
    await openPage(page, '/ai-integrations');
    const row = rowFor(page, name);
    if (!(await row.count())) return;
    page.once('dialog', (d) => d.accept().catch(() => {}));
    const del = row
      .locator('button, a, input[type="submit"]')
      .filter({ hasText: /delete|löschen|remove/i })
      .or(row.locator('[title*="elete" i], [aria-label*="elete" i], .btn-danger, .btn-outline-danger'))
      .first();
    await del.click({ timeout: 5000 });
    await page.waitForTimeout(500);
    const confirm = page
      .locator('.modal.show')
      .getByRole('button', { name: /delete|löschen|confirm|yes|ok|bestätigen/i })
      .first();
    if (await confirm.isVisible().catch(() => false)) await confirm.click();
    await page.waitForTimeout(1000);
  } catch {
    // best-effort cleanup
  }
}

const createdName = `e2e-pwl-edit-${Date.now()}`;

test.afterEach(async ({ page }) => {
  await deleteIntegration(page, createdName);
});

test('Editing an AI integration updates its Parallel worker limit', async ({ page }) => {
  await openPage(page, '/');

  // Create with limit 1
  await openPage(page, '/ai-integrations/new');
  await expect(page).toHaveURL(/\/ai-integrations\/new/);
  const newInput = page.locator('#parallelWorkerLimit');
  await newInput.waitFor({ state: 'visible', timeout: 15000 });
  await fillRequiredFields(page, createdName);
  await newInput.fill('1');
  await submitForm(page);

  // Open edit form, keep API key blank, set limit to 5
  const editInput = await openEdit(page, createdName);
  await expect(editInput).toHaveValue('1');
  const apiKey = page.locator('#apiKey, input[name="apiKey"]').first();
  if (await apiKey.count()) {
    await expect(apiKey).toHaveValue('');
  }
  await editInput.fill('5');
  await submitForm(page);

  // Re-open edit form and verify
  const reopened = await openEdit(page, createdName);
  await expect(reopened).toHaveValue('5');
});
