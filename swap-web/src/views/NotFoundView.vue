<template>
  <div class="err-wrap">
    <el-result icon="info" title="404" sub-title="页面不存在，或该路径尚未在批次31 落地。">
      <template #extra>
        <div class="err-detail mono">{{ path }}</div>
        <el-button type="primary" @click="goHome">返回看板</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";

const route = useRoute();
const router = useRouter();
const path = computed(() => route.fullPath);

function goHome(): void {
  void router.replace("/dashboard");
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
  color: #909399;
}
</style>
