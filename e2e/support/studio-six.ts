import type { QueryFieldMeta } from "../../apps/web/src/app/core/models/query-meta.models.js";
import { AxeBuilder } from "@axe-core/playwright";
import { expect, type Page } from "@playwright/test";

export const studioDataset = "ms.tasks.projects";
const pageOf = (items: unknown[]) => ({ items, nextCursor: null, hasMore: false, totalReturned: items.length });
const field = (
  key: string,
  label: string,
  type: QueryFieldMeta["type"],
  extra: Partial<QueryFieldMeta> = {},
): QueryFieldMeta => ({
  key,
  labelKey: "",
  label,
  type,
  sortable: false,
  searchable: false,
  ops: ["eq", "ne", "empty", "not_empty"],
  nullable: true,
  defaultVisible: true,
  enumValues: [],
  enumLabelPrefix: null,
  ...extra,
});
const fields = [
  field("createdAt", "Дата создания задачи", "instant"),
  field("priority", "Приоритет", "enum", { enumValues: ["low", "medium", "high"], enumLabelPrefix: "tasks.priority." }),
];
const meta = (projected: boolean) => ({
  code: "ms.tasks",
  defaultSort: "-createdAt",
  defaultLimit: 50,
  maxLimit: 200,
  maxConditions: 20,
  maxInValues: 100,
  fields: [
    ...fields,
    ...(projected
      ? [field("projectCreatedAt", "", "instant", { label: null, labelKey: "tasks.dataset.project_created_at" })]
      : []),
  ],
});
export const proposedForm = {
  formatVersion: 1,
  kind: "simple-form",
  module: { code: "inventory", title: { ru: "Склад", uz: "Ombor", en: "Inventory" } },
  entity: { name: "rooms", title: { ru: "Помещения", uz: "Xonalar", en: "Rooms" } },
  fields: [
    {
      id: "name",
      type: "text",
      labels: { ru: "Название", uz: "Nomi", en: "Name" },
      required: true,
      listed: true,
      maxLength: 100,
    },
  ],
};
export function chartResult(line: boolean) {
  return {
    groups: [{ field: line ? "projectCreatedAt" : "priority", ...(line ? { trunc: "day" } : {}), implicit: false }],
    measures: [{ op: "count" }],
    rows: (line ? ["2026-10-01", "2026-10-02", "2026-10-04", "2026-10-05"] : ["low", "medium", "high", null]).map(
      (value, index) => ({ groups: [value], values: [[3, 0, 5, 2][index]] }),
    ),
    truncated: false,
  };
}
export async function reportStudioServer(page: Page) {
  const state = { allowed: true, queries: [] as string[], exports: [] as string[] };
  await page.route("**/api/v1/form-meta/ms.tasks", (route) =>
    route.fulfill({
      json: {
        code: "ms.tasks",
        listCode: "ms.tasks",
        fields: [{ key: "title", labelKey: "tasks.col.title", type: "text", required: true }],
        layout: [{ key: "main", labelKey: "entity.section.main", fields: ["title"] }],
        actions: ["create", "update"],
        capabilities: ["export", "saved_views"],
        collections: [],
        tabs: [],
      },
    }),
  );
  await page.route("**/api/v1/query-meta/ms.tasks", (route) => route.fulfill({ json: meta(false) }));
  await page.route("**/api/v1/list-views/ms.tasks**", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/v1/entities/ms.tasks**", (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/report-datasets"))
      return route.fulfill({
        json: { items: [{ code: studioDataset, titleKey: "tasks.dataset.projects" }] },
      });
    if (url.pathname.endsWith("/report-meta"))
      return state.allowed
        ? route.fulfill({ json: meta(true) })
        : route.fulfill({ status: 404, json: { detail: "Dataset access revoked" } });
    if (url.pathname.endsWith("/report/export")) {
      state.exports.push(url.search);
      return route.fulfill({
        contentType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        body: Buffer.from("fixture"),
      });
    }
    if (url.pathname.endsWith("/report")) {
      state.queries.push(url.search);
      const groups = JSON.parse(url.searchParams.get("groupBy") ?? "[]") as { field: string }[];
      if (!groups.length)
        return route.fulfill({
          json: { groups: [], measures: [{ op: "count" }], rows: [{ groups: [], values: [4] }], truncated: false },
        });
      return route.fulfill({ json: chartResult(groups[0].field === "projectCreatedAt") });
    }
    return route.fulfill({ json: pageOf([]) });
  });
  const reports = [11, 12].map((id) => ({
    id,
    entity: "ms.tasks",
    listCode: "ms.tasks",
    kind: "report",
    name: id === 11 ? "Задачи по дате проекта" : "Задачи по приоритетам",
    state: {
      groupBy: [{ field: id === 11 ? "projectCreatedAt" : "priority", ...(id === 11 ? { trunc: "day" } : {}) }],
      measures: [{ op: "count" }],
      filter: [],
      chart: id === 11 ? "line" : "horizontalBar",
      dataset: studioDataset,
    },
    lockVersion: 0,
    modifiedAt: "2026-10-15T00:00:00Z",
    canEdit: true,
    canShare: true,
    shared: false,
  }));
  const panel = {
    id: 6,
    name: "Источники и графики",
    revision: 1,
    canEdit: true,
    canShare: true,
    shared: false,
    widgetCount: 2,
    widgets: reports.map((report) => ({ viewId: report.id, width: 1, available: true, report })),
  };
  await page.route("**/api/v1/report-views?**", (route) => route.fulfill({ json: pageOf(reports) }));
  await page.route("**/api/v1/dashboards**", (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/result")) return route.fulfill({ json: chartResult(url.pathname.includes("/11/")) });
    return route.fulfill({ json: url.pathname.endsWith("/dashboards") ? pageOf([panel]) : panel });
  });
  return state;
}
export async function auditStudio06(page: Page, selector: string) {
  await page.locator(selector).evaluate(async (element) => {
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
    await Promise.allSettled(
      element
        .getAnimations({ subtree: true })
        .filter((animation) => animation.effect?.getTiming().iterations !== Infinity)
        .map((animation) => animation.finished),
    );
  });
  const result = await new AxeBuilder({ page })
    .include(selector)
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  expect(result.violations.map(({ id, nodes }) => ({ id, targets: nodes.map((node) => node.target) }))).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  const overlaps = await page.locator(selector).evaluate((element) => {
    const buttons = [...element.querySelectorAll<HTMLButtonElement>("button[smt-button]")].filter(
      (button) => button.offsetWidth && button.offsetHeight,
    );
    return buttons.some((button, index) =>
      buttons.slice(index + 1).some((other) => {
        const a = button.getBoundingClientRect(),
          b = other.getBoundingClientRect();
        return (
          Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1 &&
          Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1
        );
      }),
    );
  });
  expect(overlaps, "visible action buttons must not overlap").toBe(false);
}
