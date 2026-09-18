<template>
  <div class="err-wrap">
    <el-result icon="warning" title="403" sub-title="当前身份没有访问该页面的权限码，或目标资源不在你的数据范围内。">
      <template #extra>
        <div class="err-detail mono">
          <div>身份：{{ auth.role || "-" }}（{{ auth.username || "-" }}）</div>
          <div>数据范围：{{ auth.dataScoped ? "受限（STATION）" : "全量（ALL）" }}</div>
          <div v-if="from">被拒路径：{{ from }}</div>
        </div>
        <el-button type="primary" @click="goHome">返回看板</el-button>
        <el-button @click="onLogout">切换账号</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuthStore } from "../stores/auth";

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();

const from = computed(() => (typeof route.query.from === "string" ? route.query.from : ""));

function goHome(): void {
  void router.replace("/dashboard");
}

async function onLogout(): Promise<void> {
  await auth.logout();
  void router.replace("/login");
}
</script>

<style scoped>
.err-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.err-detail {
  margin-bottom: 16px;
  line-height: 1.8;
  color: #909399;
  text-align: left;
}
</style>
