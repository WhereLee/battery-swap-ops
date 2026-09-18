<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">运营看板</h2>
        <p class="page-subtitle">
          口径：满电/站点可用来自分配池（Redis，与分配同源），电池与订单计数走 DB 事实源；
          全量身份聚合结果缓存 60s，<b>受限身份不共享全局缓存、按本域直算</b>。
        </p>
      </div>
      <div class="toolbar">
        <span v-if="data" class="text-muted mono">生成于 {{ formatTime(data.generatedAt) }}</span>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert
      v-if="data?.dataScoped"
      type="warning"
      :closable="false"
      class="scope-alert"
      title="当前身份为受限数据范围（STATION）：以下全部指标仅统计你可见站点内的资产与订单，不等于全网口径。"
    />

    <el-skeleton :loading="loading && !data" :rows="4" animated>
      <template #default>
        <el-row v-if="data" :gutter="16">
          <el-col :xs="24" :sm="12" :lg="6" class="metric-col">
            <el-card shadow="never">
              <div class="metric-value">{{ formatPercent(data.fullBatteryRate) }}</div>
              <div class="metric-caption">
                满电保有率（满电 {{ data.fullBatteries }} / 在役 {{ data.totalBatteries }}）
              </div>
            </el-card>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="6" class="metric-col">
            <el-card shadow="never">
              <div class="metric-value">{{ formatPercent(data.stationAvailabilityRate) }}</div>
              <div class="metric-caption">
                站点可用率（可用 {{ data.availableStations }} / 启用 {{ data.totalStations }}）
              </div>
            </el-card>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="6" class="metric-col">
            <el-card shadow="never">
              <div class="metric-value">{{ data.completedToday }}</div>
              <div class="metric-caption">今日完成换电单（自然日起算）</div>
            </el-card>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="6" class="metric-col">
            <el-card shadow="never">
              <div class="metric-value">{{ formatTurnover(data.turnoverRate) }}</div>
              <div class="metric-caption">电池周转（今日完成单 / 在役电池，次·块⁻¹）</div>
            </el-card>
          </el-col>
        </el-row>

        <el-card v-if="data" shadow="never" class="block">
          <template #header>
            <span>不可用站点（{{ data.unavailableStations.length }}）</span>
          </template>
          <div v-if="data.unavailableStations.length === 0" class="text-muted">
            全部启用站点当前均有可取满电电池。
          </div>
          <div v-else class="tag-list">
            <el-tag v-for="no in data.unavailableStations" :key="no" type="danger" effect="plain" class="mono">
              {{ no }}
            </el-tag>
          </div>
          <div class="text-muted hint">
            判定规则：站点下全部换电柜的"可取满电仓位"合计为 0 即视为不可用。
          </div>
        </el-card>
      </template>
    </el-skeleton>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { fetchDashboard } from "../api/views";
import type { DashboardVO } from "../api/types";
import { formatPercent, formatTime, formatTurnover } from "../utils/format";

const data = ref<DashboardVO | null>(null);
const loading = ref(false);

async function load(): Promise<void> {
  loading.value = true;
  try {
    data.value = await fetchDashboard();
  } catch {
    // Interceptor already reported the failure; keep the previous payload on screen so
    // a transient 429/5xx does not blank the console.
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.scope-alert {
  margin-bottom: 12px;
}

/* Cards wrap to 2-up then 1-up on narrow viewports instead of clipping the numbers. */
.metric-col {
  margin-bottom: 16px;
}

.block {
  margin-top: 16px;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.hint {
  margin-top: 10px;
  font-size: 12px;
}
</style>
