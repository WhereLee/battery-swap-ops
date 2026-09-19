/**
 * _c35_console.mjs - batch31: real-browser acceptance run of the swap-web console.
 *
 * Why this exists:
 *   `vue-tsc` and `vite build` prove the bundle compiles, and _c34 proves the HTTP
 *   contract. Neither proves the pages RENDER: a renamed field, a capability bit the
 *   UI ignores, or a runtime error in a template all pass both gates and still show a
 *   blank screen. Batch30 found its most severe defect (missing pagination
 *   interceptor) exactly this way - by opening the console in a browser - but that run
 *   was manual and unrepeatable. This script automates it.
 *
 * How it works: see `_cdp.mjs` (zero-dependency CDP client over the locally installed
 * Chrome/Edge; nothing to npm-install, nothing to keep in sync with the browser version).
 *
 * batch38 changes (both learned from the vision-model review of the batch31 screenshots):
 *   * the viewport is now set with Emulation.setDeviceMetricsOverride, so the rendered
 *     viewport is exactly 1440x900. A plain `--window-size` is an OUTER window size and
 *     produced ~1414x800 headless - every screenshot was silently narrower and shorter
 *     than intended, which made a reviewer report content "cut off at the fold".
 *   * screenshots are full-page (captureBeyondViewport), so a visual review sees the whole
 *     document instead of just the first 800px.
 *
 * Inputs (environment): APP_URL, ADMIN_USER, ADMIN_PASS, SHOT_DIR (optional).
 * Output: PASS/FAIL lines + a summary on stdout; exit code 1 when anything failed.
 */

import { launch, collectErrors, login, sleep } from "./_cdp.mjs";

const APP_URL = process.env.APP_URL ?? "http://127.0.0.1:4173";
const ADMIN_USER = process.env.ADMIN_USER ?? "admin";
const ADMIN_PASS = process.env.ADMIN_PASS ?? "";
const SHOT_DIR = process.env.SHOT_DIR ?? "";

let pass = 0;
let fail = 0;
const failures = [];
function check(name, ok, detail = "") {
  if (ok) {
    pass++;
    console.log(`PASS  ${name}${detail ? ` (${detail})` : ""}`);
  } else {
    fail++;
    failures.push(name);
    console.log(`FAIL  ${name}${detail ? ` (${detail})` : ""}`);
  }
}
function info(message) {
  console.log(`INFO  ${message}`);
}

