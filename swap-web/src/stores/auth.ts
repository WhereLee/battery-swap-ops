import { defineStore } from "pinia";
import { computed, ref } from "vue";
import { clearToken, getToken, setToken } from "../api/http";
import { fetchMe, login as loginApi, logout as logoutApi } from "../api/views";
import type { MeVO } from "../api/types";

/**
 * Auth store: token + the identity document from GET /admin/auth/me.
 *
 * The permission codes are NOT hardcoded per role here on purpose — the backend is the
 * single source of truth (AdminRole.permissions()), downloaded on login/refresh. Routes
 * filter on meta.codes and buttons use v-access, both reading this store.
 */
export const useAuthStore = defineStore("auth", () => {
  const token = ref<string>(getToken());
  const me = ref<MeVO | null>(null);
  const loading = ref(false);

  const codes = computed<Set<string>>(() => new Set(me.value?.codes ?? []));
  const username = computed(() => me.value?.username ?? "");
  const role = computed(() => me.value?.role ?? "");
  const dataScoped = computed(() => me.value?.dataScoped ?? false);

  function hasCode(code: string): boolean {
    return codes.value.has(code);
  }

  /** Any-of semantics: a route/button is reachable when at least one code matches. */
  function hasAnyCode(list: string | string[] | undefined): boolean {
    if (!list || (Array.isArray(list) && list.length === 0)) {
      return true;
    }
    const arr = Array.isArray(list) ? list : [list];
    return arr.some((code) => codes.value.has(code));
  }

  async function loadMe(): Promise<MeVO> {
    loading.value = true;
    try {
      me.value = await fetchMe();
      return me.value;
    } finally {
      loading.value = false;
    }
  }

  async function login(usernameInput: string, password: string): Promise<void> {
    const result = await loginApi(usernameInput, password);
    setToken(result.token);
    token.value = result.token;
    await loadMe();
  }

  async function logout(): Promise<void> {
    try {
      await logoutApi();
    } catch {
      // Logout is best-effort: the server session may already be gone. Local state must clear.
    }
    clearToken();
    token.value = "";
    me.value = null;
  }

  function resetLocal(): void {
    clearToken();
    token.value = "";
    me.value = null;
  }

  return {
    token,
    me,
    loading,
    codes,
    username,
    role,
    dataScoped,
    hasCode,
    hasAnyCode,
    loadMe,
    login,
    logout,
    resetLocal,
  };
});
