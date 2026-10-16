import { expect, test, type Page } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { audit, declaration, meta } from "../../support/form-studio.js";

type Layout = typeof declaration;
type Draft = { entityCode: string; revision: number; declaration: Layout };
function artifact(draft: Draft) {
  return {
    version: 1,
    sourceRevision: draft.revision,
    checksum: "a".repeat(64),
    package: {
      formatVersion: 1,
      kind: "form-layout-package",
      entityCode: declaration.entityCode,
      version: 1,
      sourceRevision: draft.revision,
      dependencies: { contractVersion: 1, metadataChecksum: "b".repeat(64) },
      declaration: structuredClone(draft.declaration),
    },
  };
}
async function server(page: Page) {
  const state = {
    draft: null as Draft | null,
    version: null as ReturnType<typeof artifact> | null,
    reads: 0,
    mutations: [] as string[],
  };
  await page.route("**/api/v1/form-studio/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    if (["POST", "PUT"].includes(method)) {
      expect(request.headers()["idempotency-key"]).toBeUndefined();
      state.mutations.push(method + " " + path);
    }
    if (path.endsWith("/prototype")) return route.fulfill({ json: { declaration, meta } });
    if (path.endsWith("/preview")) return route.fulfill({ json: { declaration: request.postDataJSON(), meta } });
    if (path.endsWith("/drafts/example.products")) {
      if (method === "GET") return route.fulfill({ json: { draft: state.draft, meta } });
      if (method === "PUT" && request.headers()["if-match"] !== `"${state.draft?.revision}"`) {
        return route.fulfill({
          status: 409,
          json: { status: 409, code: "revision_conflict", detail: "Changed elsewhere" },
        });
      }
      state.draft = {
        entityCode: declaration.entityCode,
        revision: (state.draft?.revision ?? 0) + 1,
        declaration: request.postDataJSON(),
      };
      return route.fulfill({ status: method === "POST" ? 201 : 200, json: { draft: state.draft, meta } });
    }
    if (path.endsWith("/versions")) {
      if (method === "POST") {
        expect(request.headers()["if-match"]).toBe(`"${state.draft?.revision}"`);
        state.version ??= artifact(state.draft!);
        return route.fulfill({ status: 201, json: state.version });
      }
      const items = state.version
        ? [
            {
              version: state.version.version,
              sourceRevision: state.version.sourceRevision,
              checksum: state.version.checksum,
              createdAt: "2026-10-15T00:00:00Z",
            },
          ]
        : [];
      return route.fulfill({ json: { items, hasMore: false, nextCursor: null, totalReturned: items.length } });
    }
    if (path.endsWith("/verify")) {
      if (JSON.stringify(request.postDataJSON()) !== JSON.stringify(state.version)) {
        return route.fulfill({
          status: 422,
          json: { status: 422, code: "validation_failed", detail: "Checksum mismatch" },
        });
      }
      return route.fulfill({
        json: {
          valid: true,
          version: state.version!.version,
          sourceRevision: state.version!.sourceRevision,
          checksum: state.version!.checksum,
        },
      });
    }
    if (path.endsWith("/versions/1")) {
      state.reads++;
      return route.fulfill({ json: state.version });
    }
    return route.fulfill({ status: 404, json: { status: 404 } });
  });
  return state;
}

