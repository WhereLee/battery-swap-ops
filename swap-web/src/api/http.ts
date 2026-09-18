import axios, { AxiosError, type AxiosInstance, type AxiosResponse } from "axios";
import { ElMessage } from "element-plus";

/**
 * Envelope and error contract of the platform (see common/Result.java and
 * common/RRExceptionHandler.java):
 *   HTTP 200 + {code:0,msg,data}  -> success
 *   HTTP 400                      -> business validation failure (message is user-facing)
 *   HTTP 401                      -> missing/invalid X-Admin-Token
 *   HTTP 403                      -> @PreAuthorize denial or data-scope violation
 *   HTTP 429 + Retry-After        -> rate limited
 *   HTTP 500                      -> unexpected, server logged the stack
 */
export interface ApiResult<T> {
  code: number;
  msg: string;
  data: T;
}

export interface PageResult<T> {
  list: T[];
  total: number;
  page: number;
  limit: number;
}

const TOKEN_KEY = "swap.admin.token";

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? "";
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
}

/** Set by the auth store so the interceptor can redirect without importing the router. */
let onUnauthorized: () => void = () => undefined;
export function setUnauthorizedHandler(handler: () => void): void {
  onUnauthorized = handler;
}

export const http: AxiosInstance = axios.create({
  baseURL: "/api",
  timeout: 20000,
});

http.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.set("X-Admin-Token", token);
  }
  return config;
});

http.interceptors.response.use(
  (response: AxiosResponse<ApiResult<unknown>>) => {
    const body = response.data;
    // Non-zero code with HTTP 200 is not produced by the platform today, but the
    // envelope allows it; surface it instead of silently rendering empty data.
    if (body && typeof body === "object" && "code" in body && body.code !== 0) {
      ElMessage.error(body.msg || "请求失败");
      return Promise.reject(new Error(body.msg || "business error"));
    }
    return response;
  },
  (error: AxiosError<ApiResult<unknown>>) => {
    const status = error.response?.status;
    const msg = error.response?.data?.msg;
    if (status === 401) {
      clearToken();
      onUnauthorized();
      ElMessage.error(msg || "登录已失效，请重新登录");
    } else if (status === 403) {
      ElMessage.error(msg || "无权访问（权限或数据范围不足）");
    } else if (status === 429) {
      const retryAfter = error.response?.headers?.["retry-after"];
      ElMessage.warning(`请求过于频繁${retryAfter ? `，${retryAfter}s 后重试` : ""}`);
    } else if (status && status >= 500) {
      ElMessage.error(msg || "服务端异常（已记录日志）");
    } else {
      ElMessage.error(msg || error.message || "网络异常");
    }
    return Promise.reject(error);
  },
);

/** GET returning the unwrapped payload. */
export async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const res = await http.get<ApiResult<T>>(url, { params });
  return res.data.data;
}

/** POST returning the unwrapped payload; body may be omitted for action endpoints. */
export async function post<T>(url: string, body?: unknown, params?: Record<string, unknown>): Promise<T> {
  const res = await http.post<ApiResult<T>>(url, body ?? {}, { params });
  return res.data.data;
}
