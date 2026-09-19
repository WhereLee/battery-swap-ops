<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">柜详情</h2>
        <p class="page-subtitle">
          一次聚合 5 类数据：档案 + 仓与电池 + 未处理告警 + 进行中订单 + 最近指令流水
          （各内嵌列表服务端封顶，避免把全量历史拉进浏览器）。
        </p>
      </div>
      <div class="toolbar">
        <el-button size="small" @click="goBack">返回列表</el-button>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-skeleton :loading="loading && !detail" :rows="6" animated>
      <template #default>
        <template v-if="detail">
          <el-card shadow="never">
            <template #header>
              <div class="card-header">
                <span>
                  <span class="mono">{{ detail.cabinet.cabinetNo }}</span>
                  <el-tag size="small" class="gap-left" :type="cabinetStatusTag(detail.cabinet.status)">
                    {{ CABINET_STATUS[detail.cabinet.status] ?? detail.cabinet.status }}
                  </el-tag>
                </span>
                <span class="text-muted">
                  心跳：{{ formatAge(detail.cabinet.heartbeatAgeMs) }}（服务端计算的年龄）
                </span>
              </div>
            </template>
            <el-descriptions :column="3" border size="small">
              <el-descriptions-item label="柜 ID">
                <span class="mono">{{ detail.cabinet.id }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="站点 ID">
                <span class="mono">{{ detail.cabinet.stationId }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="仓位数">
                <span class="mono">{{ detail.cabinet.cellCount }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="代际 bootId">
                <span class="mono">{{ detail.cabinet.lastBootId ?? "-" }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="事件序号水位">
                <span class="mono">{{ detail.cabinet.lastEventSeq ?? "-" }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="最近心跳">
                <span class="mono">{{ formatTime(detail.cabinet.lastHeartbeatTime) }}</span>
              </el-descriptions-item>
            </el-descriptions>
            <div class="text-muted hint">
              序号守卫口径：同柜同 bootId 的事件序号必须递增，跨代（bootId 变化）重放会被拒；
              水位即 lastEventSeq，是重放防护的对照基线。
            </div>
          </el-card>

          <el-card shadow="never" class="block">
            <template #header>
              <span>仓位与电池（{{ detail.cells.length }} 个）</span>
            </template>
            <el-empty v-if="detail.cells.length === 0" description="无仓位数据" :image-size="60" />
            <el-table v-else :data="detail.cells" size="small">
              <el-table-column prop="cellNo" label="仓号" width="80" />
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag size="small" :type="cellStatusTag(row.status)">
                    {{ CELL_STATUS[row.status as number] ?? `未知(${row.status})` }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="电池" width="160">
                <template #default="{ row }">
                  <span class="mono">{{ row.batteryNo ?? "-" }}</span>
                </template>
              </el-table-column>
              <el-table-column label="SOC" width="120">
                <template #default="{ row }">
                  <el-progress
                    v-if="row.soc !== null && row.soc !== undefined"
                    :percentage="row.soc"
                    :stroke-width="10"
                    :status="row.soc >= 90 ? 'success' : undefined"
                  />
                  <span v-else class="text-muted">-</span>
                </template>
              </el-table-column>
              <el-table-column label="锁定订单" width="120">
                <template #default="{ row }">
                  <span v-if="row.lockOrderId" class="mono">#{{ row.lockOrderId }}</span>
                  <span v-else class="text-muted">未锁定</span>
                </template>
              </el-table-column>
            </el-table>
          </el-card>

          <el-row :gutter="16" class="block">
            <el-col :xs="24" :lg="12">
              <el-card shadow="never">
                <template #header>
                  <span>未处理告警（{{ detail.openAlarms.length }} 条）</span>
                </template>
                <el-empty v-if="detail.openAlarms.length === 0" description="无未处理告警" :image-size="60" />
                <el-table v-else :data="detail.openAlarms" size="small">
                  <el-table-column prop="id" label="ID" width="70" />
                  <el-table-column label="类型" width="160">
                    <template #default="{ row }">
                      <el-tag size="small" effect="plain" class="mono">{{ row.alarmType }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="content" label="内容" min-width="160" show-overflow-tooltip />
                  <el-table-column label="时间" width="160">
                    <template #default="{ row }">
                      <span class="mono">{{ formatTime(row.createTime) }}</span>
                    </template>
                  </el-table-column>
                </el-table>
              </el-card>
            </el-col>
            <el-col :xs="24" :lg="12">
              <el-card shadow="never">
                <template #header>
                  <span>进行中订单（{{ detail.activeOrders.length }} 条）</span>
                </template>
                <el-empty v-if="detail.activeOrders.length === 0" description="无进行中订单" :image-size="60" />
                <el-table v-else :data="detail.activeOrders" size="small">
                  <el-table-column label="订单号" width="190">
                    <template #default="{ row }">
                      <el-link type="primary" class="mono" @click="openOrder(row.orderNo)">{{ row.orderNo }}</el-link>
                    </template>
                  </el-table-column>
                  <el-table-column label="类型" width="90">
                    <template #default="{ row }">
                      <span class="mono">{{ row.orderType }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="状态" width="110">
                    <template #default="{ row }">
                      <el-tag size="small" :type="orderStatusTag(row.status)">
                        {{ ORDER_STATUS[row.status as number] ?? `未知(${row.status})` }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="费用" width="100">
                    <template #default="{ row }">
                      <span class="mono">{{ formatFen(row.feeFen) }}</span>
                    </template>
                  </el-table-column>
                </el-table>
              </el-card>
            </el-col>
          </el-row>

          <el-card shadow="never" class="block">
            <template #header>
              <span>最近指令流水（{{ detail.recentCommands.length }} 条，指令幂等靠 commandSeq）</span>
            </template>
            <el-empty v-if="detail.recentCommands.length === 0" description="无指令记录" :image-size="60" />
            <el-table v-else :data="detail.recentCommands" size="small">
              <el-table-column label="动作" width="150">
                <template #default="{ row }">
                  <span class="mono">{{ row.commandAction }}</span>
                </template>
              </el-table-column>
              <el-table-column label="序号" width="90">
                <template #default="{ row }">
                  <span class="mono">{{ row.commandSeq }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="140">
                <template #default="{ row }">
                  <el-tag size="small" :type="commandStatusTag(row.commandStatus)">
                    {{ COMMAND_STATUS[row.commandStatus as number] ?? `未知(${row.commandStatus})` }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="重试次数" width="100">
                <template #default="{ row }">
                  <span class="mono">{{ row.retryCount ?? 0 }}</span>
                </template>
              </el-table-column>
              <el-table-column label="traceId" min-width="200">
                <template #default="{ row }">
                  <span class="mono">{{ row.traceId ?? "-" }}</span>
                </template>
              </el-table-column>
              <el-table-column label="时间" width="170">
                <template #default="{ row }">
                  <span class="mono">{{ formatTime(row.createTime) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </template>
      </template>
    </el-skeleton>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { fetchCabinetDetail } from "../api/views";
import {
  CABINET_STATUS,
  CELL_STATUS,
  COMMAND_STATUS,
  ORDER_STATUS,
  type CabinetDetailVO,
} from "../api/types";
import { formatAge, formatFen, formatTime } from "../utils/format";

const route = useRoute();
const router = useRouter();

const cabinetNo = String(route.params.cabinetNo);
const detail = ref<CabinetDetailVO | null>(null);
const loading = ref(false);

async function load(): Promise<void> {
  loading.value = true;
  try {
    detail.value = await fetchCabinetDetail(cabinetNo);
  } catch {
    // The interceptor reported it (403 out-of-scope station / 400 unknown cabinet).
  } finally {
    loading.value = false;
  }
}

function goBack(): void {
  void router.push("/cabinets");
}

function openOrder(orderNo: string): void {
  void router.push(`/orders/${encodeURIComponent(orderNo)}`);
}

function cabinetStatusTag(value: number): "success" | "warning" | "danger" | "info" {
  switch (value) {
    case 1:
      return "success";
    case 2:
      return "warning";
    case 3:
      return "danger";
    default:
      return "info";
  }
}

function cellStatusTag(value: number): "success" | "info" | "danger" | "warning" {
  switch (value) {
    case 1:
      return "info"; // EMPTY
    case 2:
      return "success"; // OCCUPIED
    case 3:
      return "danger"; // FAULT
    default:
      return "warning"; // DISABLED
  }
}

function orderStatusTag(value: number): "danger" | "warning" | "primary" | "success" | "info" {
  switch (value) {
    case 4:
      return "danger";
    case 3:
      return "warning";
    case 5:
      return "success";
    default:
      return "primary";
  }
}

function commandStatusTag(value: number): "success" | "warning" | "danger" | "info" {
  switch (value) {
    case 2:
      return "success"; // ARRIVED
    case 1:
      return "info"; // PENDING
    case 3:
    case 4:
    case 6:
      return "danger"; // SEND_FAILED / RETRY_EXCEEDED / EXEC_FAILED
    default:
      return "warning"; // SUPERSEDED
  }
}

onMounted(load);
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.gap-left {
  margin-left: 6px;
}

.block {
  margin-top: 16px;
}

.hint {
  margin-top: 10px;
  font-size: 12px;
}
</style>
