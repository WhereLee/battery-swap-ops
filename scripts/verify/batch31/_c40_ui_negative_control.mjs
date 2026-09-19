/**
 * _c40_ui_negative_control.mjs - batch43: prove the UI gate CAN fail.
 *
 * Why this exists:
 *   Every check in _c38_ui_probe.mjs has only ever been observed GREEN. A gate that has never
 *   been seen red is an assertion about the author's optimism, not about the interface - the
 *   repo already learned this once, when _c35_out.txt claimed 31/31 while the script ran 25
 *   checks (document/pitfalls/check-coverage-lost-in-refactor.md). The project already uses
 *   "red-probe" evidence for two CI gates; this extends the same discipline to the interface
 *   gate.
 *
 * How:
 *   It imports the gate's OWN probe expression (no re-implementation - a control that measures
 *   with different code proves nothing about the gate) and injects one deliberate defect at a
 *   time into the live page, then asserts the matching field reports it:
 *
 *     1. contrast   -> an inline <style> that forces tag text to #eeeeee on its own tint
 *     2. clipping   -> an inline <style> that squeezes a table cell to 40px
 *     3. overflow   -> a 3000px-wide block appended to the body
 *     4. header/body misalignment -> a transform on the header cell
 *     5. an empty measurement scope -> point the probe at a freshly created empty div
 *     6. alert variant contrast (batch43 补) -> the PRE-FIX Element Plus colour on one variant of
 *        the alert matrix, i.e. the exact defect the new matrix check exists to catch
 *     7. validation error contrast (batch43 补) -> the Element Plus danger colour on the form
 *        error message, the defect the new validation-state check was written for
 *
 *   Each defect is expected to be DETECTED. A control that fails to trigger its check is a
 *   FAIL of this script, because it means the gate cannot see that class of defect at all.
 *
 *   6 and 7 also prove the two batch43 checks are not decoration: both of them were added to
 *   close coverage holes, and a check added to close a hole is worth exactly as much as its
 *   ability to fail.
 *
 *   Controls 6/7 need the login form, which an authenticated session cannot reach (the router
 *   guard bounces /login to /dashboard). Their `setup` clears the token key in localStorage - a
 *   change to a throwaway browser profile only, the server never sees a logout - and control 7
 *   signs back in afterwards so the closing sanity check still measures a real page.
 *
 * Read-only with respect to the platform: it only injects styles into the loaded page in a
 * throwaway browser profile, never touches the app source, the build output or the database.
 *
 * Inputs: APP_URL, ADMIN_USER, ADMIN_PASS (same as the gates).
 */
import { launch, login, sleep } from "./_cdp.mjs";
import { probeExpression, TAG_CONTRAST_MIN, alertMatrixExpression, ALERT_MATRIX_SCOPE } from "./_c38_ui_probe.mjs";

const APP_URL = process.env.APP_URL ?? "http://127.0.0.1:4173";
const ADMIN_USER = process.env.ADMIN_USER ?? "admin";
const ADMIN_PASS = process.env.ADMIN_PASS ?? "";

let pass = 0;
let fail = 0;
function check(name, ok, detail = "") {
  if (ok) {
    pass++;
    console.log(`PASS  ${name}${detail ? ` (${detail})` : ""}`);
  } else {
    fail++;
    console.log(`FAIL  ${name}${detail ? ` (${detail})` : ""}`);
  }
}

/**
 * Each control: a page to sit on, a defect to inject, and a predicate over the probe's raw
 * output that must become true BECAUSE of the injection. `baseline` asserts the same predicate
 * is false BEFORE injecting, so a check that is red for unrelated reasons cannot be mistaken
 * for a successful control.
 */
