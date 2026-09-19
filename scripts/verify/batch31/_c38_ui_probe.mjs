/**
 * _c38_ui_probe.mjs - batch38: instrumented layout/contrast probe over the console pages.
 *
 * Why: a vision-model review of the batch31 screenshots reported "操作列被滚动条压住"、
 * "标签列渲染成空白方块"、"守恒校验是一块认不出字的色块" 等条目。这类抱怨有两种可能，
 * 必须先分辨再动手：
 *   (a) 真实缺陷（表格横向溢出、标签文字被挤没、文字与背景对比度不足）；
 *   (b) 截图与识别条件造成的假阳性（截图视口只有 1414x800、模型对小字号彩色文字识别弱）。
 * 靠"再看一眼图"分辨不了，只能量 DOM。
 *
 * 本脚本逐页测量并打印：
 *   1. 页面级横向溢出（body.scrollWidth vs clientWidth）
 *   2. 每个 el-table 的内部横向滚动（滚动容器 scrollWidth vs clientWidth）+ 需要滚动的列数
 *   3. 表头/单元格文字被裁切的数量（.cell scrollWidth > clientWidth）
 *   4. 每个 el-tag 的文字、渲染宽高、前景/背景色与**对比度**（判定"空白方块"是否成立）
 *   5. 视口/文档尺寸（确认截图尺寸口径）
 * 输出为机器可读的 JSON 摘要 + 人读行；不改任何东西，只出事实。
 */

import { launch, collectErrors, login, sleep } from "./_cdp.mjs";
import { pathToFileURL } from "node:url";

const APP_URL = process.env.APP_URL ?? "http://127.0.0.1:4173";
const ADMIN_USER = process.env.ADMIN_USER ?? "admin";
const ADMIN_PASS = process.env.ADMIN_PASS ?? "";
const VIEWPORT_WIDTH = Number(process.env.PROBE_WIDTH ?? 1440);
const VIEWPORT_HEIGHT = Number(process.env.PROBE_HEIGHT ?? 900);

/**
 * WCAG 1.4.3 AA for normal-size text. The tags render at 12px, so 4.5:1 applies; a lenient
 * 3:1 gate would let the original Element Plus colours (2.04-2.80:1) look acceptable.
 * Defined once here and injected into the browser snippet so the gate condition and the
 * printed label can never disagree.
 */
const TAG_CONTRAST_MIN = 4.5;

/*
 * Interaction states (batch43 补): hover / focus-visible / disabled were the last declared gap of
 * this gate ("only the resting state is measured"). A static sweep can never see them: the styles
 * that apply are selected by a pseudo-class the page is not currently in, so the measurement has
 * to CONSTRUCT the state first - the same discipline as the validation pass (fail a rule to see
 * the error text). Chrome DevTools Protocol can force a pseudo-class on a specific node
 * (CSS.forcePseudoState), which is deterministic and does not depend on hit-testing or scrolling.
 *
 * Two things are asserted, not one:
 *   1. the state actually applied - the computed style of the element must CHANGE when the
 *      pseudo-class is forced. Without this, a silently broken forcePseudoState (wrong node id,
 *      domain not enabled) would make every measurement "clean" and the gate would report success
 *      for a state it never entered. Same shape as the validation pass asserting 0 login POSTs.
 *   2. the text inside the hovered element still meets its WCAG threshold.
 */
const HOVER_SELECTOR = ".el-button, .el-link, .el-tabs__item";
const HOVER_LIMIT = 14;
const HOVER_PAGES = ["/work-orders", "/orders", "/alarm"];

/** Tag up to `limit` visible, non-disabled interactive elements so CDP can address them. */
function tagHoverCandidates(selector, limit) {
  return `(() => {
    document.querySelectorAll('[data-c38-hover]').forEach((el) => el.removeAttribute('data-c38-hover'));
    const all = [...document.querySelectorAll(${JSON.stringify(selector)})].filter(
      (el) => el.getClientRects().length > 0
        && !el.hasAttribute('disabled')
        && !el.classList.contains('is-disabled')
        && !el.classList.contains('is-loading'),
    );
    const picked = all.slice(0, ${limit});
    picked.forEach((el, i) => el.setAttribute('data-c38-hover', String(i)));
    return JSON.stringify({
      total: all.length,
      picked: picked.length,
      labels: picked.map((el) => (el.innerText || el.getAttribute('aria-label') || el.className || '').trim().replace(/\\s+/g, ' ').slice(0, 24)),
    });
  })()`;
}

/** The computed values a pseudo-class is expected to change. */
function styleFingerprint(selector) {
  return `(() => {
    const el = document.querySelector(${JSON.stringify(selector)});
    if (!el) return '';
    const s = getComputedStyle(el);
    return [s.color, s.backgroundColor, s.borderColor, s.boxShadow, s.outlineColor, s.outlineWidth].join(' | ');
  })()`;
}

/**
 * Force `:hover` on each tagged element, prove the style changed, and measure the text inside it
 * with the gate's own probe expression. Exported so the negative control drives the SAME code.
 */
async function measureHoverStates(session, { selector = HOVER_SELECTOR, limit = HOVER_LIMIT, label = "interaction" } = {}) {
  const info = JSON.parse(await session.evaluate(tagHoverCandidates(selector, limit)));
  const result = { label, total: info.total, picked: info.picked, applied: 0, low: [], samples: [], notApplied: [] };
  if (info.picked === 0) {
    return result;
  }
  await session.cdp.send("DOM.enable");
  await session.cdp.send("CSS.enable");
  const { root } = await session.cdp.send("DOM.getDocument", { depth: 1, pierce: false });
  const { nodeIds } = await session.cdp.send("DOM.querySelectorAll", { nodeId: root.nodeId, selector: "[data-c38-hover]" });
  for (let i = 0; i < info.picked; i++) {
    const scope = `[data-c38-hover="${i}"]`;
    // probeExpression takes a JS EXPRESSION that evaluates to the root element, while
    // styleFingerprint takes a selector - passing the bare attribute selector to the former
    // produced `const root = [data-c38-hover="0"]`, i.e. an array literal with an assignment
    // inside it (SyntaxError: Invalid left-hand side in assignment). Two different string shapes,
    // named differently on purpose.
    const scopeExpr = `document.querySelector('[data-c38-hover="${i}"]')`;
    const nodeId = nodeIds[i];
    if (nodeId === undefined) {
      result.notApplied.push({ i, label: info.labels[i], why: "no CDP node id" });
      continue;
    }
    const before = await session.evaluate(styleFingerprint(scope));
    await session.cdp.send("CSS.forcePseudoState", { nodeId, forcedPseudoClasses: ["hover"] });
    const after = await session.evaluate(styleFingerprint(scope));
    if (after !== before) {
      result.applied++;
      result.samples.push({ label: info.labels[i], before, after });
    } else {
      result.notApplied.push({ i, label: info.labels[i], why: "style did not change under :hover" });
    }
    const raw = JSON.parse(await session.evaluate(probeExpression(scopeExpr)));
    raw.lowContrastText.forEach((t) => result.low.push({ ...t, element: info.labels[i] }));
    if (raw.worstText) {
      result.samples.push({ label: info.labels[i], worstText: raw.worstText });
    }
    await session.cdp.send("CSS.forcePseudoState", { nodeId, forcedPseudoClasses: [] });
  }
  await session.evaluate("(() => { document.querySelectorAll('[data-c38-hover]').forEach((el) => el.removeAttribute('data-c38-hover')); return true; })()");
  return result;
}

