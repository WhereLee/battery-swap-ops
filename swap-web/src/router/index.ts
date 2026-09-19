import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import { CODES, type PermissionCode } from "../api/permissions";
import { getToken } from "../api/http";
import { useAuthStore } from "../stores/auth";

/**
 * Route meta contract.
 *
 * `codes` is an ANY-OF list: the route is reachable when the identity downloaded from
 * GET /admin/auth/me holds at least one of them. Routes without `codes` only require
 * authentication. This mirrors @PreAuthorize("hasAnyAuthority(...)") on the backend —
 * the frontend check is UX (do not render what will 403), the backend check is the
 * actual security boundary.
 */
declare module "vue-router" {
  interface RouteMeta {
    title?: string;
    codes?: PermissionCode[];
    /** Public routes skip authentication entirely (login). */
    public?: boolean;
    /** Hidden routes are not rendered in the side menu (detail pages, error pages). */
    hidden?: boolean;
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: "/login",
    name: "login",
    component: () => import("../views/LoginView.vue"),
    meta: { title: "登录", public: true, hidden: true },
  },
  {
    path: "/",
    component: () => import("../layouts/DefaultLayout.vue"),
    redirect: "/dashboard",
    children: [
      {
        path: "dashboard",
        name: "dashboard",
        component: () => import("../views/DashboardView.vue"),
        meta: { title: "运营看板", codes: [CODES.dashboardRead] },
      },
      {
        path: "alarm",
        name: "alarm",
        component: () => import("../views/AlarmView.vue"),
        meta: { title: "告警与建议单", codes: [CODES.alarmRead, CODES.suggestionRead] },
      },
      {
        path: "work-orders",
        name: "work-orders",
        component: () => import("../views/WorkOrderListView.vue"),
        meta: { title: "工单", codes: [CODES.workOrderRead] },
      },
      {
        path: "work-orders/:id",
        name: "work-order-detail",
        component: () => import("../views/WorkOrderDetailView.vue"),
        meta: { title: "工单详情", codes: [CODES.workOrderRead], hidden: true },
      },
      {
        path: "orders",
        name: "orders",
        component: () => import("../views/OrderListView.vue"),
        meta: { title: "换电订单", codes: [CODES.orderRead] },
      },
      {
        path: "orders/:orderNo",
        name: "order-detail",
        component: () => import("../views/OrderDetailView.vue"),
        meta: { title: "订单详情", codes: [CODES.orderRead], hidden: true },
      },
      {
        path: "cabinets",
        name: "cabinets",
        component: () => import("../views/CabinetListView.vue"),
        meta: { title: "换电柜", codes: [CODES.assetRead] },
      },
      {
        path: "cabinets/:cabinetNo",
        name: "cabinet-detail",
        component: () => import("../views/CabinetDetailView.vue"),
        meta: { title: "柜详情", codes: [CODES.assetRead], hidden: true },
      },
      {
        path: "settlements",
        name: "settlements",
        component: () => import("../views/SettlementListView.vue"),
        meta: { title: "结算单", codes: [CODES.settlementRead] },
      },
      {
        path: "settlements/:id",
        name: "settlement-detail",
        component: () => import("../views/SettlementDetailView.vue"),
        meta: { title: "结算单详情", codes: [CODES.settlementRead], hidden: true },
      },
    ],
  },
  {
    path: "/403",
    name: "forbidden",
    component: () => import("../views/ForbiddenView.vue"),
    meta: { title: "无权访问", hidden: true },
  },
  {
    path: "/:pathMatch(.*)*",
    name: "not-found",
    component: () => import("../views/NotFoundView.vue"),
    meta: { title: "页面不存在", hidden: true },
  },
];

export const router = createRouter({
  history: createWebHistory(),
  routes,
});

/**
 * Menu source: the static route table itself, so a page can never be routable but
 * invisible (or vice versa). DefaultLayout consumes this.
 */
export interface MenuEntry {
  path: string;
  title: string;
  codes?: PermissionCode[];
}

export function menuEntries(): MenuEntry[] {
  const layout = routes.find((route) => route.path === "/");
  const children = layout?.children ?? [];
  return children
    .filter((child) => !child.meta?.hidden && child.meta?.title)
    .map((child) => ({
      path: "/" + child.path.replace(/^\//, ""),
      title: child.meta?.title as string,
      codes: child.meta?.codes,
    }));
}

router.beforeEach(async (to) => {
  const auth = useAuthStore();

  if (to.meta.public) {
    // Already signed in: the login page is a dead end, send the user to the console.
    return getToken() && auth.me ? { path: "/dashboard" } : true;
  }

  if (!getToken()) {
    return { path: "/login", query: to.fullPath === "/" ? {} : { redirect: to.fullPath } };
  }

  // Token present but identity unknown (hard refresh / first navigation): load it once.
  if (!auth.me) {
    try {
      await auth.loadMe();
    } catch {
      // The 401 interceptor already cleared the token and redirected; cancel this
      // navigation instead of racing it with a second redirect.
      return false;
    }
  }

  if (!auth.hasAnyCode(to.meta.codes)) {
    return { path: "/403", query: { from: to.fullPath } };
  }

  return true;
});
