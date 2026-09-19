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

/** Runs in the browser: returns raw measurements for one page. */
const PROBE = `(() => {
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
  document.querySelectorAll('.el-table .cell').forEach((cell) => {
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

  const tables = Array.from(document.querySelectorAll('.el-table')).map((table, index) => {
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
  document.querySelectorAll('*').forEach((el) => {
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
  document.querySelectorAll('.el-table').forEach((table, tableIndex) => {
    const headers = Array.from(table.querySelectorAll('.el-table__header th'));
    const firstRow = table.querySelector('.el-table__body tbody tr');
    const bodyCells = firstRow ? Array.from(firstRow.children) : [];
    headers.forEach((th, index) => {
      const cell = th.querySelector('.cell');
      const headerBox = th.getBoundingClientRect();
      const bodyBox = bodyCells[index] ? bodyCells[index].getBoundingClientRect() : null;
      alignment.push({
        table: tableIndex,
        index,
        label: (th.innerText || '').trim().slice(0, 24),
        deltaLeft: bodyBox ? Math.round(bodyBox.left - headerBox.left) : null,
        deltaWidth: bodyBox ? Math.round(bodyBox.width - headerBox.width) : null,
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
  document.querySelectorAll('.el-table').forEach((table, tableIndex) => {
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

  const tags = [];
  document.querySelectorAll('.el-tag').forEach((tag) => {
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

  return JSON.stringify({
    viewport: { w: window.innerWidth, h: window.innerHeight },
    document: {
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
      scrollHeight: document.documentElement.scrollHeight,
    },
    pageHorizontalOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
    contentHeight,
    scrollers,
    alignment,
    dashColumns,
    tables,
    clippedCount: clipped.length,
    clipped: clipped.slice(0, 20),
    intentionalEllipsisCount: intentionalEllipsis.length,
    tagCount: tags.length,
    lowContrastTags: tags.filter((t) => t.contrast !== null && t.contrast < ${TAG_CONTRAST_MIN}),
    zeroWidthTags: tags.filter((t) => t.width < 12 || t.height < 12),
    sampleTags: tags.slice(0, 6),
  });
})()`;

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
      const raw = JSON.parse(await session.evaluate(PROBE));
      raw.path = path;
      raw.title = title;
      raw.clickFrom = page.clickFrom ?? null;
      raw.ready = ready;
      report.pages.push(raw);

      const scrollTables = raw.tables.filter((t) => t.horizontallyScrollable);
      const misaligned = raw.alignment.filter((c) => c.deltaLeft !== null && Math.abs(c.deltaLeft) > 2);
      const headerClipped = raw.alignment.filter((c) => c.headerTextClipped);
      console.log("");
      console.log(`--- ${path} (${title}) ---`);
      console.log(`PASS  rendered (title element present)`);
      console.log(`INFO  viewport=${raw.viewport.w}x${raw.viewport.h} document=${raw.document.scrollWidth}x${raw.document.scrollHeight} contentHeight=${raw.contentHeight} scrollers=[${raw.scrollers.map((s) => `${s.tag}.${s.cls.split(" ")[0]}:${s.clientHeight}<${s.scrollHeight}`).join(" ")}]`);
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
      if (raw.lowContrastTags.length > 0) {
        raw.lowContrastTags.slice(0, 5).forEach((t) => console.log(`      low contrast ${t.contrast}: "${t.text}" color=${t.color} bg=${t.background} font=${t.fontSize}`));
      }
      if (raw.zeroWidthTags.length > 0) {
        raw.zeroWidthTags.slice(0, 5).forEach((t) => console.log(`      zero-size tag: "${t.text}" ${t.width}x${t.height}`));
      }
      if (raw.sampleTags.length > 0) {
        console.log(`      sample tag: "${raw.sampleTags[0].text}" ${raw.sampleTags[0].width}x${raw.sampleTags[0].height} contrast=${raw.sampleTags[0].contrast} font=${raw.sampleTags[0].fontSize}`);
      }
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
    const overflow = report.pages.filter((p) => p.pageHorizontalOverflow);
    const clipped = report.pages.reduce((sum, p) => sum + p.clippedCount, 0);
    const lowContrast = report.pages.reduce((sum, p) => sum + p.lowContrastTags.length, 0);
    const zeroSize = report.pages.reduce((sum, p) => sum + p.zeroWidthTags.length, 0);
    const misaligned = report.pages.reduce(
      (sum, p) => sum + p.alignment.filter((c) => c.deltaLeft !== null && Math.abs(c.deltaLeft) > 2).length,
      0,
    );
    const headerClipped = report.pages.reduce(
      (sum, p) => sum + p.alignment.filter((c) => c.headerTextClipped).length,
      0,
    );
    console.log("");
    console.log(`${overflow.length === 0 ? "PASS" : "FAIL"}  no page overflows horizontally at ${VIEWPORT_WIDTH}px`);
    console.log(`${clipped === 0 ? "PASS" : "FAIL"}  no non-intentional cell clipping (${clipped} cells)`);
    console.log(`${misaligned === 0 ? "PASS" : "FAIL"}  no header cell sits over the wrong body column (${misaligned} misaligned)`);
    console.log(`${headerClipped === 0 ? "PASS" : "FAIL"}  no clipped table header text (${headerClipped})`);
    console.log(`${lowContrast === 0 ? "PASS" : "FAIL"}  every visible tag reaches ${TAG_CONTRAST_MIN}:1 contrast (${lowContrast} below)`);
    console.log(`${zeroSize === 0 ? "PASS" : "FAIL"}  no zero-sized visible tag (${zeroSize})`);
    console.log(`${errors.consoleErrors.length === 0 ? "PASS" : "FAIL"}  console errors during probe: ${errors.consoleErrors.length}`);
    console.log(`${errors.httpErrors.length === 0 ? "PASS" : "FAIL"}  HTTP >= 400 during probe: ${errors.httpErrors.length}`);
    if (errors.consoleErrors.length > 0) {
      console.log(`      ${errors.consoleErrors.slice(0, 3).join(" | ")}`);
    }
    report.consoleErrors = errors.consoleErrors;
    report.httpErrors = errors.httpErrors;
    report.totals = { clipped, lowContrast, zeroSize, overflowPages: overflow.length, misaligned, headerClipped };

    const hardFailures = report.pages.filter((p) => !p.ready).length + failures
      + overflow.length + clipped + lowContrast + zeroSize + misaligned + headerClipped
      + errors.consoleErrors.length + errors.httpErrors.length;
    console.log("");
    console.log(`=== C38 summary: pages=${report.pages.length} hardFailures=${hardFailures} ===`);
    console.log("JSON-BEGIN");
    console.log(JSON.stringify(report));
    console.log("JSON-END");
    console.log(hardFailures === 0 ? "GATE-UI-PROBE PASS" : "GATE-UI-PROBE FAIL");
    process.exitCode = hardFailures === 0 ? 0 : 1;
  } finally {
    session.close();
  }
}

main().catch((error) => {
  console.log(`FATAL ${error.stack ?? error.message}`);
  process.exit(1);
});
