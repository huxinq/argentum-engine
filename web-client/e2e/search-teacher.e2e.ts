import { expect, test } from '@playwright/test'

test('locked Solo mirror reaches mulligans and exposes read-only search diagnostics', async ({ page }) => {
  test.setTimeout(120_000)
  await page.goto('/')
  await page.getByPlaceholder('Your name').fill('Reducer Explorer')
  await page.getByRole('button', { name: 'Continue' }).click()
  await expect(page.getByTestId('wizard-roster-solo')).toBeVisible({ timeout: 10_000 })

  await page.getByTestId('wizard-roster-solo').click()
  await page.getByTestId('wizard-cards-bring-a-deck').click()
  await page.getByTestId('wizard-create').click()

  await expect(page.getByTestId('search-teacher-lock')).toContainText('Locked Search Teacher match')
  await expect(page.getByTestId('search-teacher-lock')).toContainText('8×64')
  await expect(page.getByRole('button', { name: /Choose your deck/i })).toHaveCount(0)
  await expect(page.getByRole('button', { name: "I'm ready" })).toBeEnabled()

  await page.getByRole('button', { name: "I'm ready" }).click()
  const keep = page.getByRole('button', { name: 'Keep Hand' })
  await expect(keep).toBeVisible({ timeout: 60_000 })
  await keep.click()

  const insightToggle = page.getByRole('button', { name: /Search Teacher \(\d+\)/ })
  await expect(insightToggle).toBeVisible({ timeout: 60_000 })
  await insightToggle.click()
  await expect(page.getByTestId('search-teacher-insight')).toContainText('read-only')
  await expect(page.getByTestId('search-teacher-insight')).toContainText('visits')
  await expect(page.getByRole('button', { name: 'Export' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /Playing|Stepping/ })).toHaveCount(0)

  // Require another semantic decision after the opening root. Depending on the frozen seed this
  // may be a later mulligan or the first main-phase choice; either way it proves prefix catch-up.
  await page.getByTitle('Close').click()
  await expect(page.getByRole('button', { name: /Search Teacher \(([2-9]|\d{2,})\)/ })).toBeVisible({
    timeout: 60_000,
  })
})