/**
 * Routes to measure. `clickFrom` marks a detail page and names the list it hangs off:
 * the probe clicks the first row link exactly like a user and reads back location.pathname.
 *
 * Why not just hard-code the detail URLs: for work orders and settlements the list link
 * routes on `row.id` while DISPLAYING woNo/statementNo, so the visible text is not the
 * route parameter and cannot be used to build a URL. Clicking is the one discovery method
 * that works for all four lists, and it also proves the detail pages are reachable by
 * click rather than only by deep link.
 *
 * Coverage note: the batch38 review pointed out that the first version measured 7 routes
 * and left order/cabinet/settlement detail - the pages carrying the money path and the
 * 48-line split ledger - entirely unmeasured. They are in this list now. /login is
 * deliberately absent: it has no table and no tag, so there is nothing here to measure;
 * its render and viewport are covered by _c35 B01/B21.
 */
const PAGES = [
  { path: "/dashboard", title: "运营看板" },
  { path: "/alarm", title: "告警与建议单" },
  { path: "/work-orders", title: "工单列表" },
  { path: "/work-orders/12", title: "工单详情", clickFrom: "/work-orders" },
  { path: "/orders", title: "换电订单列表" },
  { path: "/orders/x", title: "换电订单详情", clickFrom: "/orders" },
  { path: "/cabinets", title: "换电柜列表" },
  { path: "/cabinets/x", title: "换电柜详情", clickFrom: "/cabinets" },
  { path: "/settlements", title: "结算单列表" },
  { path: "/settlements/x", title: "结算单详情", clickFrom: "/settlements" },
];

/**
 * Runs in the browser: returns raw measurements for one scope.
 *
 * `scopeExpr` is a JS expression evaluated in the page that must yield the element to measure.
 * The default is the whole document; the dialog pass passes an expression that resolves the
 * visible `.el-dialog`, because Element Plus teleports dialogs to <body> and keeps the page
 * behind them rendered - measuring the document with a dialog open would double-count the
 * page and hide which numbers came from the dialog.
 */