for (const theme of ["light", "dark"] as const) {
  test.describe(`form studio lifecycle in ${theme}`, () => {
    test.beforeEach(async ({ page }) => {
      await page.setViewportSize({ width: 360, height: 800 });
      await page.addInitScript((value) => localStorage.setItem("smc_theme", value), theme);
    });

    test("saves a draft, prepares an immutable version and downloads and verifies its package", async ({ page }) => {
      const state = await server(page);
      const runtimeErrors: string[] = [];
      page.on("pageerror", (error) => runtimeErrors.push(error.message));
      const entityWrites: string[] = [];
      page.on("request", (request) => {
        if (request.url().includes("/entities/") && ["POST", "PUT", "PATCH", "DELETE"].includes(request.method()))
          entityWrites.push(request.url());
      });
      await page.goto("/settings/form-studio");
      const save = page.getByRole("button", { name: "Сохранить черновик", exact: true });
      const seal = page.getByRole("button", { name: "Подготовить версию", exact: true });
      await expect(save).toBeEnabled();
      await expect(seal).toBeDisabled();
      await save.click();
      await expect(page.getByTestId("studio-draft-status")).toContainText("Ревизия черновика: 1");
      await page
        .getByTestId("studio-field-name")
        .getByRole("button", { name: /Поднять/ })
        .click();
      await expect(seal).toBeDisabled();
      await save.click();
      await expect(page.getByTestId("studio-draft-status")).toContainText("Ревизия черновика: 2");
      await seal.click();
      await expect(
        page.getByTestId("studio-version-list").getByRole("button", { name: "Открыть версию 1" }),
      ).toBeVisible();
      await expect(page.getByTestId("studio-selected-version")).toContainText("Из ревизии 2");
      await page
        .getByTestId("studio-field-name")
        .getByRole("button", { name: /Опустить/ })
        .click();
      const firstField = page.getByTestId("form-studio-editor").locator('[data-testid^="studio-field-"]').first();
      await expect(firstField).toHaveAttribute("data-testid", "studio-field-code");
      await page.getByRole("button", { name: "Открыть версию 1", exact: true }).click();
      await expect(firstField).toHaveAttribute("data-testid", "studio-field-code");
      const event = page.waitForEvent("download");
      await page.getByRole("button", { name: "Скачать пакет", exact: true }).click();
      const downloaded = await event;
      const bytes = await readFile((await downloaded.path())!);
      expect(JSON.parse(bytes.toString("utf8"))).toEqual(state.version);
      expect(state.reads).toBe(2);
      const input = page.getByTestId("studio-package-import");
      const tampered = { ...state.version!, checksum: "f".repeat(64) };
      await input.setInputFiles({
        name: "tampered.json",
        mimeType: "application/json",
        buffer: Buffer.from(JSON.stringify(tampered)),
      });
      await expect(page.getByTestId("studio-package-check").getByRole("alert")).toContainText("Checksum mismatch");
      await input.setInputFiles({ name: "version.json", mimeType: "application/json", buffer: bytes });
      await expect(page.getByTestId("studio-package-check").getByRole("status")).toContainText(
        "Пакет версии 1 соответствует",
      );
      await audit(page);
      await page.screenshot({ path: `../.local/studio-02-${theme}-mobile.png`, fullPage: true });
      await page.setViewportSize({ width: 1440, height: 1000 });
      await audit(page);
      await page.screenshot({ path: `../.local/studio-02-${theme}-desktop.png`, fullPage: true });
      expect(runtimeErrors).toEqual([]);
      expect(entityWrites).toEqual([]);
      expect(state.draft?.revision).toBe(2);
    });

    test("retains local edits on revision conflict until an explicit confirmed reload", async ({ page }) => {
      const state = await server(page);
      state.draft = { entityCode: declaration.entityCode, revision: 1, declaration: structuredClone(declaration) };
      await page.goto("/settings/form-studio");
      const fields = page.getByTestId("form-studio-editor").locator('[data-testid^="studio-field-"]');
      await page
        .getByTestId("studio-field-name")
        .getByRole("button", { name: /Поднять/ })
        .click();
      state.draft = { ...state.draft, revision: 2 };
      await page.getByRole("button", { name: "Сохранить черновик", exact: true }).click();
      await expect(page.getByTestId("studio-draft-conflict")).toBeVisible();
      await expect(fields.first()).toHaveAttribute("data-testid", "studio-field-name");
      await expect(page.getByTestId("studio-draft-status")).toContainText("Ревизия черновика: 1");
      await audit(page);
      const reload = page.getByRole("button", { name: "Загрузить с сервера", exact: true });
      await reload.click();
      await page.getByRole("button", { name: "Продолжить редактирование", exact: true }).click();
      await expect(fields.first()).toHaveAttribute("data-testid", "studio-field-name");
      await reload.click();
      await page.getByRole("button", { name: "Не сохранять", exact: true }).click();
      await expect(page.getByTestId("studio-draft-status")).toContainText("Ревизия черновика: 2");
      await expect(page.getByTestId("studio-draft-conflict")).toHaveCount(0);
      await expect(fields.first()).toHaveAttribute("data-testid", "studio-field-code");
      await expect(page.getByRole("button", { name: "Подготовить версию", exact: true })).toBeEnabled();
    });
  });
}