const CONTROLS = [
  {
    name: "C1 tag contrast",
    page: "/work-orders",
    inject: () => {
      const style = document.createElement('style');
      style.id = 'negctl';
      // Force every tag's text to a near-white on its own pale tint: the exact failure mode a
      // reviewer described as "an empty coloured box".
      style.textContent = '.el-tag:not(.el-tag--dark){ --el-tag-text-color: #eeeeee !important; }';
      document.head.appendChild(style);
      return true;
    },
    detected: (raw) => raw.lowContrastTags.length > 0,
    describe: (raw) => `${raw.lowContrastTags.length} tags below ${TAG_CONTRAST_MIN}:1 (worst ${Math.min(...raw.lowContrastTags.map((t) => t.contrast))}:1)`,
  },
  {
    name: "C2 cell clipping",
    page: "/orders",
    inject: () => {
      const style = document.createElement('style');
      style.id = 'negctl';
      // Squeeze the first body column so its text no longer fits. max-width on the inner .cell
      // is how Element Plus itself would clip; this reproduces the shape the probe looks for
      // (scrollWidth > clientWidth on a cell that is not declared show-overflow-tooltip).
      style.textContent = '.el-table__body .el-table__row td:first-child .cell{ max-width: 40px; overflow: hidden; white-space: nowrap; }';
      document.head.appendChild(style);
      return true;
    },
    detected: (raw) => raw.clippedCount > 0,
    describe: (raw) => `${raw.clippedCount} clipped cells`,
  },
  {
    name: "C3 page horizontal overflow",
    page: "/cabinets",
    inject: () => {
      const div = document.createElement('div');
      div.id = 'negctl';
      div.style.width = '3000px';
      div.style.height = '4px';
      document.body.appendChild(div);
      return true;
    },
    detected: (raw) => raw.pageHorizontalOverflow === true,
    describe: (raw) => `pageHorizontalOverflow=${raw.pageHorizontalOverflow}`,
  },
  {
    name: "C4 table header misalignment",
    page: "/settlements",
    inject: () => {
      const style = document.createElement('style');
      style.id = 'negctl';
      // Push the header BOX 30px right of its body column. The first version of this control
      // transformed `th .cell` instead, and the check did not fire - which turned out to be a
      // real finding about the gate rather than about the injection: getBoundingClientRect on
      // the <th> ignores a transform applied to a child, so a header whose TEXT is drawn away
      // from its column was invisible to the check. The probe now compares the inner .cell boxes
      // as well, so both shapes are caught; this injection keeps the layout-level shape.
      style.textContent = '.el-table__header-wrapper th{ transform: translateX(30px); }';
      document.head.appendChild(style);
      return true;
    },
    detected: (raw) => raw.alignment.some((c) =>
      (c.deltaLeft !== null && Math.abs(c.deltaLeft) > 2)
      || (c.deltaCellLeft !== null && Math.abs(c.deltaCellLeft) > 2)),
    describe: (raw) => `${raw.alignment.filter((c) => (c.deltaLeft !== null && Math.abs(c.deltaLeft) > 2) || (c.deltaCellLeft !== null && Math.abs(c.deltaCellLeft) > 2)).length} misaligned columns`,
  },
  {
    name: "C5 empty measurement scope",
    page: "/dashboard",
    // Not a visual defect - a MEASUREMENT defect. The dialog pass resolves its scope by
    // expression, so a wrong expression measures an empty subtree and every count comes back
    // zero, which reads exactly like a clean page. This control points the probe at a freshly
    // created empty div and requires the scope self-check to notice that nothing was measured.
    // Without it the self-check itself would be an untested assertion.
    inject: () => {
      const div = document.createElement('div');
      div.id = 'negctl';
      document.body.appendChild(div);
      return true;
    },
    scopeExpr: "document.getElementById('negctl')",
    detected: (raw) => raw.scopeElements === 0,
    describe: (raw) => `scopeElements=${raw.scopeElements}`,
  },
  {
    name: "C6 alert variant contrast",
    page: "/dashboard",
    // The alert matrix is built by the gate's own exported expression, so this control breaks
    // the real cascade for one variant and asks the real check whether it noticed.
    ready: "!!document.querySelector('.login-card .el-alert')",
    setup: async (session) => {
      await signOutToLoginForm(session);
    },
    prepare: async (session) => {
      const built = await session.evaluate(alertMatrixExpression());
      check("C6 alert variant contrast: the matrix under test was built by the gate's own code", built === 10, `${built} variants`);
    },
    scopeExpr: ALERT_MATRIX_SCOPE,
    inject: () => {
      const style = document.createElement('style');
      style.id = 'negctl';
      // The pre-batch43 pairing: the BASE warning colour as text on its own light-9 tint. This is
      // what Element Plus ships, so the control is not inventing a failure mode - it is putting
      // the original one back.
      style.textContent = '.el-alert--warning.is-light{ color: #e6a23c !important; }';
      document.head.appendChild(style);
      return true;
    },
    detected: (raw) => raw.lowContrastText.length > 0,
    describe: (raw) => `${raw.lowContrastText.length} of ${raw.textScanned} variants below threshold${raw.lowContrastText.length > 0 ? ` (worst ${Math.min(...raw.lowContrastText.map((t) => t.ratio))}:1 "${raw.lowContrastText[0].text}")` : ""}`,
    cleanup: async (session) => {
      await session.evaluate("(() => { const h = document.getElementById('c38-alert-matrix'); if (h) h.remove(); return true; })()");
    },
  },
  {
    name: "C7 validation error contrast",
    page: "/dashboard",
    // Same story as C6, for the other batch43 check: the error message only exists while a rule
    // fails, so the control fails a rule on purpose (which sends no request - the same property
    // the gate asserts) and then puts the original --el-color-danger back.
    ready: "!!document.querySelector('.login-card')",
    setup: async (session) => {
      await signOutToLoginForm(session);
    },
    prepare: async (session) => {
      const shown = await showValidationError(session);
      check("C7 validation error contrast: the error state under test is present", shown);
    },
    scopeExpr: "document.querySelector('.login-card')",
    inject: () => {
      const style = document.createElement('style');
      style.id = 'negctl';
      style.textContent = '.el-form-item__error{ color: #f56c6c !important; }';
      document.head.appendChild(style);
      return true;
    },
    detected: (raw) => raw.lowContrastText.length > 0,
    describe: (raw) => `${raw.lowContrastText.length} of ${raw.textScanned} texts below threshold${raw.lowContrastText.length > 0 ? ` (worst ${Math.min(...raw.lowContrastText.map((t) => t.ratio))}:1 "${raw.lowContrastText[0].text}")` : ""}`,
    after: async (session) => {
      // Leave the browser signed in again: the closing sanity check measures an authenticated page.
      const back = await login(session, APP_URL, ADMIN_USER, ADMIN_PASS);
      check("C7 validation error contrast: signed back in after the login-page controls", back);
    },
  },
];