function probeExpression(scopeExpr = "document") {
  return `(() => {
  const root = ${scopeExpr};
  const scopeIsPage = root === document;
  if (!root) return JSON.stringify({ scopeMissing: true });
  const px = (v) => Math.round(Number.parseFloat(v || '0')) || 0;
  const parseRGB = (c) => {
    const m = String(c).match(/rgba?\\(([^)]+)\\)/);
    if (!m) return null;
    const p = m[1].split(',').map((s) => Number.parseFloat(s));
    return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
  };
  const lum = ({ r, g, b }) => {
    const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
  };
  const contrast = (fg, bg) => {
    if (!fg || !bg) return null;
    const l1 = lum(fg), l2 = lum(bg);
    const [hi, lo] = l1 >= l2 ? [l1, l2] : [l2, l1];
    return Math.round(((hi + 0.05) / (lo + 0.05)) * 100) / 100;
  };
  // Effective background: walk up until a non-transparent colour is found.
  const effectiveBg = (el) => {
    let node = el;
    while (node && node !== document.documentElement) {
      const bg = parseRGB(getComputedStyle(node).backgroundColor);
      if (bg && bg.a > 0.01) return bg;
      node = node.parentElement;
    }
    return parseRGB(getComputedStyle(document.body).backgroundColor) || { r: 255, g: 255, b: 255, a: 1 };
  };

  const clipped = [];
  const intentionalEllipsis = [];
  root.querySelectorAll('.el-table .cell').forEach((cell) => {
    const text = (cell.innerText || '').trim();
    if (!text) return;
    if (cell.scrollWidth <= cell.clientWidth + 1) return;
    const entry = { text: text.slice(0, 40), scrollWidth: cell.scrollWidth, clientWidth: cell.clientWidth };
    // Element Plus marks columns declared with show-overflow-tooltip by adding .el-tooltip
    // to the cell: those are ellipsised ON PURPOSE (hover shows the full value), so they must
    // not be counted as defects - otherwise the probe drowns real clipping in noise.
    if (cell.classList.contains('el-tooltip')) {
      intentionalEllipsis.push(entry);
    } else {
      clipped.push(entry);
    }
  });

  const tables = Array.from(root.querySelectorAll('.el-table')).map((table, index) => {
    const wrapper = table.querySelector('.el-table__body-wrapper') || table;
    return {
      index,
      columns: table.querySelectorAll('.el-table__header th').length,
      rows: table.querySelectorAll('.el-table__body tbody tr').length,
      wrapperScrollWidth: wrapper.scrollWidth,
      wrapperClientWidth: wrapper.clientWidth,
      horizontallyScrollable: wrapper.scrollWidth > wrapper.clientWidth + 1,
    };
  });

  // ---- content height: the console scrolls INSIDE the window -------------------------
  // The shell is a full-height Element Plus layout, so <el-main> (or whatever the app marks
  // scrollable) is the real scroller and document.documentElement.scrollHeight stays pinned
  // to the viewport height no matter how long the page is. Consequence: a naive full-page
  // capture silently captures one screen - the settlement detail ledger has 48 rows and the
  // first "full page" screenshot stopped at row 12, which a reviewer correctly reported as
  // content missing from the image. Measure the real scroller so the capture logic, and the
  // record, are both held to the true content height instead of to documentElement.
  const scrollers = [];
  root.querySelectorAll('*').forEach((el) => {
    if (el.clientHeight < 100 || el.scrollHeight <= el.clientHeight + 4) return;
    const overflowY = getComputedStyle(el).overflowY;
    if (overflowY !== 'auto' && overflowY !== 'scroll') return;
    scrollers.push({
      tag: el.tagName.toLowerCase(),
      cls: String(el.className || '').slice(0, 60),
      scrollHeight: el.scrollHeight,
      clientHeight: el.clientHeight,
    });
  });
  const contentHeight = Math.max(
    document.documentElement.scrollHeight,
    document.body ? document.body.scrollHeight : 0,
    ...scrollers.map((s) => s.scrollHeight),
    0,
  );

  // ---- header vs body alignment ------------------------------------------------------
  // Two review rounds claimed the header row was "shifted left by one column" (orders) and a
  // header was "truncated mid-word" (settlements). The clipping check cannot see either: a
  // header can sit over the wrong body column while every individual cell fits. Measure the
  // box of each header cell against the same index in the first body row. This matters for
  // Element Plus specifically because a fixed="right" column is painted with position:sticky
  // and can look displaced in a captured image even when the DOM is perfectly aligned - the
  // kind of claim that has to be settled with numbers, not with another look at the picture.
  const alignment = [];
  root.querySelectorAll('.el-table').forEach((table, tableIndex) => {
    const headers = Array.from(table.querySelectorAll('.el-table__header th'));
    const firstRow = table.querySelector('.el-table__body tbody tr');
    const bodyCells = firstRow ? Array.from(firstRow.children) : [];
    headers.forEach((th, index) => {
      const cell = th.querySelector('.cell');
      const headerBox = th.getBoundingClientRect();
      const bodyCell = bodyCells[index] ?? null;
      const bodyBox = bodyCell ? bodyCell.getBoundingClientRect() : null;
      // Two levels, on purpose. The cell-level boxes catch a header whose text is drawn away
      // from its own body column even when the <th>/<td> boxes still line up - a CSS transform
      // on the inner .cell moves the glyphs without touching layout, which is exactly how the
      // batch43 negative control exposed that the first version of this check could be fooled.
      const bodyCellInner = bodyCell ? bodyCell.querySelector('.cell') : null;
      const cellInnerBox = cell ? cell.getBoundingClientRect() : null;
      const bodyCellInnerBox = bodyCellInner ? bodyCellInner.getBoundingClientRect() : null;
      alignment.push({
        table: tableIndex,
        index,
        label: (th.innerText || '').trim().slice(0, 24),
        deltaLeft: bodyBox ? Math.round(bodyBox.left - headerBox.left) : null,
        deltaWidth: bodyBox ? Math.round(bodyBox.width - headerBox.width) : null,
        deltaCellLeft: cellInnerBox && bodyCellInnerBox
          ? Math.round(bodyCellInnerBox.left - cellInnerBox.left)
          : null,
        headerTextClipped: cell ? cell.scrollWidth > cell.clientWidth + 1 : false,
        headerTextHeight: cell ? Math.round(cell.getBoundingClientRect().height) : null,
      });
    });
  });

  // ---- columns that render nothing ---------------------------------------------------
  // "The 状态 column is empty and every row shows a bare dash" was another review claim.
  // It may be a legitimate null (关联工单 is null for most alarms) or a mis-mapped field, and
  // the difference is not visible in a picture: report the measurement, do not guess.
  //
  // innerText vs textContent is the part that makes this decisive. innerText only returns
  // text the browser actually lays out, so a cell with innerText '-' but textContent '100%'
  // means the value IS bound but something hides it (a progress bar's label inside a
  // zero-width flex item, for instance) - a real defect that a reader would describe as "the
  // SOC column is empty" while a source-code reading would call it correct. Reporting both,
  // plus the child-element count, keeps "no data" and "data that cannot be seen" apart.
  const dashColumns = [];
  root.querySelectorAll('.el-table').forEach((table, tableIndex) => {
    const rows = Array.from(table.querySelectorAll('.el-table__body tbody tr'));
    if (rows.length === 0) return;
    Array.from(table.querySelectorAll('.el-table__header th')).forEach((th, index) => {
      const cells = rows.map((row) => {
        const cell = row.children[index];
        if (!cell) return { inner: '', text: '', elements: 0 };
        return {
          inner: (cell.innerText || '').trim(),
          text: (cell.textContent || '').trim(),
          elements: cell.querySelectorAll('*').length,
        };
      });
      // Element Plus renders type="expand"/"selection" columns with an empty header and an
      // icon-only cell; that is the component's design, not an unlabelled data column, so it
      // must not be reported as suspicious.
      if ((th.innerText || '').trim() === '' && cells.every((c) => c.elements > 0)) return;
      const blankVisible = cells.filter((c) => c.inner === '' || c.inner === '-').length;
      if (blankVisible !== rows.length) return;
      dashColumns.push({
        table: tableIndex,
        index,
        label: (th.innerText || '').trim().slice(0, 24),
        rows: rows.length,
        domTextFilled: cells.filter((c) => c.text !== '' && c.text !== '-').length,
        domTextSample: cells[0].text.slice(0, 40),
        childElements: cells[0].elements,
      });
    });
  });

  // ---- text contrast beyond tags ------------------------------------------------------
  // Declared boundary of the first version: only .el-tag was measured. But "I cannot read
  // this" applies to every small grey label in the console, and Element Plus ships
  // --el-text-color-secondary (#909399) and --el-text-color-placeholder, which sit around
  // 2.8-3.4:1 on white. Walk leaf text elements rather than a hand-written selector list so a
  // component nobody thought of is covered too, and apply the WCAG 1.4.3 split properly:
  // 3:1 is allowed only for LARGE text (>=24px, or >=18.66px AND bold), everything else 4.5:1.
  // Disabled controls and aria-hidden decoration are exempt by the same rule, so they are
  // skipped instead of being counted as failures.
  const TEXT_LIMIT = 800;
  const textSamples = [];
  let textScanned = 0;
  const textSeen = new Set();
  // Use 'body *' for the page (skips <head>) and '*' when the scope IS an element: a dialog
  // has no <body> descendant, so querying 'body *' inside one silently returns nothing.
  // NOTE: never write backticks in this comment block - the whole probe is a template literal.
  const textRoot = scopeIsPage ? document.body : root;
  for (const el of textRoot.querySelectorAll('*')) {
    if (textSamples.length >= TEXT_LIMIT) break;
    if (el.closest('.is-disabled, [disabled], [aria-hidden="true"]')) continue;
    if (el.getClientRects().length === 0) continue;
    let ownsText = false;
    for (const node of el.childNodes) {
      if (node.nodeType === 3 && node.textContent.trim() !== '') {
        ownsText = true;
        break;
      }
    }
    if (!ownsText) continue;
    const style = getComputedStyle(el);
    const fontSize = Number.parseFloat(style.fontSize) || 0;
    const fontWeight = Number.parseInt(style.fontWeight, 10) || 400;
    const need = fontSize >= 24 || (fontSize >= 18.66 && fontWeight >= 700) ? 3 : 4.5;
    const fg = parseRGB(style.color);
    const bg = effectiveBg(el);
    const ratio = contrast(fg, bg);
    if (ratio === null) continue;
    textScanned++;
    const cls = typeof el.className === 'string' && el.className.trim() !== ''
      ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.')
      : '';
    const sample = {
      text: (el.innerText || '').trim().slice(0, 24),
      sel: el.tagName.toLowerCase() + cls,
      fontSize: style.fontSize,
      color: style.color,
      background: 'rgb(' + bg.r + ',' + bg.g + ',' + bg.b + ')',
      ratio,
      need,
    };
    // One entry per (selector, colour) pair is enough to act on; without this the report is
    // 400 copies of the same table-cell rule.
    const key = sample.sel + '|' + sample.color + '|' + sample.need;
    if (textSeen.has(key)) continue;
    textSeen.add(key);
    textSamples.push(sample);
  }
  // ---- placeholder text -----------------------------------------------------------------
  // Element Plus renders a select's placeholder as a real element (.el-select__placeholder,
  // already covered by the walk above) but an input's as a ::placeholder pseudo-element, which
  // querySelectorAll can never return. Both are text under WCAG 1.4.3, so measure the pseudo
  // explicitly via getComputedStyle(el, '::placeholder'). Batch39 fixed
  // --el-text-color-secondary but left --el-text-color-placeholder alone; this is where that
  // gap shows up, and it only showed up at all once the probe started opening dialogs (a form
  // is where placeholders live).
  //
  // These samples must be pushed BEFORE lowContrastText is computed: the first version added
  // them after, so page-level placeholder failures were measured, reported as their own field,
  // and then silently left out of the gate. A measurement that does not reach the gate is a
  // comment, not a check.
  const placeholderSamples = [];
  root.querySelectorAll('input[placeholder], textarea[placeholder]').forEach((el) => {
    if (el.getClientRects().length === 0) return;
    const pseudo = getComputedStyle(el, '::placeholder');
    const fg = parseRGB(pseudo.color);
    const bg = effectiveBg(el);
    const ratio = contrast(fg, bg);
    if (ratio === null) return;
    const key = '::placeholder|' + pseudo.color;
    if (textSeen.has(key)) return;
    textSeen.add(key);
    const sample = {
      text: '::placeholder "' + String(el.getAttribute('placeholder') || '').slice(0, 16) + '"',
      sel: el.tagName.toLowerCase() + '::placeholder',
      fontSize: pseudo.fontSize,
      color: pseudo.color,
      background: 'rgb(' + bg.r + ',' + bg.g + ',' + bg.b + ')',
      ratio,
      need: 4.5,
    };
    textSamples.push(sample);
    placeholderSamples.push(sample);
  });

  const lowContrastText = textSamples.filter((t) => t.ratio < t.need);
  // "0 below threshold" is only meaningful next to HOW FAR the closest call was - the same
  // reasoning as scopeElements: a count of problems says nothing unless you also know what was
  // measured. worstText is the lowest ratio among everything measured, so a page that passes by
  // a hair is visible in the report instead of being indistinguishable from one with headroom.
  const worstText = textSamples.reduce((worst, t) => (worst === null || t.ratio < worst.ratio ? t : worst), null);
  const isPlaceholder = (t) => t.sel.endsWith('::placeholder') || t.sel.includes('select__placeholder');
  const lowContrastPlaceholders = lowContrastText.filter(isPlaceholder);

  const tags = [];
  root.querySelectorAll('.el-tag').forEach((tag) => {
    // Inactive el-tabs panes are still in the DOM with display:none; measuring them yields
    // 0x0 boxes that are not rendering defects at all.
    if (tag.getClientRects().length === 0) return;
    const rect = tag.getBoundingClientRect();
    const style = getComputedStyle(tag);
    const fg = parseRGB(style.color);
    const bg = effectiveBg(tag);
    tags.push({
      text: (tag.innerText || '').trim(),
      width: Math.round(rect.width),
      height: Math.round(rect.height),
      fontSize: style.fontSize,
      color: style.color,
      background: 'rgb(' + bg.r + ',' + bg.g + ',' + bg.b + ')',
      contrast: contrast(fg, bg),
      scrollWidth: tag.scrollWidth,
      clientWidth: tag.clientWidth,
    });
  });

  // ---- non-text contrast (WCAG 1.4.11) - reported, NOT gated --------------------------
  // A UI component's boundary must reach 3:1 against the adjacent colour. Whether the rule
  // applies to an Element Plus text input is a judgement call - the input is also identified
  // by its label and its placement, and the stock border (--el-border-color #dcdfe6 on white
  // is ~1.4:1) is a deliberate design choice across the whole component library. Fixing it
  // means restyling every form control, which is a visual-design decision rather than a
  // defect fix, so this section measures and reports the worst offenders and does NOT fail
  // the gate. Recorded as a declared boundary in the block record instead.
  const nonText = [];
  const seenNonText = new Set();
  root.querySelectorAll('.el-button, .el-input__wrapper, .el-textarea__inner, .el-select__wrapper, .el-checkbox__inner, .el-radio__inner, .el-table__cell, .el-card').forEach((el) => {
    if (el.closest('.is-disabled, [disabled], [aria-hidden="true"]')) return;
    if (el.getClientRects().length === 0) return;
    const style = getComputedStyle(el);
    const sides = ['borderTopColor', 'borderRightColor', 'borderBottomColor', 'borderLeftColor'];
    const border = sides.map((s) => parseRGB(style[s])).find((c) => c && c.a > 0.01);
    if (!border) return;
    const widths = [style.borderTopWidth, style.borderRightWidth, style.borderBottomWidth, style.borderLeftWidth];
    if (!widths.some((w) => (Number.parseFloat(w) || 0) > 0)) return;
    // Compare the boundary against what is OUTSIDE the component, which is what the eye uses.
    const outside = el.parentElement ? effectiveBg(el.parentElement) : effectiveBg(el);
    const ratio = contrast(border, outside);
    if (ratio === null) return;
    const cls = typeof el.className === 'string' ? el.className.trim().split(/\s+/).slice(0, 2).join('.') : '';
    const key = el.tagName.toLowerCase() + '.' + cls + '|' + style.borderTopColor;
    if (seenNonText.has(key)) return;
    seenNonText.add(key);
    nonText.push({
      sel: el.tagName.toLowerCase() + '.' + cls,
      text: (el.innerText || '').trim().slice(0, 20),
      border: style.borderTopColor,
      outside: 'rgb(' + outside.r + ',' + outside.g + ',' + outside.b + ')',
      ratio,
    });
  });

  return JSON.stringify({
    viewport: { w: window.innerWidth, h: window.innerHeight },
    scopeIsPage,
    document: {
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
      scrollHeight: document.documentElement.scrollHeight,
    },
    // Page-level criteria are only meaningful when the whole document is the scope; the
    // dialog pass must not re-count the page's own overflow as a dialog defect.
    pageHorizontalOverflow: scopeIsPage
      && document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
    contentHeight,
    scrollers,
    alignment,
    dashColumns,
    nonText,
    tables,
    clippedCount: clipped.length,
    clipped: clipped.slice(0, 20),
    intentionalEllipsisCount: intentionalEllipsis.length,
    tagCount: tags.length,
    textScanned,
    textSamples: textSamples.length,
    worstText: worstText === null ? null : { ratio: worstText.ratio, need: worstText.need, text: worstText.text, sel: worstText.sel },
    lowContrastText,
    lowContrastPlaceholders,
    lowContrastTags: tags.filter((t) => t.contrast !== null && t.contrast < ${TAG_CONTRAST_MIN}),
    zeroWidthTags: tags.filter((t) => t.width < 12 || t.height < 12),
    sampleTags: tags.slice(0, 6),
    // Self-check for the scope itself. Every other field is a COUNT OF PROBLEMS, so a scope
    // that silently measures nothing reports zero problems and reads as a pass - the most
    // dangerous shape a gate can have, and the risk declared in the batch41 and batch43 records
    // (the dialog pass resolves its scope by expression; get that wrong and it measures an empty
    // subtree for ever, green). This counts what was actually LOOKED AT, and the gate treats
    // zero as a failure rather than as a pass.
    scopeElements: tags.length + textSamples.length + alignment.length + clipped.length
      + tables.length + dashColumns.length,
    // Fingerprint, not just a count. "Non-empty" only rules out an empty subtree; pointing the
    // scope at some OTHER non-empty part of the page still measures something and still reports
    // zero problems. These two fields let the caller assert that the thing measured is the thing
    // it meant to measure: the page scope must contain the .page-title element the gate waited
    // for, and a dialog scope must contain the dialog's own title text.
    scopeHasPageTitle: !!root.querySelector('.page-title'),
    scopeSignature: (root.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 160),
  });
})()`;
}
/**
 * Build the alert variant matrix: clone the one real .el-alert on the login form once per
 * Element Plus variant (5 types x light/dark) into a throwaway host and return how many clones
 * were created. Exported so the batch43 negative control injects a defect into the SAME matrix
 * the gate measures - a control that rebuilt it would prove nothing about this gate.
 */
