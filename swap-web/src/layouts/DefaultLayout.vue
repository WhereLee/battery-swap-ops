<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="brand">
        <div class="brand-title">换电运营平台</div>
        <div class="brand-sub">管理台 · S8</div>
      </div>
      <el-menu :default-active="activePath" router class="menu">
        <el-menu-item v-for="entry in visibleMenu" :key="entry.path" :index="entry.path">
          <span>{{ entry.title }}</span>
        </el-menu-item>
      </el-menu>
      <div class="aside-foot mono">权限码 {{ auth.codes.size }}</div>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-title">{{ currentTitle }}</div>
        <div class="header-right">
          <el-tooltip :content="scopeTooltip" placement="bottom">
            <el-tag :type="auth.dataScoped ? 'warning' : 'success'" size="small" effect="plain">
              {{ auth.dataScoped ? "受限数据范围" : "全量数据范围" }}
            </el-tag>
          </el-tooltip>
          <el-tag size="small" effect="dark" type="info">{{ auth.role || "-" }}</el-tag>
          <span class="header-user">{{ auth.username || "-" }}</span>
          <el-button size="small" text @click="onLogout">退出</el-button>
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { useAuthStore } from "../stores/auth";
import { menuEntries } from "../router";

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();

/**
 * The menu is derived from the route table instead of a second hardcoded list, so a
 * page can never be routable-but-invisible. Entries whose codes the identity does not
 * hold are filtered out here (UX); the backend still enforces them (security).
 */
const entries = menuEntries();
const visibleMenu = computed(() => entries.filter((entry) => auth.hasAnyCode(entry.codes)));
const activePath = computed(() => route.path);
const currentTitle = computed(() => route.meta.title ?? "");

const scopeTooltip = computed(() => {
  const scope = auth.me?.dataScope ?? "-";
  const ids = auth.me?.scopeStationIds ?? [];
  return auth.dataScoped
    ? `dataScope=${scope}；可见站点 ID：${ids.length > 0 ? ids.join(", ") : "（空集，fail-closed：查询恒假）"}`
    : `dataScope=${scope}（不受站点范围限制）`;
});

async function onLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm("确认退出登录？", "提示", { type: "warning" });
  } catch {
    return;
  }
  await auth.logout();
  ElMessage.success("已退出登录");
  void router.replace("/login");
}
</script>

<style scoped>
.layout {
  height: 100%;
}

.aside {
  display: flex;
  flex-direction: column;
  background-color: #1f2d3d;
}

.brand {
  padding: 16px;
  color: #fff;
  border-bottom: 1px solid #263445;
}

.brand-title {
  font-size: 15px;
  font-weight: 600;
}

.brand-sub {
  margin-top: 2px;
  font-size: 11px;
  /* batch39: was #8492a6 = 4.43:1 on the #1f2d3d sidebar; on a dark background the fix is a
     LIGHTER shade, not a darker one. #a8b3c4 = 6.61:1. */
  color: #a8b3c4;
}

/* Element Plus menu theming via CSS vars (the bg/text color props are deprecated). */
.menu {
  flex: 1;
  --el-menu-bg-color: #1f2d3d;
  --el-menu-text-color: #c0c4cc;
  --el-menu-active-color: #ffd04b;
  --el-menu-hover-bg-color: #263445;
  border-right: none;
}

.aside-foot {
  padding: 8px 16px;
  /* batch39: was #5c6b7f = 2.57:1 on #1f2d3d - the worst text on the page, and the one a
     reviewer flagged as looking like a stray debug line. #9aa7b8 = 5.73:1. */
  color: #9aa7b8;
  border-top: 1px solid #263445;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-user {
  font-size: 13px;
  color: #606266;
}

.main {
  padding: 0;
  background-color: #f5f7fa;
}
</style>
