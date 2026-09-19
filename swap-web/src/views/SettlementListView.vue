<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">结算单</h2>
        <p class="page-subtitle">
          顺序批模型：未挂单流水按代理归集为一张结算单，<b>PAID 不可变</b>——迟到流水、退款冲正、
          欠费补缴一律进下一期。金额一律整数分，聚合结果由服务端给出，本页不做任何加减。
        </p>
      </div>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div class="toolbar">
      <el-select v-model="status" size="small" style="width: 150px" @change="onFilterChange">
        <el-option label="全部状态" :value="-1" />
        <el-option v-for="(label, code) in SETTLEMENT_STATUS" :key="code" :label="label" :value="Number(code)" />
      </el-select>
      <span class="text-muted">共 {{ total }} 张</span>
      <span class="text-muted">状态推进：生成 → 确认 → 打款（每步各自鉴权 + 各自审计）</span>
    </div>

    <el-table v-loading="loading" :data="rows" size="small" row-key="id" class="table">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="结算单号" width="220">
        <template #default="{ row }">
          <el-link type="primary" class="mono" @click="openDetail(row.id)">{{ row.statementNo }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="代理" width="140">
        <template #default="{ row }">
          <span>{{ row.agentName ?? "-" }}</span>
          <span v-if="row.agentId" class="text-muted mono"> #{{ row.agentId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="结算周期" width="330">
        <template #default="{ row }">
          <span class="mono">{{ formatTime(row.periodStart) }} ~ {{ formatTime(row.periodEnd) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="单量" width="80">
        <template #default="{ row }">
          <span class="mono">{{ row.orderCount ?? 0 }}</span>
        </template>
      </el-table-column>
      <el-table-column label="分账基数" width="110">
        <template #default="{ row }">
          <span class="mono">{{ formatFen(row.baseAmountFen) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="代理应得" width="110">
        <template #default="{ row }">
          <span class="mono">{{ formatFen(row.agentAmountFen) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="平台留存" width="110">
        <template #default="{ row }">
          <span class="mono">{{ formatFen(row.platformAmountFen) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="券补贴" width="100">
        <template #default="{ row }">
          <span class="mono">{{ formatFen(row.subsidyFen) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)">
            {{ SETTLEMENT_STATUS[row.status as number] ?? `未知(${row.status})` }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button
            v-for="action in row.allowedActions"
            :key="action"
            v-access="CODES.settlementManage"
            size="small"
            :type="action === 'paid' ? 'warning' : 'primary'"
            link
            :loading="busyId === row.id"
            @click="onStep(row, action)"
          >
            {{ SETTLEMENT_ACTION_LABEL[action] ?? action }}
          </el-button>
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
import { ElMessage, ElMessageBox } from "element-plus";
import { CODES } from "../api/permissions";
import { fetchSettlementPage, settlementStep } from "../api/views";
import { SETTLEMENT_ACTION_LABEL, SETTLEMENT_STATUS, type SettlementVO } from "../api/types";
import { formatFen, formatTime } from "../utils/format";

const router = useRouter();

const rows = ref<SettlementVO[]>([]);
const loading = ref(false);
const busyId = ref(-1);
const page = ref(1);
const limit = ref(20);
const total = ref(0);
const status = ref(-1);

async function load(): Promise<void> {
  loading.value = true;
  try {
    const result = await fetchSettlementPage({
      page: page.value,
      limit: limit.value,
      status: status.value === -1 ? undefined : status.value,
    });
    rows.value = result.list;
    total.value = result.total;
  } catch {
    // The interceptor reported it; keep the last good page.
  } finally {
    loading.value = false;
  }
}

function onFilterChange(): void {
  page.value = 1;
  void load();
}

function openDetail(id: number): void {
  void router.push(`/settlements/${id}`);
}

async function onStep(row: SettlementVO, action: string): Promise<void> {
  const label = SETTLEMENT_ACTION_LABEL[action] ?? action;
  try {
    await ElMessageBox.confirm(
      action === "paid"
        ? `确认将结算单 ${row.statementNo} 标记为已打款？打款后单据不可变，不可撤销。`
        : `确认结算单 ${row.statementNo} 的金额（代理应得 ${formatFen(row.agentAmountFen)}）？确认后进入待打款。`,
      label,
      { type: "warning", confirmButtonText: label, cancelButtonText: "取消" },
    );
  } catch {
    return;
  }
  busyId.value = row.id;
  try {
    await settlementStep(row.id, action as "confirm" | "paid");
    ElMessage.success(`${row.statementNo} 已${label}`);
    await load();
  } catch {
    // Interceptor reported it; a 400 means the statement moved on (CAS) — the reload shows it.
    await load();
  } finally {
    busyId.value = -1;
  }
}

function statusTag(value: number): "warning" | "primary" | "success" | "info" {
  switch (value) {
    case 1:
      return "warning"; // GENERATED
    case 2:
      return "primary"; // CONFIRMED
    case 3:
      return "success"; // PAID
    default:
      return "info";
  }
}

onMounted(load);
</script>

<style scoped>
.table {
  margin-top: 12px;
}
</style>