function alertMatrixExpression(selector = ".login-card .el-alert") {
  return `(() => {
    const src = document.querySelector(${JSON.stringify(selector)});
    if (!src) return 0;
    const host = document.createElement('div');
    host.id = 'c38-alert-matrix';
    for (const type of ['primary', 'success', 'warning', 'error', 'info']) {
      for (const effect of ['light', 'dark']) {
        const clone = src.cloneNode(true);
        clone.className = 'el-alert el-alert--' + type + ' is-' + effect;
        const title = clone.querySelector('.el-alert__title') || clone;
        title.textContent = type + '/' + effect;
        host.appendChild(clone);
      }
    }
    document.body.appendChild(host);
    return host.querySelectorAll('.el-alert').length;
  })()`;
}
const ALERT_MATRIX_SCOPE = "document.getElementById('c38-alert-matrix')";

async function main() {
  if (!ADMIN_PASS) {
    console.log("FATAL ADMIN_PASS is empty (read .local/admin-pass.txt)");
    process.exit(1);
  }
  const session = await launch({ width: VIEWPORT_WIDTH, height: VIEWPORT_HEIGHT });
  const errors = collectErrors(session);
  const report = { appUrl: APP_URL, viewport: { width: VIEWPORT_WIDTH, height: VIEWPORT_HEIGHT }, pages: [] };
  let failures = 0;

  try {
    // ---------------------------------------------------------------------------
    // Validation-state pass (batch43 补): a form error message exists only while a rule is
    // failing, so no walk over the rendered app can ever see one - which is how
    // `.el-form-item__error` (Element Plus paints it with --el-color-danger #f56c6c = 2.90:1 on
    // white) survived the batch39 sweep over 1936 text elements and the batch41 placeholder
    // sweep. The login form is the only form in the app with rules, and failing it on purpose is
    // safe AND checkable: onSubmit() awaits validate() and returns before the request when it is
    // false. Both halves are asserted below - the error text renders, and no /admin/auth/login
    // request was sent - so "we measured a harmless state" is evidence rather than a promise.
    //
    // This is measured before signing in, and it is the only measurement in this file whose
    // scope is not a page or a dialog, so its results go in their own bucket: a scope without
    // .page-title would otherwise be counted as a fingerprint failure.
    // ---------------------------------------------------------------------------
    let loginPosts = 0;
    session.cdp.on("Network.requestWillBeSent", (params) => {
      if (String(params.request?.url ?? "").includes("/admin/auth/login")) {
        loginPosts++;
      }
    });
    const validation = { attempted: false, errorSeen: false, requestSent: false, lowText: [], scanned: 0, scopes: 0 };
    await session.goto(`${APP_URL}/login`, "!!document.querySelector('.login-card')");
    await sleep(800);
    validation.attempted = await session.evaluate(
      "(() => { const b = document.querySelector('.login-btn'); if (!b) return false; b.click(); return true; })()",
    );
    if (validation.attempted) {
      validation.errorSeen = await session.waitFor("!!document.querySelector('.el-form-item__error')", 5000);
    }
    validation.requestSent = loginPosts > 0;
    await sleep(300);
    const vraw = JSON.parse(await session.evaluate(probeExpression("document.querySelector('.login-card')")));
    validation.scopes = vraw.scopeElements;
    validation.scanned = vraw.textScanned;
    validation.lowText = vraw.lowContrastText;
    validation.worstText = vraw.worstText;
    report.validation = validation;
    console.log("");
    console.log("--- login form validation state ---");
    console.log(`${validation.attempted ? "PASS" : "FAIL"}  empty submit clicked the login button`);
    console.log(`${validation.errorSeen ? "PASS" : "FAIL"}  a failing rule renders .el-form-item__error (this state is normally invisible to a page sweep)`);
    console.log(`${validation.requestSent ? "FAIL" : "PASS"}  a failing rule sends no request to the server (${loginPosts} login POSTs during the empty submit)`);
    console.log(`${validation.scopes > 0 ? "PASS" : "FAIL"}  login scope is non-empty (${validation.scopes} elements measured)`);
    console.log(`${validation.lowText.length === 0 ? "PASS" : "FAIL"}  validation error text reaches its WCAG threshold (${validation.lowText.length} below, ${validation.scanned} elements scanned, worst ${validation.worstText ? `${validation.worstText.ratio}:1 (needs ${validation.worstText.need}) "${validation.worstText.text}"` : "n/a"})`);
    validation.lowText.slice(0, 6).forEach((t) => console.log(`      low text ${t.ratio}:1 (need ${t.need}) "${t.text}" ${t.sel} color=${t.color} bg=${t.background} font=${t.fontSize}`));

    // ---------------------------------------------------------------------------
    // Component variant matrix (batch43): `.el-alert` is conditional everywhere in this app
    // (a scoped identity for the dashboard note, a refund hint for the two order ones), so the
    // page sweep can only ever see the one on the login form. A fix verified on one variant of
    // a component whose other four variants are never rendered is a fix verified by luck - the
    // same luck that let the batch38 tag fix and the batch39 token sweep both walk past this
    // component. So the real component is cloned once per variant (5 types x 2 effects) into a
    // throwaway host, and the clones are measured with THIS file's own measurement code. The
    // variant name is written into the title text, so a failure names itself in the output.
    //
    // This measures the shipped cascade, not a prediction of it: the clones carry exactly the
    // class names Element Plus puts on the root element. It is not a substitute for measuring a
    // real alert - it is what makes "all five variants are readable" checkable at all.
    // ---------------------------------------------------------------------------
    const alertMatrix = { variants: 0, scanned: 0, lowText: [] };
    alertMatrix.variants = await session.evaluate(alertMatrixExpression());
    if (alertMatrix.variants > 0) {
      const mraw = JSON.parse(await session.evaluate(probeExpression(ALERT_MATRIX_SCOPE)));
      alertMatrix.scanned = mraw.textScanned;
      alertMatrix.lowText = mraw.lowContrastText;
      alertMatrix.worstText = mraw.worstText;
      await session.evaluate("(() => { const h = document.querySelector('#c38-alert-matrix'); if (h) h.remove(); return true; })()");
    }
    validation.alertMatrix = alertMatrix;
    console.log(`${alertMatrix.variants === 10 ? "PASS" : "FAIL"}  alert variant matrix rendered (${alertMatrix.variants}/10 clones: 5 types x light/dark)`);
    console.log(`${alertMatrix.scanned >= 10 ? "PASS" : "FAIL"}  alert variant matrix measured (${alertMatrix.scanned} text elements, one per variant)`);
    console.log(`${alertMatrix.lowText.length === 0 ? "PASS" : "FAIL"}  every alert variant is readable (${alertMatrix.lowText.length} below, worst ${alertMatrix.worstText ? `${alertMatrix.worstText.ratio}:1 (needs ${alertMatrix.worstText.need}) "${alertMatrix.worstText.text}"` : "n/a"})`);
    alertMatrix.lowText.slice(0, 6).forEach((t) => console.log(`      low text ${t.ratio}:1 (need ${t.need}) "${t.text}" ${t.sel} color=${t.color} bg=${t.background}`));

    const signedIn = await login(session, APP_URL, ADMIN_USER, ADMIN_PASS);
    console.log(`${signedIn ? "PASS" : "FAIL"}  login`);
    if (!signedIn) {
      failures++;
    }
    await sleep(1500); // let the dev seeder settle before measuring data-dependent widths

    for (const page of PAGES) {
      const title = page.title;
      let path = page.path;
      let ready;
      if (page.clickFrom) {
        await session.goto(`${APP_URL}${page.clickFrom}`, "document.querySelectorAll('.el-table__row .el-link').length > 0");
        await session.evaluate("(document.querySelector('.el-table__row .el-link').click(), true)");
        const moved = await session.waitFor(`location.pathname !== ${JSON.stringify(page.clickFrom)}`, 15000);
        path = await session.evaluate("location.pathname");
        ready = moved && (await session.waitFor("!!document.querySelector('.page-title')", 15000));
        if (!moved) {
          console.log(`FAIL  ${page.clickFrom}: clicking the first row did not open a detail route`);
        }
      } else {
        ready = await session.goto(`${APP_URL}${path}`, "!!document.querySelector('.page-title')");
      }
      await sleep(1200);
      const raw = JSON.parse(await session.evaluate(probeExpression("document")));
      raw.path = path;
      raw.title = title;
      raw.clickFrom = page.clickFrom ?? null;
      raw.ready = ready;
      report.pages.push(raw);

      const scrollTables = raw.tables.filter((t) => t.horizontallyScrollable);
      const misaligned = raw.alignment.filter((c) => (c.deltaLeft !== null && Math.abs(c.deltaLeft) > 2) || (c.deltaCellLeft !== null && Math.abs(c.deltaCellLeft) > 2));
      const headerClipped = raw.alignment.filter((c) => c.headerTextClipped);
      console.log("");
      console.log(`--- ${path} (${title}) ---`);
      console.log(`PASS  rendered (title element present)`);
      console.log(`INFO  viewport=${raw.viewport.w}x${raw.viewport.h} document=${raw.document.scrollWidth}x${raw.document.scrollHeight} contentHeight=${raw.contentHeight} scrollers=[${raw.scrollers.map((s) => `${s.tag}.${s.cls.split(" ")[0]}:${s.clientHeight}<${s.scrollHeight}`).join(" ")}]`);
      console.log(`${raw.scopeElements > 0 ? "PASS" : "FAIL"}  measurement scope is non-empty (${raw.scopeElements} elements measured - a scope that measures nothing would otherwise report zero problems)`);
      console.log(`${raw.scopeHasPageTitle ? "PASS" : "FAIL"}  measurement scope is the page we navigated to (fingerprint: .page-title present)`);
      console.log(`${raw.pageHorizontalOverflow ? "FAIL" : "PASS"}  page-level horizontal overflow ${raw.pageHorizontalOverflow ? "(body scrolls sideways)" : "(none)"}`);
      console.log(`${scrollTables.length === 0 ? "PASS" : "WARN"}  tables needing internal horizontal scroll: ${scrollTables.length}/${raw.tables.length} ${scrollTables.map((t) => `[cols=${t.columns} ${t.wrapperClientWidth}<${t.wrapperScrollWidth}]`).join(" ")}`);
      console.log(`${raw.clippedCount === 0 ? "PASS" : "FAIL"}  table cells clipped NOT by design: ${raw.clippedCount} (intentional ellipsis with tooltip: ${raw.intentionalEllipsisCount})`);
      if (raw.clippedCount > 0) {
        raw.clipped.slice(0, 8).forEach((c) => console.log(`      clipped: "${c.text}" (${c.clientWidth}<${c.scrollWidth}px)`));
      }
      console.log(`${misaligned.length === 0 && headerClipped.length === 0 ? "PASS" : "FAIL"}  header row sits over its own body columns: ${raw.alignment.length} columns checked, ${misaligned.length} misaligned, ${headerClipped.length} header text clipped`);
      misaligned.slice(0, 6).forEach((c) => console.log(`      misaligned: "${c.label}" left delta ${c.deltaLeft}px width delta ${c.deltaWidth}px`));
      headerClipped.slice(0, 6).forEach((c) => console.log(`      clipped header: "${c.label}" height ${c.headerTextHeight}px`));
      console.log(`INFO  columns not rendering visible text: ${raw.dashColumns.length === 0 ? "none" : raw.dashColumns.map((c) => `"${c.label}" (${c.rows} rows visible-empty, ${c.domTextFilled} with DOM text${c.domTextFilled > 0 ? ` e.g. "${c.domTextSample}" - value bound but not visible` : ""})`).join(", ")}`);
      console.log(`INFO  tags=${raw.tagCount} lowContrast(<${TAG_CONTRAST_MIN}:1)=${raw.lowContrastTags.length} zeroSize=${raw.zeroWidthTags.length}`);
      console.log(`INFO  text elements scanned=${raw.textScanned} distinct(selector,colour)=${raw.textSamples} below their WCAG threshold=${raw.lowContrastText.length} worst=${raw.worstText ? `${raw.worstText.ratio}:1 (needs ${raw.worstText.need}) "${raw.worstText.text}"` : "n/a"}`);
      raw.lowContrastText.slice(0, 8).forEach((t) => console.log(`      low text ${t.ratio}:1 (need ${t.need}) "${t.text}" ${t.sel} color=${t.color} bg=${t.background} font=${t.fontSize}`));
      if (raw.lowContrastTags.length > 0) {
        raw.lowContrastTags.slice(0, 5).forEach((t) => console.log(`      low contrast ${t.contrast}: "${t.text}" color=${t.color} bg=${t.background} font=${t.fontSize}`));
      }
      if (raw.zeroWidthTags.length > 0) {
        raw.zeroWidthTags.slice(0, 5).forEach((t) => console.log(`      zero-size tag: "${t.text}" ${t.width}x${t.height}`));
      }
      if (raw.sampleTags.length > 0) {
        console.log(`      sample tag: "${raw.sampleTags[0].text}" ${raw.sampleTags[0].width}x${raw.sampleTags[0].height} contrast=${raw.sampleTags[0].contrast} font=${raw.sampleTags[0].fontSize}`);
      }
      const weakBorders = raw.nonText.filter((n) => n.ratio < 3);
      console.log(`INFO  non-text boundaries measured=${raw.nonText.length} below 3:1=${weakBorders.length} (WCAG 1.4.11, reported not gated - see the block record)`);
      weakBorders.slice(0, 4).forEach((n) => console.log(`      weak boundary ${n.ratio}:1 ${n.sel} border=${n.border} outside=${n.outside}`));
    }

    // ---------------------------------------------------------------------------
    // Dialog pass (batch40 补): the first version of this probe only measured pages, and a
    // dialog is where a reviewer actually clicks (the reject/refund/action forms). Element
    // Plus teleports dialogs to <body> and leaves the page behind them rendered, so each
    // dialog is measured with an element scope instead of the document - otherwise the page
    // numbers would be counted twice and nothing would say which numbers came from the form.
    //
    // Openers are found by button text and a missing opener is a SKIP with the reason, not a
    // FAIL: whether a 驳回/退款 button exists depends on the row's allowedActions, which is
    // data-dependent by design.
    // ---------------------------------------------------------------------------
    // preStep runs in the browser before the opener is looked for: a dialog that lives behind
    // a tab is unreachable otherwise, and "SKIP - opener absent" for a dialog that simply needed
    // one click is a coverage hole disguised as a data condition.
    // titleAny covers dialogs whose title is dynamic (the order dialog is 退款 or 冲正
    // depending on the row), which the content fingerprint must accept either way.
    const DIALOGS = [
      { path: "/alarm", opener: "建工单", title: "从告警创建工单" },
      { path: "/alarm", opener: "驳回", title: "驳回建议单",
        preStep: "(() => { const tab = [...document.querySelectorAll('.el-tabs__item')].find((t) => (t.innerText || '').includes('建议单')); if (!tab) return false; tab.click(); return true; })()" },
      { path: "/orders", opener: "退款", titleAny: ["退款", "冲正"] },
      { path: "/work-orders", opener: "分诊", title: "工单动作" },
    ];
    const dialogScope = "([...document.querySelectorAll('.el-dialog')].find((d) => d.getClientRects().length > 0) || null)";

    for (const d of DIALOGS) {
      const label = d.title ?? d.titleAny.join("/");
      await session.goto(`${APP_URL}${d.path}`, "!!document.querySelector('.page-title')");
      await sleep(700);
      if (d.preStep) {
        const stepped = await session.evaluate(d.preStep);
        await sleep(600);
        if (!stepped) {
          console.log("");
          console.log(`SKIP  dialog "${label}" on ${d.path}: preStep could not run (the tab it needs was not found)`);
          report.dialogSkips = (report.dialogSkips ?? 0) + 1;
          continue;
        }
      }
      const opened = await session.evaluate(`(() => {
        const btn = [...document.querySelectorAll('.el-button')]
          .find((b) => b.getClientRects().length > 0 && (b.innerText || '').trim().startsWith(${JSON.stringify(d.opener)}));
        if (!btn) return false;
        btn.click();
        return true;
      })()`);
      if (!opened) {
        console.log("");
        console.log(`SKIP  dialog "${label}" on ${d.path}: no visible button starting with "${d.opener}" (allowedActions-driven, may be absent for this data)`);
        report.dialogSkips = (report.dialogSkips ?? 0) + 1;
        continue;
      }
      const visible = await session.waitFor(`!!${dialogScope}`, 8000);
      if (!visible) {
        console.log("");
        console.log(`FAIL  dialog "${label}" on ${d.path}: button clicked but no visible .el-dialog appeared`);
        report.dialogs = report.dialogs ?? [];
        report.dialogs.push({ path: d.path, title: label, ready: false });
        continue;
      }
      await sleep(400);
      const raw = JSON.parse(await session.evaluate(probeExpression(dialogScope)));
      raw.path = d.path;
      raw.title = d.title ?? d.titleAny.join("/");
      raw.ready = true;
      // Content fingerprint: the measured subtree must be the dialog we opened. The count check
      // below only rules out an EMPTY scope; this rules out measuring a different non-empty one.
      const expected = d.titleAny ?? [d.title];
      raw.fingerprintOk = expected.some((needle) => raw.scopeSignature.includes(needle));
      report.dialogs = report.dialogs ?? [];
      report.dialogs.push(raw);

      const misaligned = raw.alignment.filter((c) => (c.deltaLeft !== null && Math.abs(c.deltaLeft) > 2) || (c.deltaCellLeft !== null && Math.abs(c.deltaCellLeft) > 2));
      const headerClipped = raw.alignment.filter((c) => c.headerTextClipped);
      console.log("");
      console.log(`--- dialog: ${d.title} (${d.path}) ---`);
      console.log(`PASS  opened and visible`);
      console.log(`${raw.scopeElements > 0 ? "PASS" : "FAIL"}  dialog scope is non-empty (${raw.scopeElements} elements measured)`);
      console.log(`${raw.fingerprintOk ? "PASS" : "FAIL"}  dialog scope is the dialog we opened (expected "${d.title}" in "${raw.scopeSignature.slice(0, 40)}")`);
      console.log(`${raw.clippedCount === 0 ? "PASS" : "FAIL"}  dialog cells clipped NOT by design: ${raw.clippedCount}`);
      raw.clipped.slice(0, 4).forEach((c) => console.log(`      clipped: "${c.text}" (${c.clientWidth}<${c.scrollWidth}px)`));
      console.log(`${misaligned.length === 0 ? "PASS" : "FAIL"}  dialog tables aligned: ${raw.alignment.length} columns, ${misaligned.length} misaligned`);
      console.log(`INFO  dialog tags=${raw.tagCount} lowContrast=${raw.lowContrastTags.length} zeroSize=${raw.zeroWidthTags.length}`);
      console.log(`INFO  dialog text elements=${raw.textScanned} below threshold=${raw.lowContrastText.length}`);
      raw.lowContrastText.slice(0, 6).forEach((t) => console.log(`      low text ${t.ratio}:1 (need ${t.need}) "${t.text}" ${t.sel} color=${t.color} bg=${t.background} font=${t.fontSize}`));
      raw.lowContrastTags.slice(0, 4).forEach((t) => console.log(`      low tag ${t.contrast}:1 "${t.text}" color=${t.color} bg=${t.background}`));
      // Close via the header X so the next iteration starts from a clean page.
      await session.evaluate("(() => { const b = document.querySelector('.el-dialog__headerbtn'); if (b) b.click(); return true; })()");
      await sleep(400);
    }

    // ---------------------------------------------------------------------------
    // Interaction-state pass (batch43 补): hover was the last declared gap of this gate. The state
    // is constructed with CDP (CSS.forcePseudoState) and asserted to have applied BEFORE any
    // contrast number from it is trusted - see the note on HOVER_SELECTOR at the top of the file.
    // ---------------------------------------------------------------------------
    const hoverResults = [];
    for (const path of HOVER_PAGES) {
      await session.goto(`${APP_URL}${path}`, "!!document.querySelector('.page-title')");
      await sleep(900);
      const hover = await measureHoverStates(session, { label: path });
      hover.worstText = hover.samples
        .map((s) => s.worstText)
        .filter(Boolean)
        .reduce((w, t) => (w === null || t.ratio < w.ratio ? t : w), null);
      hoverResults.push(hover);
      console.log("");
      console.log(`--- hover states ${path} ---`);
      console.log(`${hover.picked > 0 ? "PASS" : "FAIL"}  interactive elements available to hover (${hover.picked} of ${hover.total} tagged, limit ${HOVER_LIMIT})`);
      console.log(`${hover.applied > 0 ? "PASS" : "FAIL"}  forcing :hover changed the computed style (${hover.applied}/${hover.picked}) - a state that never applied would report clean for free`);
      console.log(`${hover.low.length === 0 ? "PASS" : "FAIL"}  hovered text reaches its WCAG threshold (${hover.low.length} below, worst ${hover.worstText ? `${hover.worstText.ratio}:1 (needs ${hover.worstText.need})` : "n/a"})`);
      hover.low.slice(0, 5).forEach((t) => console.log(`      low text ${t.ratio}:1 (need ${t.need}) "${t.text}" on "${t.element}" ${t.sel} color=${t.color} bg=${t.background}`));
      hover.notApplied.slice(0, 3).forEach((n) => console.log(`      INFO no hover styling: "${n.label}" (${n.why})`));
    }

    // Hard criteria (batch38): these are the three things the vision review flagged that
    // turned out to be measurable. They must stay clean, so they are gate conditions:
    //   1. no page-level horizontal overflow at the 1440px target width
    //   2. no cell clipped unless it is declared with show-overflow-tooltip (intentional)
    //   3. no tag whose text/background contrast is below 4.5:1. Element Plus paints light
    //      tags with the BASE semantic colour on its light-9 tint (#67c23a on #f0f9eb is
    //      2.08:1, #e6a23c on #fdf6ec 2.04:1), which at the 12px tag font size is not
    //      "slightly low" but unreadable - a reviewer described those cells as an empty
    //      coloured box. 4.5:1 is the WCAG 1.4.3 threshold for normal-size text and 12px
    //      is normal-size text, so the gate uses it rather than a lenient 3:1.
    //   4. no text element below ITS OWN threshold - 4.5:1 normally, 3:1 only for large text
    //      (>=24px, or >=18.66px bold). Added in batch39 after the tag-only version of this
    //      file proved the failure was not tag-specific: Element Plus's
    //      --el-text-color-secondary (#909399, 2.87-3.08:1) and --el-color-primary
    //      (#409eff, 2.78:1 as text AND under white text) broke the whole secondary text
    //      layer, every link, every table header label and the primary buttons. Measuring
    //      leaf text elements instead of a fixed selector list is what makes this hold for
    //      components nobody thought to list.
    const overflow = report.pages.filter((p) => p.pageHorizontalOverflow);
    const clipped = report.pages.reduce((sum, p) => sum + p.clippedCount, 0);
    const lowContrast = report.pages.reduce((sum, p) => sum + p.lowContrastTags.length, 0);
    const zeroSize = report.pages.reduce((sum, p) => sum + p.zeroWidthTags.length, 0);
    const lowText = report.pages.reduce((sum, p) => sum + p.lowContrastText.length, 0);
    const textScanned = report.pages.reduce((sum, p) => sum + p.textScanned, 0);
    const misaligned = report.pages.reduce(
      (sum, p) => sum + p.alignment.filter((c) => (c.deltaLeft !== null && Math.abs(c.deltaLeft) > 2) || (c.deltaCellLeft !== null && Math.abs(c.deltaCellLeft) > 2)).length,
      0,
    );
    const headerClipped = report.pages.reduce(
      (sum, p) => sum + p.alignment.filter((c) => c.headerTextClipped).length,
      0,
    );
    // Dialog results are aggregated separately so a regression says which surface broke.
    const dialogs = report.dialogs ?? [];
    const dialogReady = dialogs.filter((d) => d.ready !== false);
    const dialogClipped = dialogReady.reduce((sum, d) => sum + d.clippedCount, 0);
    const dialogLowText = dialogReady.reduce((sum, d) => sum + d.lowContrastText.length, 0);
    const dialogLowTag = dialogReady.reduce((sum, d) => sum + d.lowContrastTags.length, 0);
    const dialogMisaligned = dialogReady.reduce(
      (sum, d) => sum + d.alignment.filter((c) => (c.deltaLeft !== null && Math.abs(c.deltaLeft) > 2) || (c.deltaCellLeft !== null && Math.abs(c.deltaCellLeft) > 2)).length,
      0,
    );
    const dialogNotReady = dialogs.filter((d) => d.ready === false).length;
    const dialogWrongScope = dialogReady.filter((d) => d.fingerprintOk === false).length;
    // A scope that measured nothing is a failed measurement, not a clean one.
    const badScopes = report.pages.filter((p) => !(p.scopeHasPageTitle)).length
    const emptyScopes = report.pages.filter((p) => !(p.scopeElements > 0)).length
      + dialogReady.filter((d) => !(d.scopeElements > 0)).length;
    const nonTextWeak = report.pages.reduce((sum, p) => sum + p.nonText.filter((n) => n.ratio < 3).length, 0);
    const nonTextMeasured = report.pages.reduce((sum, p) => sum + p.nonText.length, 0);
    // The validation pass is a gate condition, not a footnote: if the error state never appeared
    // then the measurement did not happen, and "no low-contrast error text" would be a claim
    // about nothing. Same principle as an empty measurement scope.
    const validationCovered = !!(report.validation?.attempted && report.validation?.errorSeen);
    const validationRequestSent = !!report.validation?.requestSent;
    const validationLowText = (report.validation?.lowText ?? []).length;
    const alertMatrixResult = report.validation?.alertMatrix ?? { variants: 0, scanned: 0, lowText: [] };
    const alertMatrixBad = (alertMatrixResult.variants === 10 && alertMatrixResult.scanned >= 10 ? 0 : 1) + alertMatrixResult.lowText.length;
    // Hover: a page where nothing could be hovered, or where forcing :hover changed nothing, is a
    // failed measurement - not a clean one.
    const hoverPicked = hoverResults.reduce((sum, h) => sum + h.picked, 0);
    const hoverApplied = hoverResults.reduce((sum, h) => sum + h.applied, 0);
    const hoverLow = hoverResults.reduce((sum, h) => sum + h.low.length, 0);
    const hoverUnusable = hoverResults.filter((h) => h.picked === 0 || h.applied === 0).length;
    report.hover = hoverResults;
    console.log("");
    console.log(`${overflow.length === 0 ? "PASS" : "FAIL"}  no page overflows horizontally at ${VIEWPORT_WIDTH}px`);
    console.log(`${clipped === 0 ? "PASS" : "FAIL"}  no non-intentional cell clipping (${clipped} cells)`);
    console.log(`${misaligned === 0 ? "PASS" : "FAIL"}  no header cell sits over the wrong body column (${misaligned} misaligned)`);
    console.log(`${headerClipped === 0 ? "PASS" : "FAIL"}  no clipped table header text (${headerClipped})`);
    console.log(`${lowContrast === 0 ? "PASS" : "FAIL"}  every visible tag reaches ${TAG_CONTRAST_MIN}:1 contrast (${lowContrast} below)`);
    console.log(`${lowText === 0 ? "PASS" : "FAIL"}  every text element reaches its WCAG threshold (${lowText} below, ${textScanned} elements scanned)`);
    console.log(`${zeroSize === 0 ? "PASS" : "FAIL"}  no zero-sized visible tag (${zeroSize})`);
    console.log(`${emptyScopes === 0 ? "PASS" : "FAIL"}  every measurement scope contained elements (${emptyScopes} empty)`);
    console.log(`${badScopes === 0 && dialogWrongScope === 0 ? "PASS" : "FAIL"}  every measurement scope matched its fingerprint (page .page-title missing: ${badScopes}, dialog title mismatch: ${dialogWrongScope})`);
    console.log(`${dialogNotReady === 0 && dialogClipped + dialogLowText + dialogLowTag + dialogMisaligned === 0 ? "PASS" : "FAIL"}  dialogs: ${dialogReady.length} measured (${report.dialogSkips ?? 0} skipped - opener absent), ${dialogNotReady} failed to open, ${dialogClipped} clipped, ${dialogMisaligned} misaligned, ${dialogLowTag} low-contrast tags, ${dialogLowText} low-contrast text`);
    console.log(`${validationCovered ? "PASS" : "FAIL"}  login validation state was reached (attempted=${report.validation?.attempted}, error text rendered=${report.validation?.errorSeen})`);
    console.log(`${validationRequestSent ? "FAIL" : "PASS"}  the invalid submit never reached the server`);
    console.log(`${validationLowText === 0 ? "PASS" : "FAIL"}  validation error text contrast (${validationLowText} below, ${report.validation?.scanned ?? 0} elements scanned in the login scope)`);
    console.log(`${alertMatrixBad === 0 ? "PASS" : "FAIL"}  alert variant matrix: ${alertMatrixResult.variants} variants, ${alertMatrixResult.scanned} measured, ${alertMatrixResult.lowText.length} below threshold`);
    console.log(`${hoverUnusable === 0 ? "PASS" : "FAIL"}  hover states were constructed on every page (${hoverApplied} of ${hoverPicked} elements changed style, ${hoverUnusable} pages with nothing measurable)`);
    console.log(`${hoverLow === 0 ? "PASS" : "FAIL"}  hovered text contrast (${hoverLow} below across ${hoverPicked} hovered elements)`);
    console.log(`INFO  non-text boundaries (WCAG 1.4.11): ${nonTextMeasured} measured, ${nonTextWeak} below 3:1 - reported, deliberately NOT a gate condition`);
    // The closest call across everything measured. "0 below threshold" says nothing about how
    // much headroom is left; this does. Computed over pages, the login scope and the alert
    // matrix, so a variant that barely passes cannot hide inside a passing average.
    const worstOverall = [...report.pages.map((p) => p.worstText), report.validation?.worstText, alertMatrixResult.worstText, ...hoverResults.map((h) => h.worstText)]
      .filter(Boolean)
      .reduce((w, t) => (w === null || t.ratio < w.ratio ? t : w), null);
    console.log(`INFO  closest call anywhere in this run: ${worstOverall ? `${worstOverall.ratio}:1 (needs ${worstOverall.need}) "${worstOverall.text}" ${worstOverall.sel}` : "nothing measured"}`);
    console.log(`${errors.consoleErrors.length === 0 ? "PASS" : "FAIL"}  console errors during probe: ${errors.consoleErrors.length}`);
    console.log(`${errors.httpErrors.length === 0 ? "PASS" : "FAIL"}  HTTP >= 400 during probe: ${errors.httpErrors.length}`);
    if (errors.consoleErrors.length > 0) {
      console.log(`      ${errors.consoleErrors.slice(0, 3).join(" | ")}`);
    }
    report.consoleErrors = errors.consoleErrors;
    report.httpErrors = errors.httpErrors;
    report.totals = {
      clipped, lowContrast, lowText, textScanned, zeroSize, overflowPages: overflow.length, misaligned, headerClipped,
      dialogsMeasured: dialogReady.length, dialogSkips: report.dialogSkips ?? 0, emptyScopes, badScopes, dialogWrongScope, dialogClipped, dialogLowText, dialogLowTag,
      dialogMisaligned, dialogNotReady, nonTextMeasured, nonTextWeak,
      validationCovered, validationRequestSent, validationLowText, validationScanned: report.validation?.scanned ?? 0,
      alertVariants: alertMatrixResult.variants, alertVariantsScanned: alertMatrixResult.scanned, alertVariantsLowText: alertMatrixResult.lowText.length,
      worstTextRatio: worstOverall?.ratio ?? null, worstTextLabel: worstOverall ? `${worstOverall.text} ${worstOverall.sel}` : null,
      hoverPages: hoverResults.length, hoverPicked, hoverApplied, hoverLow, hoverUnusable,
    };

    const hardFailures = report.pages.filter((p) => !p.ready).length + failures
      + overflow.length + clipped + lowContrast + lowText + zeroSize + misaligned + headerClipped
      + dialogNotReady + dialogClipped + dialogLowText + dialogLowTag + dialogMisaligned + emptyScopes + badScopes + dialogWrongScope
      + (validationCovered ? 0 : 1) + (validationRequestSent ? 1 : 0) + validationLowText + alertMatrixBad
      + hoverUnusable + hoverLow
      + errors.consoleErrors.length + errors.httpErrors.length;
    console.log("");
    console.log(`=== C38 summary: pages=${report.pages.length} dialogs=${dialogReady.length} hardFailures=${hardFailures} ===`);
    console.log("JSON-BEGIN");
    console.log(JSON.stringify(report));
    console.log("JSON-END");
    console.log(hardFailures === 0 ? "GATE-UI-PROBE PASS" : "GATE-UI-PROBE FAIL");
    process.exitCode = hardFailures === 0 ? 0 : 1;
  } finally {
    session.close();
  }
}

// Run the sweep only when this file is executed directly. The batch43 negative-control script
// imports it to reuse the EXACT measurement code (that is the whole point of the control: if it
// re-implemented the checks, passing it would prove nothing about this gate), so importing must
// not kick off a full page sweep as a side effect.
const isDirectRun = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isDirectRun) {
  main().catch((error) => {
    console.log(`FATAL ${error.stack ?? error.message}`);
    process.exit(1);
  });
}

export { probeExpression, TAG_CONTRAST_MIN, PAGES, alertMatrixExpression, ALERT_MATRIX_SCOPE, measureHoverStates, HOVER_SELECTOR, HOVER_PAGES };
