/**
 * _cdp.mjs - tiny shared Chrome DevTools Protocol client (batch31/batch38).
 *
 * Extracted from _c35_console.mjs so the browser gates share one launcher instead of
 * copy-pasting 80 lines of WebSocket plumbing. Zero dependencies on purpose: Node 22's
 * built-in WebSocket drives the locally installed Chrome/Edge, so nothing has to be
 * installed and nothing has to track the browser version.
 */

import { spawn } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Browser-side measurement of "how tall is this page really", plus how much of the window the
 * shell chrome (header, breadcrumb, padding) consumes. Used by full-page capture, which has
 * to expand the emulated viewport for a layout that scrolls inside the window rather than in
 * the document - see the note in `screenshot`.
 */
const MEASURE_CONTENT_HEIGHT = `(() => {
  const scrollers = Array.from(document.querySelectorAll('*')).filter((el) => {
    if (el.clientHeight < 100 || el.scrollHeight <= el.clientHeight + 4) return false;
    const overflowY = getComputedStyle(el).overflowY;
    return overflowY === 'auto' || overflowY === 'scroll';
  }).sort((a, b) => b.scrollHeight - a.scrollHeight);
  const tallest = scrollers[0];
  return JSON.stringify({
    contentHeight: Math.max(
      document.documentElement.scrollHeight,
      document.body ? document.body.scrollHeight : 0,
      ...scrollers.map((el) => el.scrollHeight),
      0,
    ),
    scrollerClientHeight: tallest ? tallest.clientHeight : 0,
  });
})()`;

export function findBrowser() {
  const candidates = [
    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
  ];
  return candidates.find((candidate) => existsSync(candidate)) ?? null;
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
        entry.reject(new Error(`${message.error.message}`));
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

async function connect(port, attempts = 40) {
  for (let attempt = 0; attempt < attempts; attempt++) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/list`);
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

/**
 * Launch a headless browser, attach, and return a session object with the helpers the
 * gates need. `width`/`height` are applied through Emulation.setDeviceMetricsOverride so
 * the rendered viewport is EXACTLY the requested size (a plain --window-size is an outer
 * window size: in headless it yields ~1414x800 after the scrollbar, which silently made
 * every screenshot narrower and shorter than intended).
 */
export async function launch({ width = 1440, height = 900, port = 9333, ignoreCertErrors = false } = {}) {
  const browser = findBrowser();
  if (!browser) {
    throw new Error("no Chrome/Edge found for the browser run");
  }
  const profile = mkdtempSync(join(tmpdir(), "swap-cdp-"));
  const child = spawn(browser, [
    "--headless=new",
    "--disable-gpu",
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-extensions",
    // 自签证书的站点（云端 DSH Web 是其一）会在无头模式停在证书警告页，DOM 里只有
    // "您的连接不是私密连接"，所有断言都会莫名其妙地失败。默认保持关闭（探针跑的是
    // 本地 http），只有明确要求的调用才忽略证书错误。
    ...(ignoreCertErrors ? ["--ignore-certificate-errors"] : []),
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profile}`,
    "about:blank",
  ], { stdio: "ignore" });

  const socket = await connect(port);
  const cdp = createClient(socket);
  await cdp.send("Page.enable");
  await cdp.send("Runtime.enable");
  await cdp.send("Network.enable");
  await cdp.send("Log.enable");
  await cdp.send("Emulation.setDeviceMetricsOverride", {
    width,
    height,
    deviceScaleFactor: 1,
    mobile: false,
  });
  // Remembered so a full-page capture can expand the viewport and then restore it.
  const viewport = { width, height };

  const session = {
    cdp,
    browserPath: browser,
    async evaluate(expression) {
      const result = await cdp.send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
      if (result.exceptionDetails) {
        throw new Error(result.exceptionDetails.exception?.description ?? "evaluate failed");
      }
      return result.result.value;
    },
    async waitFor(expression, timeoutMs = 15000) {
      // Loud on purpose. The batch38 refactor handed a human label ("work-order detail")
      // to this parameter, so `deadline = Date.now() + "work-order detail"` became NaN,
      // `Date.now() < NaN` was false, and the helper returned false on the FIRST poll -
      // nine real checks went red in ~0ms and looked exactly like an application defect.
      // A wrong argument type must never be able to masquerade as a failed assertion.
      if (typeof timeoutMs !== "number" || !Number.isFinite(timeoutMs) || timeoutMs <= 0) {
        throw new Error(
          `waitFor(timeoutMs) must be a positive finite number, got ${typeof timeoutMs} ${String(timeoutMs)}`,
        );
      }
      const deadline = Date.now() + timeoutMs;
      while (Date.now() < deadline) {
        try {
          if (await session.evaluate(expression)) {
            return true;
          }
        } catch {
          // navigation tears the context down; retry until the deadline
        }
        await sleep(200);
      }
      return false;
    },
    async goto(url, readyExpression) {
      await cdp.send("Page.navigate", { url });
      return readyExpression ? session.waitFor(readyExpression) : true;
    },
    async screenshot(path, { fullPage = false } = {}) {
      const params = { format: "png" };
      let captured = { width: viewport.width, height: viewport.height };
      if (fullPage) {
        // "Full page" is NOT simply captureBeyondViewport here. The console shell is a
        // full-height Element Plus layout whose content scrolls INSIDE <el-main>, so
        // document.documentElement.scrollHeight stays pinned to the viewport height and the
        // first version of this helper captured exactly one screen while reporting
        // "(full page)" - the settlement detail ledger has 48 rows and its screenshot stopped
        // at row 12. Grow the emulated viewport to the tallest content box, let the
        // 100%-height layout lay out to it, capture, then put the viewport back so later
        // measurements still see the documented 1440x900.
        const contentHeight = await session.evaluate(MEASURE_CONTENT_HEIGHT);
        // Growing to the content height alone is not enough: the shell header keeps its own
        // height, so `main` becomes (viewport - header) tall and its last rows stay hidden.
        // Solve for the chrome and iterate to a fixed point instead of guessing once.
        let target = viewport.height;
        for (let attempt = 0; attempt < 4; attempt++) {
          const probe = JSON.parse(attempt === 0 ? contentHeight : await session.evaluate(MEASURE_CONTENT_HEIGHT));
          const chrome = probe.scrollerClientHeight > 0 ? target - probe.scrollerClientHeight : 0;
          const need = Math.max(probe.contentHeight + chrome, viewport.height);
          if (need <= target) {
            break;
          }
          target = Math.min(need, 20000);
          await cdp.send("Emulation.setDeviceMetricsOverride", {
            width: viewport.width,
            height: target,
            deviceScaleFactor: 1,
            mobile: false,
          });
          await sleep(400); // let layout settle at the new height before measuring again
        }
        const metrics = await cdp.send("Page.getLayoutMetrics");
        const size = metrics.cssContentSize ?? metrics.contentSize;
        captured = { width: Math.ceil(size.width), height: Math.ceil(size.height) };
        params.clip = { x: 0, y: 0, width: captured.width, height: captured.height, scale: 1 };
        params.captureBeyondViewport = true;
      }
      const { data } = await cdp.send("Page.captureScreenshot", params);
      if (fullPage) {
        await cdp.send("Emulation.setDeviceMetricsOverride", {
          width: viewport.width,
          height: viewport.height,
          deviceScaleFactor: 1,
          mobile: false,
        });
        await sleep(200);
      }
      if (path) {
        mkdirSync(join(path, ".."), { recursive: true });
        writeFileSync(path, Buffer.from(data, "base64"));
      }
      return { ...captured, bytes: Buffer.from(data, "base64").length };
    },
    close() {
      try {
        socket.close();
      } catch {
        // ignore
      }
      child.kill();
    },
  };
  return session;
}

