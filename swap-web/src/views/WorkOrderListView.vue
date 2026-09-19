<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">工单</h2>
        <p class="page-subtitle">
          列表行带后端算好的 <span class="mono">allowedActions</span>（由工单状态机推导），
          点进详情执行五步动作链；受限身份只看到本站点设备的工单（服务端过滤，越域详情 403）。
        </p>
      </div>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div class="toolbar">
      <el-select v-model="status" size="small" style="width: 160px" @change="onFilterChange">
        <el-option label="全部状态" :value="-1" />
        <el-option v-for="(label, code) in WORK_ORDER_STATUS" :key="code" :label="label" :value="Number(code)" />
      </el-select>
      <span class="text-muted">共 {{ total }} 条</span>
      <span class="text-muted">超期行以红色标识（SLA 已破，来自后端 slaBreached 标记）</span>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      size="small"
      row-key="id"
      class="table"
      :row-class-name="rowClass"
    >
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="工单号" width="200">
        <template #default="{ row }">
          <el-link type="primary" class="mono" @click="openDetail(row.id)">{{ row.woNo }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
      <el-table-column label="设备" width="170">
        <template #default="{ row }">
          <div class="mono">{{ row.deviceNo }}</div>
          <div class="text-muted">{{ row.deviceType }}</div>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="130">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" type="info">{{ workOrderSourceLabel(row.source) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="严重级" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="severityTag(row.severity)">{{ severityLabel(row.severity) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)">
            {{ WORK_ORDER_STATUS[row.status as number] ?? `未知(${row.status})` }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="处理人" width="90">
        <template #default="{ row }">
          <span v-if="row.handlerId" class="mono">{{ row.handlerId }}</span>
          <span v-else class="text-muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="SLA 截止" width="170">
        <template #default="{ row }">
          <span class="mono">{{ formatTime(row.slaDeadline) }}</span>
          <el-tag v-if="row.slaBreached === 1" type="danger" size="small" class="sla-tag">已超期</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">
          <span class="mono">{{ formatTime(row.createTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="可执行动作" width="150" fixed="right">
        <template #default="{ row }">
          <template v-if="row.allowedActions && row.allowedActions.length > 0">
            <el-tag v-for="action in row.allowedActions" :key="action" size="small" effect="plain" class="action-tag">
              {{ WORK_ORDER_ACTION_LABEL[action] ?? action }}
            </el-tag>
          </template>
          <span v-else class="text-muted">已关闭</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="openDetail(row.id)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="limit"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        background
        size="small"
        @current-change="load"
        @size-change="onFilterChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { fetchWorkOrderPage } from "../api/views";
import { WORK_ORDER_ACTION_LABEL, WORK_ORDER_STATUS, type WorkOrderVO } from "../api/types";
import { formatTime } from "../utils/format";
import { severityLabel, workOrderSourceLabel } from "../utils/labels";

const router = useRouter();

const rows = ref<WorkOrderVO[]>([]);
const loading = ref(false);
const page = ref(1);
const limit = ref(20);
const total = ref(0);
/** -1 is a UI-only sentinel meaning "no filter"; the API takes undefined. */
const status = ref(-1);

async function load(): Promise<void> {
  loading.value = true;
  try {
    const result = await fetchWorkOrderPage({
      page: page.value,
      limit: limit.value,
      status: status.value === -1 ? undefined : status.value,
    });
    rows.value = result.list;
    total.value = result.total;
  } catch {
    // The interceptor reported it; keep the last good page instead of blanking the table.
  } finally {
    loading.value = false;
  }
}

function onFilterChange(): void {
  page.value = 1;
  void load();
}

function openDetail(id: number): void {
  void router.push(`/work-orders/${id}`);
}

function rowClass({ row }: { row: WorkOrderVO }): string {
  return row.slaBreached === 1 && row.status !== 6 ? "row-breached" : "";
}

function severityTag(severity: string): "danger" | "warning" | "info" {
  return severity === "HIGH" ? "danger" : severity === "MEDIUM" ? "warning" : "info";
}

function statusTag(value: number): "danger" | "warning" | "primary" | "success" | "info" {
  switch (value) {
    case 1:
      return "danger"; // OPEN
    case 2:
      return "warning"; // TRIAGED
    case 3:
      return "primary"; // ASSIGNED
    case 4:
      return "primary"; // HANDLING
    case 5:
      return "success"; // VERIFIED
    default:
      return "info"; // CLOSED / unknown
  }
}

onMounted(load);
</script>

<style scoped>
.table {
  margin-top: 12px;
}

.sla-tag {
  margin-left: 6px;
}

.action-tag {
  margin-right: 4px;
}

/* SLA breach is the one thing an ops lead scans for; keep it visible but not alarming-only. */
:deep(.row-breached) {
  --el-table-tr-bg-color: #fef0f0;
}
</style>
