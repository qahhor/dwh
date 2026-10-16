import { expect, type Page } from "@playwright/test";
import { AxeBuilder } from "@axe-core/playwright";

export const reports = [7, 8].map((id) => ({
  id,
  entity: "example.orders",
  listCode: "example.orders",
  kind: "report",
  name: id === 7 ? "Продажи" : "Количество",
  state: { groupBy: [], measures: [{ op: "count" }], filter: [], chart: "kpi" },
  lockVersion: 0,
  modifiedAt: "2026-10-15T00:00:00Z",
  canEdit: true,
  canShare: true,
  shared: false,
}));
export interface Widget {
  viewId: number;
  width: number;
  periodField?: string;
}
export async function dashboardServer(page: Page) {
  const state = {
    name: "Продажи за период",
    revision: 3,
    widgets: [
      { viewId: 7, width: 1, periodField: "orderDate" },
      { viewId: 8, width: 1 },
    ] as Widget[],
    roles: [2],
    conflict: false,
    failWidget: false,
    denyExport: false,
    results: [] as string[],
    exports: [] as string[],
    writes: [] as string[],
  };
  const detail = () => ({
    id: 9,
    name: state.name,
    revision: state.revision,
    modifiedAt: "2026-10-15T00:00:00Z",
    canEdit: true,
    canShare: true,
    shared: state.roles.length > 0,
    widgetCount: state.widgets.length,
    widgets: state.widgets.map((widget) => ({
      ...widget,
      available: true,
      report: reports.find((report) => report.id === widget.viewId),
    })),
  });
  const pageOf = (items: unknown[]) => ({ items, hasMore: false, nextCursor: null, totalReturned: items.length });
  await page.route("**/api/v1/report-views?**", (route) => route.fulfill({ json: pageOf(reports) }));
  await page.route("**/api/v1/report-sharing/roles?**", (route) =>
    route.fulfill({
      json: pageOf([
        { id: 2, name: "Аналитики" },
        { id: 3, name: "Руководители" },
      ]),
    }),
  );
  await page.route("**/api/v1/dashboards**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();
    if (["POST", "PUT", "DELETE"].includes(method)) {
      expect(request.headers()["idempotency-key"]).toBeUndefined();
      state.writes.push(method + " " + url.pathname);
    }
    if (url.pathname.endsWith("/dashboards")) {
      if (method === "POST") {
        state.name = request.postDataJSON().name;
        state.widgets = [];
        state.revision = 1;
        return route.fulfill({ status: 201, json: detail() });
      }
      return route.fulfill({ json: pageOf([detail()]) });
    }
    if (url.pathname.endsWith("/sharing")) {
      if (method === "PUT") {
        expect(request.headers()["if-match"]).toBe(`"${state.revision}"`);
        state.roles = request.postDataJSON().roleIds;
        state.revision++;
      }
      return route.fulfill({ json: { revision: state.revision, roleIds: state.roles } });
    }
    if (/\/widgets\/\d+\/(result|export)$/u.test(url.pathname)) {
      expect(request.headers()["if-match"]).toBe(`"${state.revision}"`);
      if (url.pathname.endsWith("/export")) {
        state.exports.push(url.search);
        return state.denyExport
          ? route.fulfill({
              status: 403,
              contentType: "application/problem+json",
              json: { status: 403, detail: "Export access revoked" },
            })
          : route.fulfill({
              contentType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
              body: Buffer.from("fixture-xlsx"),
            });
      }
      state.results.push(url.pathname + url.search);
      if (state.failWidget && url.pathname.includes("/8/"))
        return route.fulfill({ status: 422, json: { status: 422, detail: "Field access revoked" } });
      return route.fulfill({
        json: {
          groups: [],
          measures: [{ op: "count" }],
          rows: [{ groups: [], values: [url.pathname.includes("/7/") ? 41 : 12] }],
          truncated: false,
        },
      });
    }
    if (url.pathname.endsWith("/9")) {
      if (method === "PUT") {
        expect(request.headers()["if-match"]).toBe(`"${state.revision}"`);
        if (state.conflict) return route.fulfill({ status: 409, json: { status: 409, detail: "Revision changed" } });
        const body = request.postDataJSON();
        state.name = body.name;
        state.widgets = body.widgets;
        state.revision++;
      }
      return route.fulfill({ json: detail() });
    }
    return route.fulfill({ status: 404, json: { status: 404 } });
  });
  return state;
}
export async function auditPanels(page: Page) {
  await page.getByTestId("dashboards").evaluate(async (element) => {
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
    await Promise.allSettled(
      element
        .getAnimations({ subtree: true })
        .filter((animation) => animation.effect?.getTiming().iterations !== Infinity)
        .map((animation) => animation.finished),
    );
  });
  for (const card of await page.getByTestId("dashboard-widget").all()) {
    const overlap = await card.evaluate((element) => {
      const titles = element.querySelector(".dash-card__titles")!;
      const range = document.createRange();
      range.selectNodeContents(titles);
      const title = range.getBoundingClientRect();
      const actions = element.querySelector(".dash-card__actions")!.getBoundingClientRect();
      return (
        Math.min(title.right, actions.right) > Math.max(title.left, actions.left) &&
        Math.min(title.bottom, actions.bottom) > Math.max(title.top, actions.top)
      );
    });
    expect(overlap, "widget actions must not overlap the title").toBe(false);
  }
  const result = await new AxeBuilder({ page })
    .include('[data-testid="dashboards"]')
    .include('[role="dialog"]')
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  expect(
    result.violations.map(({ id, impact, nodes }) => ({ id, impact, nodes: nodes.map((node) => node.target) })),
  ).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
}
