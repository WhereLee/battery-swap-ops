/**
 * Formatting helpers.
 *
 * Every time field in this platform is epoch millis (`System.currentTimeMillis()` on
 * the server, `Long` on the entities and VOs) — there is no ISO string and no
 * LocalDateTime serialization to worry about. Rates from DashboardService are 0..1
 * ratios rounded to 4 decimals, so percentages must be scaled here, not on the server.
 */

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

export function formatTime(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || ms === 0) {
    return "-";
  }
  const date = new Date(ms);
  if (Number.isNaN(date.getTime())) {
    return "-";
  }
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
}

/** Relative age, used for heartbeat freshness where an absolute timestamp is noise. */
export function formatAge(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || ms < 0) {
    return "-";
  }
  if (ms < 1000) {
    return "刚刚";
  }
  const seconds = Math.floor(ms / 1000);
  if (seconds < 60) {
    return `${seconds} 秒前`;
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes} 分钟前`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours} 小时前`;
  }
  return `${Math.floor(hours / 24)} 天前`;
}

/** 0..1 ratio -> "85.3%"; a 0 denominator already yields 0.0 on the server. */
export function formatPercent(rate: number | null | undefined, digits = 1): string {
  if (rate === null || rate === undefined || Number.isNaN(rate)) {
    return "-";
  }
  return `${(rate * 100).toFixed(digits)}%`;
}

/** Turnover is "completed orders per active battery today", not a percentage. */
export function formatTurnover(rate: number | null | undefined): string {
  if (rate === null || rate === undefined || Number.isNaN(rate)) {
    return "-";
  }
  return rate.toFixed(2);
}

/**
 * Money is integer fen end to end; never let a float touch it.
 * The sign goes before the symbol ("-¥3.00") because the split ledger renders negative
 * reversal lines and "¥-3.00" reads like a typo.
 */
export function formatFen(fen: number | null | undefined): string {
  if (fen === null || fen === undefined) {
    return "-";
  }
  return fen < 0 ? `-¥${(-fen / 100).toFixed(2)}` : `¥${(fen / 100).toFixed(2)}`;
}
