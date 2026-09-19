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
 * How it works (zero dependencies, on purpose):
 *   - launches the locally installed Chrome/Edge headless with --remote-debugging-port
 *   - talks raw CDP over the WebSocket built into Node 22 (no puppeteer/playwright,
 *     nothing to install, nothing to keep in sync with the browser version)
 *   - drives the real login form, then walks every console page by clicking the same
 *     links a user would click (so in-app routing is exercised, not just deep links)
 *   - fails on any console error, any uncaught exception, any HTTP >= 400 the app made,
 *     and any page whose DOM does not contain the rows it is supposed to render
 *
 * Inputs (environment): APP_URL, ADMIN_USER, ADMIN_PASS, SHOT_DIR (optional).
 * Output: PASS/FAIL lines + a summary on stdout; exit code 1 when anything failed.
 */

import { spawn } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const APP_URL = process.env.APP_URL ?? "http://127.0.0.1:4173";
const ADMIN_USER = process.env.ADMIN_USER ?? "admin";
const ADMIN_PASS = process.env.ADMIN_PASS ?? "";
const SHOT_DIR = process.env.SHOT_DIR ?? "";
const DEBUG_PORT = Number(process.env.CDP_PORT ?? 9333);

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

function findBrowser() {
  const candidates = [
    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
    "/usr/bin/chromium-browser",
  ];
  for (const candidate of candidates) {
    if (existsSync(candidate)) {
      return candidate;
    }
  }
  return null;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function main() {
  if (!ADMIN_PASS) {
    console.log("FATAL ADMIN_PASS is empty (read .local/admin-pass.txt)");
    process.exit(1);
  }
  const browser = findBrowser();
  if (!browser) {
    console.log("FATAL no Chrome/Edge found for the browser run");
    process.exit(1);
  }
  info(`browser: ${browser}`);
  info(`app url: ${APP_URL}`);

  const profile = mkdtempSync(join(tmpdir(), "swap-cdp-"));
  const child = spawn(browser, [
    "--headless=new",
    "--disable-gpu",
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-extensions",
    "--window-size=1440,900",
    `--remote-debugging-port=${DEBUG_PORT}`,
    `--user-data-dir=${profile}`,
    "about:blank",
  ], { stdio: "ignore" });

  let ws;
  try {
    ws = await connectWithRetry();
    const cdp = createClient(ws);
    await cdp.send("Page.enable");
    await cdp.send("Runtime.enable");
    await cdp.send("Network.enable");
    await cdp.send("Log.enable");

    const consoleErrors = [];
    const httpErrors = [];
    const failedRequests = [];
    cdp.on("Runtime.consoleAPICalled", (params) => {
      if (params.type === "error") {
        consoleErrors.push(textOfConsole(params));
      }
    });
    cdp.on("Runtime.exceptionThrown", (params) => {
      consoleErrors.push(params.exceptionDetails?.exception?.description ?? "uncaught exception");
    });
    cdp.on("Log.entryAdded", (params) => {
      const entry = params.entry ?? {};
      if (entry.level === "error" && !String(entry.url ?? "").includes("favicon")) {
        consoleErrors.push(`${entry.source}: ${entry.text}`);
      }
    });
    cdp.on("Network.responseReceived", (params) => {
      const { response } = params;
      if (response.status >= 400 && !String(response.url).includes("favicon")) {
        httpErrors.push(`${response.status} ${response.url}`);
      }
    });
    cdp.on("Network.loadingFailed", (params) => {
      if (!params.canceled) {
        failedRequests.push(params.errorText);
      }
    });

    const evaluate = async (expression) => {
      const result = await cdp.send("Runtime.evaluate", {
        expression,
        awaitPromise: true,
        returnByValue: true,
      });
      if (result.exceptionDetails) {
        throw new Error(result.exceptionDetails.exception?.description ?? "evaluate failed");
      }
      return result.result.value;
    };

    const waitFor = async (expression, label, timeoutMs = 15000) => {
      const deadline = Date.now() + timeoutMs;
      while (Date.now() < deadline) {
        try {
          if (await evaluate(expression)) {
            return true;
          }
        } catch {
          // Navigation tears the execution context down; retry until the deadline.
        }
        await sleep(200);
      }
      info(`timeout waiting for ${label}`);
      return false;
    };

    const goto = async (path, readyExpression, label) => {
      await cdp.send("Page.navigate", { url: `${APP_URL}${path}` });
      const ready = await waitFor(readyExpression, label);
      check(`B goto ${path} renders (${label})`, ready);
      return ready;
    };

    const shot = async (name) => {
      if (!SHOT_DIR) {
        return;
      }
      mkdirSync(SHOT_DIR, { recursive: true });
      const { data } = await cdp.send("Page.captureScreenshot", { format: "png" });
      writeFileSync(join(SHOT_DIR, `${name}.png`), Buffer.from(data, "base64"));
    };

    // ---------- B01: login page ----------
    await goto("/login", "!!document.querySelector('.login-btn')", "login card");
    await shot("01-login");

    // ---------- B02: real login through the form ----------
    await evaluate(`(() => {
      const setValue = (selector, value) => {
        const el = document.querySelector(selector);
        const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        setter.call(el, value);
        el.dispatchEvent(new Event('input', { bubbles: true }));
      };
      setValue('input[autocomplete="username"]', ${JSON.stringify(ADMIN_USER)});
      setValue('input[autocomplete="current-password"]', ${JSON.stringify(ADMIN_PASS)});
      return true;
    })()`);
    await evaluate("document.querySelector('.login-btn').click(), true");
    const loggedIn = await waitFor(
      "location.pathname === '/dashboard' && !!document.querySelector('.metric-value')",
      "dashboard after login",
      20000,
    );
    check("B02 login form authenticates and lands on /dashboard", loggedIn);
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
    await goto("/alarm", "!!document.querySelector('.el-tabs')", "alarm tabs");
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
    await goto("/work-orders", "document.querySelectorAll('.el-table__row').length > 0",
      "work-order rows");
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

    // ---------- B09/B10: order list -> detail ----------
    await goto("/orders", "document.querySelectorAll('.el-table__row').length > 0", "order rows");
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

    // ---------- B12/B13: cabinet list -> detail ----------
    await goto("/cabinets", "document.querySelectorAll('.el-table__row').length > 0", "cabinet rows");
    const cabinetRows = await evaluate("document.querySelectorAll('.el-table__row').length");
    check("B12 cabinet list renders rows", cabinetRows > 0, `rows=${cabinetRows}`);
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
    await shot("08-cabinet-detail");

    // ---------- B15/B16: settlement list -> detail ----------
    await goto("/settlements", "document.querySelectorAll('.el-table__row').length > 0",
      "settlement rows");
    const settlementRows = await evaluate("document.querySelectorAll('.el-table__row').length");
    check("B15 settlement list renders rows", settlementRows > 0, `rows=${settlementRows}`);
    await shot("09-settlements");
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
    await shot("10-settlement-detail");

    // ---------- B18/B19: guard blocks a deep link, then honours the redirect param ----------
    // The bootstrap admin is SUPER (holds every code), so the honest negative case is
    // "no token" rather than "no code": clear the token and confirm the guard redirects.
    await evaluate("localStorage.removeItem('swap.admin.token'), true");
    await cdp.send("Page.navigate", { url: `${APP_URL}/work-orders` });
    const guarded = await waitFor("location.pathname === '/login'", "login redirect after logout");
    check("B18 route guard sends an unauthenticated deep link to /login", guarded);
    check("B19 the guard preserves the requested path in ?redirect=",
      (await evaluate("(new URLSearchParams(location.search).get('redirect') ?? '')")) === "/work-orders");
    await evaluate(`(() => {
      const setValue = (selector, value) => {
        const el = document.querySelector(selector);
        const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        setter.call(el, value);
        el.dispatchEvent(new Event('input', { bubbles: true }));
      };
      setValue('input[autocomplete="username"]', ${JSON.stringify(ADMIN_USER)});
      setValue('input[autocomplete="current-password"]', ${JSON.stringify(ADMIN_PASS)});
      document.querySelector('.login-btn').click();
      return true;
    })()`);
    const backToDeepLink = await waitFor(
      "location.pathname === '/work-orders'",
      "deep link after re-login",
      20000,
    );
    check("B20 re-login returns the user to the originally requested page", backToDeepLink);

    // ---------- B21: viewport sanity ----------
    await goto("/orders", "document.querySelectorAll('.el-table__row').length > 0", "order rows again");
    const layout = await evaluate(`JSON.stringify({
      viewport: window.innerWidth,
      bodyScroll: document.body.scrollWidth,
      client: document.documentElement.clientWidth,
      placeholders: (document.body.innerText.match(/undefined|NaN|\\[object Object\\]/g) ?? []).length
    })`);
    const layoutObj = JSON.parse(layout);
    check("B21 no horizontal overflow at 1440px",
      layoutObj.bodyScroll <= layoutObj.client, `body=${layoutObj.bodyScroll} client=${layoutObj.client}`);
    check("B22 no undefined/NaN/[object Object] rendered anywhere on the order page",
      layoutObj.placeholders === 0, `hits=${layoutObj.placeholders}`);

    // ---------- B23: runtime hygiene ----------
    check("B23 console errors during the whole run", consoleErrors.length === 0,
      consoleErrors.slice(0, 3).join(" | "));
    check("B24 no HTTP >= 400 response during the whole run", httpErrors.length === 0,
      httpErrors.slice(0, 3).join(" | "));
    check("B25 no failed network request during the whole run", failedRequests.length === 0,
      failedRequests.slice(0, 3).join(" | "));
  } finally {
    try {
      ws?.close();
    } catch {
      // ignore
    }
    child.kill();
  }

  console.log("");
  console.log(`=== C35 summary: PASS=${pass} FAIL=${fail} ===`);
  if (fail > 0) {
    console.log("GATE-BROWSER FAIL");
    process.exit(1);
  }
  console.log("GATE-BROWSER PASS");
}

function textOfConsole(params) {
  return (params.args ?? [])
    .map((arg) => arg.value ?? arg.description ?? arg.preview?.description ?? "")
    .join(" ");
}

async function connectWithRetry(attempts = 40) {
  for (let attempt = 0; attempt < attempts; attempt++) {
    try {
      const response = await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/list`);
      const targets = await response.json();
      const page = targets.find((target) => target.type === "page" && target.webSocketDebuggerUrl);
      if (page) {
        return await openSocket(page.webSocketDebuggerUrl);
      }
    } catch {
      // browser not up yet
    }
    await sleep(250);
  }
  throw new Error("could not attach to the browser via CDP");
}

function openSocket(url) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    socket.addEventListener("open", () => resolve(socket));
    socket.addEventListener("error", (event) => reject(new Error(`websocket error: ${event.message ?? "unknown"}`)));
  });
}

function createClient(socket) {
  let nextId = 0;
  const pending = new Map();
  const handlers = new Map();
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.id !== undefined) {
      const entry = pending.get(message.id);
      if (!entry) {
        return;
      }
      pending.delete(message.id);
      if (message.error) {
        entry.reject(new Error(`${message.error.message} (${JSON.stringify(message.error.data ?? "")})`));
      } else {
        entry.resolve(message.result);
      }
      return;
    }
    const handler = handlers.get(message.method);
    if (handler) {
      handler(message.params ?? {});
    }
  });
  return {
    send(method, params = {}) {
      const id = ++nextId;
      socket.send(JSON.stringify({ id, method, params }));
      return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
    },
    on(method, handler) {
      handlers.set(method, handler);
    },
  };
}

main().catch((error) => {
  console.log(`FATAL ${error.stack ?? error.message}`);
  process.exit(1);
});