/** Clear the token key so the router guard lets /login render, then wait for the form. */
async function signOutToLoginForm(session) {
  // "swap.admin.token" is the key src/api/http.ts uses. This touches a throwaway profile only.
  await session.evaluate("(() => { localStorage.removeItem('swap.admin.token'); return true; })()");
  await session.goto(`${APP_URL}/login`, "!!document.querySelector('.login-card')");
  return true;
}

/** Submit the empty login form, which fails validation and renders .el-form-item__error. */
async function showValidationError(session) {
  const already = await session.evaluate("!!document.querySelector('.el-form-item__error')");
  if (already) {
    return true;
  }
  const clicked = await session.evaluate(
    "(() => { const b = document.querySelector('.login-btn'); if (!b) return false; b.click(); return true; })()",
  );
  return clicked && (await session.waitFor("!!document.querySelector('.el-form-item__error')", 5000));
}

async function main() {
  if (!ADMIN_PASS) {
    console.log("FATAL ADMIN_PASS is empty (read .local/admin-pass.txt)");
    process.exit(1);
  }
  console.log("=== C40 batch43: negative control for the UI gate (the gate must be able to fail) ===");
  console.log("INFO  measuring with the gate's own probe expression, imported from _c38_ui_probe.mjs");

  const session = await launch({ width: 1440, height: 900 });
  try {
    const signedIn = await login(session, APP_URL, ADMIN_USER, ADMIN_PASS);
    check("login", signedIn);
    if (!signedIn) {
      process.exit(1);
    }
    await sleep(1200);

    for (const control of CONTROLS) {
      // Baseline: same page, no injection. The predicate must be FALSE here, otherwise the
      // "detection" below would be indistinguishable from a pre-existing defect.
      await session.goto(`${APP_URL}${control.page}`, control.pageReady ?? "!!document.querySelector('.page-title')");
      await sleep(900);
      if (control.setup) {
        // Some states cannot be reached by navigation alone (an authenticated session cannot see
        // the login form). setup runs first and its result is asserted, so a control can never
        // "detect" a defect on a page it silently failed to reach.
        await control.setup(session);
        const reached = await session.waitFor(control.ready ?? control.pageReady ?? "!!document.querySelector('.page-title')", 15000);
        check(`${control.name}: setup reached the state under test`, reached);
        await sleep(600);
      }
      if (control.prepare) {
        // Runs once, before the baseline: creates the state the probe is about to measure.
        await control.prepare(session);
      }
      const before = JSON.parse(await session.evaluate(probeExpression(control.scopeExpr ?? "document")));
      const cleanBefore = control.detected(before) === false;

      const injected = await session.evaluate(`(${control.inject.toString()})()`);
      await sleep(500);
      // C5 measures a deliberately empty scope, so it passes its own scope expression instead
      // of the document; every other control measures the whole page.
      const after = JSON.parse(await session.evaluate(probeExpression(control.scopeExpr ?? "document")));
      const detected = !injected ? false : control.detected(after) === true;

      console.log("");
      console.log(`--- ${control.name} on ${control.page} ---`);
      console.log(`INFO  baseline (no injection): ${control.describe(before)}`);
      console.log(`INFO  after injection:         ${control.describe(after)}`);
      check(`${control.name}: clean page reports nothing`, cleanBefore, control.describe(before));
      check(`${control.name}: injected defect is DETECTED`, detected, control.describe(after));

      // Clean up so the next control starts from a clean page.
      await session.evaluate("(() => { const n = document.getElementById('negctl'); if (n) n.remove(); return true; })()");
      if (control.cleanup) {
        await control.cleanup(session);
      }
      await sleep(300);
      if (control.after) {
        await control.after(session);
      }
    }

    // Final sanity: after every injection is removed, the page must measure clean again -
    // otherwise the control proves the probe latches rather than that it reacts.
    await session.goto(`${APP_URL}${CONTROLS[0].page}`, "!!document.querySelector('.page-title')");
    await sleep(900);
    const restored = JSON.parse(await session.evaluate(probeExpression("document")));
    check("after removing all injections the page measures clean again", restored.lowContrastTags.length === 0 && restored.clippedCount === 0,
      `lowContrastTags=${restored.lowContrastTags.length} clipped=${restored.clippedCount}`);

    console.log("");
    console.log(`=== C40 summary: PASS=${pass} FAIL=${fail} ===`);
    console.log(fail === 0 ? "GATE-NEGCTL PASS" : "GATE-NEGCTL FAIL");
    process.exitCode = fail === 0 ? 0 : 1;
  } finally {
    session.close();
  }
}

main().catch((error) => {
  console.log(`FATAL ${error.stack ?? error.message}`);
  process.exit(1);
});
