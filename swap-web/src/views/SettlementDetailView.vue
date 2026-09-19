<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">结算单详情</h2>
        <p class="page-subtitle">
          append-only 分账流水：<span class="mono">ORDER</span> 正行、<span class="mono">REFUND_REVERSAL</span> 负行、
          <span class="mono">ARREARS_SETTLE</span> 补缴行；<span class="mono">event_key</span> 唯一即幂等闸，
          事件重放不会二次入账。
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
                  <span class="mono">{{ detail.statement.statementNo }}</span>
                  <el-tag size="small" class="gap-left" :type="statusTag(detail.statement.status)">
                    {{ SETTLEMENT_STATUS[detail.statement.status] ?? detail.statement.status }}
                  </el-tag>
                </span>
                <span class="actions">
                  <el-button
                    v-for="action in detail.statement.allowedActions"
                    :key="action"
                    v-access="CODES.settlementManage"
                    size="small"
                    :type="action === 'paid' ? 'warning' : 'primary'"
                    :loading="busyAction === action"
                    @click="onStep(action)"
                  >
                    {{ SETTLEMENT_ACTION_LABEL[action] ?? action }}
                  </el-button>
                  <span v-if="detail.statement.allowedActions.length === 0" class="text-muted">
                    已打款：单据不可变（迟到流水进下一期）
                  </span>
                </span>
              </div>
            </template>

            <el-descriptions :column="3" border size="small">
              <el-descriptions-item label="代理">
                {{ detail.statement.agentName ?? "直营" }}
                <span v-if="detail.statement.agentId" class="text-muted mono"> #{{ detail.statement.agentId }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="结算周期" :span="2">
                <span class="mono">
                  {{ formatTime(detail.statement.periodStart) }} ~ {{ formatTime(detail.statement.periodEnd) }}
                </span>
              </el-descriptions-item>
              <el-descriptions-item label="单量">
                <span class="mono">{{ detail.statement.orderCount ?? 0 }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="分账基数">
                <span class="mono">{{ formatFen(detail.statement.baseAmountFen) }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="券补贴（平台承担）">
                <span class="mono">{{ formatFen(detail.statement.subsidyFen) }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="代理应得">
                <span class="mono">{{ formatFen(detail.statement.agentAmountFen) }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="平台留存">
                <span class="mono">{{ formatFen(detail.statement.platformAmountFen) }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="守恒校验">
                <el-tag size="small" :type="conserved ? 'success' : 'danger'">
                  {{ conserved ? "代理 + 平台 = 基数" : "不守恒（需排查）" }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="生成">
                {{ detail.statement.generatedBy ?? "-" }} / {{ formatTime(detail.statement.generatedTime) }}
              </el-descriptions-item>
              <el-descriptions-item label="确认">
                {{ detail.statement.confirmedBy ?? "-" }} / {{ formatTime(detail.statement.confirmedTime) }}
              </el-descriptions-item>
              <el-descriptions-item label="打款">
                {{ detail.statement.paidBy ?? "-" }} / {{ formatTime(detail.statement.paidTime) }}
              </el-descriptions-item>
            </el-descriptions>
            <div class="text-muted hint">
              "守恒校验"只做展示层断言（代理 + 平台 = 基数）：写入侧由
              <span class="mono">SettlementService</span> 用 long 运算 + platform=base-agent 保证不丢分，
              对账任务另有不变量兜底；页面不改数据。
            </div>
          </el-card>

          <el-card shadow="never" class="block">
            <template #header>
              <span>分账流水（{{ detail.lines.length }} 行；负行为退款冲正）</span>
            </template>
            <el-empty v-if="detail.lines.length === 0" description="该单据下无流水" :image-size="60" />
            <el-table v-else :data="detail.lines" size="small" :row-class-name="lineRowClass">
              <el-table-column prop="id" label="ID" width="70" />
              <el-table-column label="订单号" width="200">
                <template #default="{ row }">
                  <el-link type="primary" class="mono" @click="openOrder(row.orderNo)">{{ row.orderNo }}</el-link>
                </template>
              </el-table-column>
              <el-table-column label="事件" width="140">
                <template #default="{ row }">
                  <el-tooltip :content="row.eventType" placement="top">
                    <el-tag size="small" :type="row.baseAmountFen < 0 ? 'danger' : 'primary'" effect="plain">
                      {{ SETTLEMENT_EVENT_TYPE[row.eventType] ?? row.eventType }}
                    </el-tag>
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column label="基数口径" width="140">
                <template #default="{ row }">
                  <el-tooltip :content="row.baseType" placement="top">
                    <span>{{ SETTLEMENT_BASE_TYPE[row.baseType] ?? row.baseType }}</span>
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column label="基数" width="110">
                <template #default="{ row }">
                  <span class="mono">{{ formatFen(row.baseAmountFen) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="代理分成" width="110">
                <template #default="{ row }">
                  <span class="mono">{{ formatFen(row.agentShareFen) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="平台分成" width="110">
                <template #default="{ row }">
                  <span class="mono">{{ formatFen(row.platformShareFen) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="券补贴" width="100">
                <template #default="{ row }">
                  <span class="mono">{{ formatFen(row.subsidyFen) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="站点" width="90">
                <template #default="{ row }">
                  <span class="mono">{{ row.stationId ?? "-" }}</span>
                </template>
              </el-table-column>
              <el-table-column label="入账时间" width="170">
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
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { CODES } from "../api/permissions";
import { fetchSettlementDetail, settlementStep } from "../api/views";
import {
  SETTLEMENT_ACTION_LABEL,
  SETTLEMENT_BASE_TYPE,
  SETTLEMENT_EVENT_TYPE,
  SETTLEMENT_STATUS,
  type SettlementDetailVO,
  type SettlementLineVO,
} from "../api/types";
import { formatFen, formatTime } from "../utils/format";

const route = useRoute();
const router = useRouter();

const settlementId = Number(route.params.id);
const detail = ref<SettlementDetailVO | null>(null);
const loading = ref(false);
const busyAction = ref("");

/** Display-only invariant: agent share + platform share must equal the split base. */
const conserved = computed(() => {
  const statement = detail.value?.statement;
  if (!statement) {
    return false;
  }
  return (statement.agentAmountFen ?? 0) + (statement.platformAmountFen ?? 0) === (statement.baseAmountFen ?? 0);
});

async function load(): Promise<void> {
  loading.value = true;
  try {
    detail.value = await fetchSettlementDetail(settlementId);
  } catch {
    // The interceptor reported it (400 unknown id).
  } finally {
    loading.value = false;
  }
}

function goBack(): void {
  void router.push("/settlements");
}

function openOrder(orderNo: string): void {
  void router.push(`/orders/${encodeURIComponent(orderNo)}`);
}

function lineRowClass({ row }: { row: SettlementLineVO }): string {
  return (row.baseAmountFen ?? 0) < 0 ? "row-negative" : "";
}

async function onStep(action: string): Promise<void> {
  const statement = detail.value?.statement;
  if (!statement) {
    return;
  }
  const label = SETTLEMENT_ACTION_LABEL[action] ?? action;
  try {
    await ElMessageBox.confirm(
      action === "paid"
        ? `确认将结算单 ${statement.statementNo} 标记为已打款？打款后单据不可变，不可撤销。`
        : `确认结算单 ${statement.statementNo} 的金额（代理应得 ${formatFen(statement.agentAmountFen)}）？确认后进入待打款。`,
      label,
      { type: "warning", confirmButtonText: label, cancelButtonText: "取消" },
    );
  } catch {
    return;
  }
  busyAction.value = action;
  try {
    await settlementStep(statement.id, action as "confirm" | "paid");
    ElMessage.success(`${statement.statementNo} 已${label}`);
    await load();
  } catch {
    // Interceptor reported it; a 400 means the statement moved on (CAS).
    await load();
  } finally {
    busyAction.value = "";
  }
}

function statusTag(value: number): "warning" | "primary" | "success" | "info" {
  switch (value) {
    case 1:
      return "warning";
    case 2:
      return "primary";
    case 3:
      return "success";
    default:
      return "info";
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

.actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.block {
  margin-top: 16px;
}

.hint {
  margin-top: 10px;
  font-size: 12px;
}

/* Reversal lines are negative; tint them so a reader does not mistake them for income. */
:deep(.row-negative) {
  --el-table-tr-bg-color: #fef0f0;
}
</style>