async function main() {
  if (!ADMIN_PASS) {
    console.log("FATAL ADMIN_PASS is empty (read .local/admin-pass.txt)");
    process.exit(1);
  }
  const session = await launch({ width: 1440, height: 900 });
  const { consoleErrors, httpErrors, failedRequests } = collectErrors(session);
  info(`browser: ${session.browserPath}`);
  info(`app url: ${APP_URL}`);

  const { evaluate, goto } = session;
  /**
   * Label + timeout wrapper around the shared primitive. `session.waitFor` takes a raw
   * timeout, but every call site here names what it is waiting for, and a bare boolean
   * "false" from a click-through check says nothing about which step stalled. Kept as an
   * explicit (expression, label, timeoutMs) signature rather than the polymorphic
   * second argument the pre-refactor version accepted - that looseness is precisely how a
   * string label silently became a NaN deadline.
   */
  const waitFor = async (expression, label, timeoutMs = 15000) => {
    const ok = await session.waitFor(expression, timeoutMs);
    if (!ok) {
      info(`waitFor timed out after ${timeoutMs}ms: ${label}`);
    }
    return ok;
  };
  /**
   * Navigation AS a check, not as a raw side effect. The pre-refactor script counted every
   * page load ("B goto /orders renders (order rows)"), which is why the batch31 baseline
   * reads 31/31 while the numbered business checks stop at B25. Extracting the CDP client
   * into _cdp.mjs dropped those six lines: coverage quietly fell to 25 checks while every
   * document still claimed 31. Counting a landing that never renders as its own failure is
   * also what makes the following assertion failures readable.
   */
  const navCheck = async (path, readyExpression, label) => {
    const ok = await session.goto(`${APP_URL}${path}`, readyExpression);
    check(`B goto ${path} renders (${label})`, ok);
    return ok;
  };
  const shot = async (name) => {
    if (!SHOT_DIR) {
      return;
    }
    const meta = await session.screenshot(`${SHOT_DIR}\\${name}.png`, { fullPage: true });
    info(`screenshot ${name}.png (${Math.round(meta.bytes / 1024)} kB, captured ${meta.width}x${meta.height})`);
  };

  try {
    // ---------- B01/B02: login page + real login ----------
    await goto(`${APP_URL}/login`, "!!document.querySelector('.login-btn')");
    check("B01 login page renders", (await evaluate("!!document.querySelector('.login-btn')")) === true);
    await shot("01-login");

    const signedIn = await login(session, APP_URL, ADMIN_USER, ADMIN_PASS);
    const onDashboard = signedIn && (await waitFor("!!document.querySelector('.metric-value')", "dashboard metric cards", 20000));
    check("B02 login form authenticates and lands on /dashboard", onDashboard);
    check("B03 token is persisted under the documented localStorage key",
      (await evaluate("!!localStorage.getItem('swap.admin.token')")) === true);

    // ---------- B04: menu is derived from the route table and permission-filtered ----------
    const menu = await evaluate(
      "Array.from(document.querySelectorAll('.el-menu-item')).map((el) => el.textContent.trim())",
    );
    const expectedMenu = ["运营看板", "告警与建议单", "工单", "换电订单", "换电柜", "结算单"];
    check("B04 side menu lists all six pages for a SUPER identity",
      JSON.stringify(menu) === JSON.stringify(expectedMenu), menu.join(" / "));

    // ---------- B05: dashboard ----------
    const metrics = await evaluate("document.querySelectorAll('.metric-value').length");
    check("B05 dashboard renders 4 metric cards", metrics === 4, `cards=${metrics}`);
    await shot("02-dashboard");

    // ---------- B06: alarm + suggestion tabs ----------
    await navCheck("/alarm", "!!document.querySelector('.el-tabs')", "alarm tabs");
    const alarmTotal = await evaluate(
      "Number((document.body.innerText.match(/共\\s*(\\d+)\\s*条/) ?? [0, 0])[1])",
    );
    // el-tabs keeps the inactive pane in the DOM, so a plain `tr` count mixes the alarm
    // and suggestion tables (batch30 recorded the same trap). Scope to the visible pane.
    const alarmRows = await evaluate(`(() => {
      const pane = Array.from(document.querySelectorAll('.el-tab-pane'))
        .filter((el) => getComputedStyle(el).display !== 'none')[0];
      return pane ? pane.querySelectorAll('.el-table__row').length : -1;
    })()`);
    check("B06 alarm page renders rows consistent with its reported total",
      alarmRows >= 0 && alarmRows <= 20 && (alarmTotal === 0 ? alarmRows === 0 : alarmRows > 0),
      `total=${alarmTotal} visibleRows=${alarmRows}`);
    await shot("03-alarm");

    // ---------- B07/B08: work-order list -> detail (click-through) ----------
    await navCheck("/work-orders", "document.querySelectorAll('.el-table__row').length > 0", "work-order rows");
    const woRows = await evaluate("document.querySelectorAll('.el-table__row').length");
    check("B07 work-order list renders rows", woRows > 0, `rows=${woRows}`);
    await shot("04-work-orders");
    await evaluate("document.querySelector('.el-table__row .el-link').click(), true");
    const woDetailReady = await waitFor(
      "/^\\/work-orders\\/\\d+$/.test(location.pathname) && !!document.querySelector('.el-descriptions')",
      "work-order detail",
    );
    check("B08 work-order detail opens from the list and renders the flow log",
      woDetailReady &&
        (await evaluate("!!document.querySelector('.el-timeline') || !!document.querySelector('.el-empty')")) === true);
    await shot("05-work-order-detail");

    // ---------- B09/B10/B11: order list -> detail ----------
    await navCheck("/orders", "document.querySelectorAll('.el-table__row').length > 0", "order rows");
    const orderRows = await evaluate("document.querySelectorAll('.el-table__row').length");
    check("B09 order list renders a full page of rows", orderRows === 20, `rows=${orderRows}`);
    // Scope to the table: the page subtitle *mentions* idemKey on purpose (it explains
    // that the list does not carry internal columns), so the whole-body text is the
    // wrong place to look for a leak.
    const noSecret = await evaluate(
      "!Array.from(document.querySelectorAll('.el-table')).some((t) => t.innerText.includes('idemKey'))",
    );
    check("B10 order table does not surface internal columns", noSecret === true);
    await shot("06-orders");
    await evaluate("document.querySelector('.el-table__row .el-link').click(), true");
    const orderDetailReady = await waitFor(
      "/^\\/orders\\/.+/.test(location.pathname) && document.querySelectorAll('.el-card').length >= 3",
      "order detail",
    );
    check("B11 order detail opens from the list and renders payments/refunds cards", orderDetailReady);
    await shot("07-order-detail");

    // ---------- B12/B13/B14: cabinet list -> detail ----------
    await navCheck("/cabinets", "document.querySelectorAll('.el-table__row').length > 0", "cabinet rows");
    const cabinetRows = await evaluate("document.querySelectorAll('.el-table__row').length");
    check("B12 cabinet list renders rows", cabinetRows > 0, `rows=${cabinetRows}`);
    await shot("08-cabinets");
    await evaluate("document.querySelector('.el-table__row .el-link').click(), true");
    const cabinetDetailReady = await waitFor(
      "/^\\/cabinets\\/.+/.test(location.pathname) && document.querySelectorAll('.el-card').length >= 4",
      "cabinet detail",
    );
    check("B13 cabinet detail opens from the list and renders all four blocks", cabinetDetailReady);
    const cellRows = await evaluate(
      "Array.from(document.querySelectorAll('.el-card')).filter((c) => c.innerText.includes('仓位与电池'))[0]" +
        "?.querySelectorAll('.el-table__row').length ?? 0",
    );
    check("B14 cabinet detail renders one row per cell", cellRows >= 12, `cells=${cellRows}`);
    await shot("09-cabinet-detail");

    // ---------- B15/B16/B17: settlement list -> detail ----------
    await navCheck("/settlements", "document.querySelectorAll('.el-table__row').length > 0", "settlement rows");
    const settlementRows = await evaluate("document.querySelectorAll('.el-table__row').length");
    check("B15 settlement list renders rows", settlementRows > 0, `rows=${settlementRows}`);
    await shot("10-settlements");
    await evaluate("document.querySelector('.el-table__row .el-link').click(), true");
    const settlementDetailReady = await waitFor(
      "/^\\/settlements\\/\\d+$/.test(location.pathname) && !!document.querySelector('.el-descriptions')",
      "settlement detail",
    );
    check("B16 settlement detail opens from the list", settlementDetailReady);
    const ledgerRows = await evaluate(
      "Array.from(document.querySelectorAll('.el-card')).filter((c) => c.innerText.includes('分账流水'))[0]" +
        "?.querySelectorAll('.el-table__row').length ?? 0",
    );
    check("B17 settlement detail renders the split ledger lines", ledgerRows > 0, `lines=${ledgerRows}`);
    await shot("11-settlement-detail");

    // ---------- B18/B19/B20: guard + redirect round trip ----------
    await evaluate("localStorage.removeItem('swap.admin.token'), true");
    await session.cdp.send("Page.navigate", { url: `${APP_URL}/work-orders` });
    const guarded = await waitFor("location.pathname === '/login'", "login redirect after logout");
    check("B18 route guard sends an unauthenticated deep link to /login", guarded);
    check("B19 the guard preserves the requested path in ?redirect=",
      (await evaluate("(new URLSearchParams(location.search).get('redirect') ?? '')")) === "/work-orders");
    const reSignedIn = await login(session, APP_URL, ADMIN_USER, ADMIN_PASS);
    const backToDeepLink = reSignedIn && (await waitFor("location.pathname === '/work-orders'", "redirect back to the deep link", 20000));
    check("B20 re-login returns the user to the originally requested page", backToDeepLink);

    // ---------- B21/B22: viewport sanity ----------
    await navCheck("/orders", "document.querySelectorAll('.el-table__row').length > 0", "order rows again");
    const layout = await evaluate(`JSON.stringify({
      viewport: window.innerWidth,
      bodyScroll: document.body.scrollWidth,
      client: document.documentElement.clientWidth,
      placeholders: (document.body.innerText.match(/undefined|NaN|\\[object Object\\]/g) ?? []).length
    })`);
    const layoutObj = JSON.parse(layout);
    check("B21 viewport is the requested 1440px and the page does not overflow",
      layoutObj.viewport === 1440 && layoutObj.bodyScroll <= layoutObj.client,
      `viewport=${layoutObj.viewport} body=${layoutObj.bodyScroll} client=${layoutObj.client}`);
    check("B22 no undefined/NaN/[object Object] rendered anywhere on the order page",
      layoutObj.placeholders === 0, `hits=${layoutObj.placeholders}`);

    // ---------- B23/B24/B25: runtime hygiene ----------
    check("B23 console errors during the whole run", consoleErrors.length === 0, consoleErrors.slice(0, 3).join(" | "));
    check("B24 no HTTP >= 400 response during the whole run", httpErrors.length === 0, httpErrors.slice(0, 3).join(" | "));
    check("B25 no failed network request during the whole run", failedRequests.length === 0, failedRequests.slice(0, 3).join(" | "));
  } finally {
    session.close();
  }

  console.log("");
  console.log(`=== C35 summary: PASS=${pass} FAIL=${fail} ===`);
  if (fail > 0) {
    console.log("GATE-BROWSER FAIL");
    process.exit(1);
  }
  console.log("GATE-BROWSER PASS");
}

main().catch((error) => {
  console.log(`FATAL ${error.stack ?? error.message}`);
  process.exit(1);
});