/** Minimal console/network error collector used by both gates. */
export function collectErrors(session) {
  const consoleErrors = [];
  const httpErrors = [];
  const failedRequests = [];
  session.cdp.on("Runtime.consoleAPICalled", (params) => {
    if (params.type === "error") {
      consoleErrors.push((params.args ?? []).map((a) => a.value ?? a.description ?? "").join(" "));
    }
  });
  session.cdp.on("Runtime.exceptionThrown", (params) => {
    consoleErrors.push(params.exceptionDetails?.exception?.description ?? "uncaught exception");
  });
  session.cdp.on("Log.entryAdded", (params) => {
    const entry = params.entry ?? {};
    if (entry.level === "error" && !String(entry.url ?? "").includes("favicon")) {
      consoleErrors.push(`${entry.source}: ${entry.text}`);
    }
  });
  session.cdp.on("Network.responseReceived", (params) => {
    if (params.response.status >= 400 && !String(params.response.url).includes("favicon")) {
      httpErrors.push(`${params.response.status} ${params.response.url}`);
    }
  });
  session.cdp.on("Network.loadingFailed", (params) => {
    if (!params.canceled) {
      failedRequests.push(params.errorText);
    }
  });
  return { consoleErrors, httpErrors, failedRequests };
}

/** Sign in through the real login form (same selectors _c35 uses). */
export async function login(session, appUrl, user, password) {
  // Only navigate when we are not already sitting on the form. The route guard sends an
  // unauthenticated deep link to `/login?redirect=/work-orders`, and a blind
  // Page.navigate to a bare `/login` throws that query away - the app then correctly lands
  // on the default page and the deep-link round trip (B20) can never be observed.
  const alreadyOnForm = await session
    .evaluate("location.pathname === '/login' && !!document.querySelector('.login-btn')")
    .catch(() => false);
  if (!alreadyOnForm) {
    await session.goto(`${appUrl}/login`, "!!document.querySelector('.login-btn')");
  }
  await session.evaluate(`(() => {
    const setValue = (selector, value) => {
      const el = document.querySelector(selector);
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      setter.call(el, value);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    };
    setValue('input[autocomplete="username"]', ${JSON.stringify(user)});
    setValue('input[autocomplete="current-password"]', ${JSON.stringify(password)});
    document.querySelector('.login-btn').click();
    return true;
  })()`);
  return session.waitFor("location.pathname !== '/login'", 20000);
}
