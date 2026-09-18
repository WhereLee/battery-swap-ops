import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import "element-plus/dist/index.css";

import App from "./App.vue";
import { router } from "./router";
import { setUnauthorizedHandler } from "./api/http";
import { useAuthStore } from "./stores/auth";
import { createAccessDirective } from "./directives/access";
import "./styles/global.css";

const app = createApp(App);
const pinia = createPinia();

// Pinia must be installed before any store is instantiated, including the one handed
// to the v-access directive factory (the directive closes over a live store instance).
app.use(pinia);
const auth = useAuthStore(pinia);
app.directive("access", createAccessDirective(auth));
app.use(router);
app.use(ElementPlus, { locale: zhCn });

/**
 * 401 from anywhere (expired token, server restart wiping Redis sessions) clears local
 * state and returns to login with the intended destination preserved. Kept out of the
 * axios module so http.ts stays free of router/store imports.
 */
setUnauthorizedHandler(() => {
  auth.resetLocal();
  const current = router.currentRoute.value;
  if (current.path !== "/login") {
    void router.replace({ path: "/login", query: { redirect: current.fullPath } });
  }
});

app.mount("#app");
