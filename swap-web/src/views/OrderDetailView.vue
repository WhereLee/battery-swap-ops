<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">订单详情</h2>
        <p class="page-subtitle">
          一次请求拿到订单 + 时间线 + 支付/退款流水 + <b>服务端口径</b>的可退金额；
          退款通道（refund / reversal）由后端判定，前端不推算可退额、不挑通道。
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
                  <span class="mono">{{ detail.orderNo }}</span>
                  <el-tag size="small" class="gap-left">{{ orderTypeLabel(detail.orderType) }}</el-tag>
                  <el-tag size="small" class="gap-left" :type="statusTag(detail.status)">
                    {{ ORDER_STATUS[detail.status] ?? detail.statusDesc }}
                  </el-tag>
                </span>
                <span class="actions">
                  <el-button
                    v-for="action in detail.allowedActions"
                    :key="action"
                    v-access="CODES.refundCreate"
                    size="small"
                    :type="action === 'reversal' ? 'warning' : 'danger'"
                    :loading="busyAction === action"
                    @click="openRefund(action)"
                  >
                    {{ ORDER_ACTION_LABEL[action] ?? action }}
                  </el-button>
                  <span v-if="detail.allowedActions.length === 0" class="text-muted">无可退金额，无资金动作</span>
                </span>
              </div>
            </template>

            <el-descriptions :column="3" border size="small">
              <el-descriptions-item label="用户 ID">
                <span class="mono">{{ detail.userId }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="站点 ID">
                <span class="mono">{{ detail.stationId ?? "-" }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="柜 / 仓">
                <el-link
                  v-if="detail.cabinetNo"
                  type="primary"
                  class="mono"
                  @click="openCabinet(detail.cabinetNo)"
                >
                  {{ detail.cabinetNo }}
                </el-link>
                <span v-else class="text-muted">-</span>
                <span class="mono gap-left">#{{ detail.cellNo ?? "-" }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="取出电池">
                <span class="mono">{{ detail.takeBatteryNo ?? "-" }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="归还电池">
                <span class="mono">{{ detail.returnBatteryNo ?? "-" }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="支付方式">
                <span class="mono">{{ detail.payType ?? "-" }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="费用">
                <span class="mono">{{ formatFen(detail.feeFen) }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="券抵扣">
                <span class="mono">{{ formatFen(detail.discountFen) }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="可退金额">
                <span class="mono">{{ formatFen(detail.refundableFen) }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="关闭原因">
                <span class="mono">{{ detail.closeReason ?? "-" }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="预占过期">
                <span class="mono">{{ formatTime(detail.preemptExpireTime) }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="状态描述">
                <span class="mono">{{ detail.statusDesc }}</span>
              </el-descriptions-item>
            </el-descriptions>
          </el-card>

          <el-card shadow="never" class="block">
            <template #header>
              <span>时间线（状态机各步落库时间）</span>
            </template>
            <el-timeline>
              <el-timeline-item
                v-for="step in timeline"
                :key="step.label"
                :timestamp="formatTime(step.time)"
                placement="top"
                :type="step.time ? 'primary' : 'info'"
              >
                {{ step.label }}
              </el-timeline-item>
            </el-timeline>
          </el-card>

          <el-card shadow="never" class="block">
            <template #header>
              <span>支付流水（{{ detail.payments.length }} 条）</span>
            </template>
            <el-empty v-if="detail.payments.length === 0" description="无支付流水" :image-size="60" />
            <el-table v-else :data="detail.payments" size="small">
              <el-table-column label="交易号" min-width="200">
                <template #default="{ row }">
                  <span class="mono">{{ row.tradeNo ?? "-" }}</span>
                </template>
              </el-table-column>
              <el-table-column label="类型" width="140">
                <template #default="{ row }">
                  <span class="mono">{{ row.paymentType }}</span>
                </template>
              </el-table-column>
              <el-table-column label="金额" width="110">
                <template #default="{ row }">
                  <span class="mono">{{ formatFen(row.amountFen) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.status === 1 ? 'success' : 'danger'">
                    {{ row.status === 1 ? "成功" : "失败" }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="时间" width="170">
                <template #default="{ row }">
                  <span class="mono">{{ formatTime(row.createTime) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </el-card>

          <el-card shadow="never" class="block">
            <template #header>
              <span>退款流水（{{ detail.refunds.length }} 条）</span>
            </template>
            <el-empty v-if="detail.refunds.length === 0" description="无退款流水" :image-size="60" />
            <el-table v-else :data="detail.refunds" size="small">
              <el-table-column label="退款号" min-width="200">
                <template #default="{ row }">
                  <span class="mono">{{ row.refundNo }}</span>
                </template>
              </el-table-column>
              <el-table-column label="原因" width="150">
                <template #default="{ row }">
                  <el-tooltip :content="row.reason" placement="top">
                    <el-tag size="small" effect="plain">{{ REFUND_REASON[row.reason] ?? row.reason }}</el-tag>
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column label="金额" width="110">
                <template #default="{ row }">
                  <span class="mono">{{ formatFen(row.amountFen) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : 'warning'">
                    {{ row.status === "SUCCESS" ? "已到账" : "待执行" }}
                  </el-tag>
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

    <el-dialog v-model="refundVisible" :title="refundTitle" width="520px">
      <el-alert :closable="false" type="warning" class="refund-alert" :title="refundHint" />
      <el-form label-width="110px">
        <el-form-item label="订单号">
          <span class="mono">{{ detail?.orderNo }}</span>
        </el-form-item>
        <el-form-item label="可退金额">
          <span class="mono">{{ formatFen(detail?.refundableFen) }}</span>
        </el-form-item>
        <el-form-item label="退款金额（分）">
          <el-input v-model="refundAmount" placeholder="留空＝按服务端可退金额全额退" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="refundVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRefund">确认退款</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { CODES } from "../api/permissions";
import { fetchOrderDetail, refundOrder, reversalOrder } from "../api/views";
import { ORDER_ACTION_LABEL, ORDER_STATUS, REFUND_REASON, type OrderDetailVO } from "../api/types";
import { formatFen, formatTime } from "../utils/format";
import { orderTypeLabel } from "../utils/labels";

const route = useRoute();
const router = useRouter();

const orderNo = String(route.params.orderNo);
const detail = ref<OrderDetailVO | null>(null);
const loading = ref(false);

const timeline = computed(() => {
  const data = detail.value;
  return [
    { label: "创建订单", time: data?.createTime ?? null },
    { label: "开仓", time: data?.openTime ?? null },
    { label: "取电", time: data?.takeTime ?? null },
    { label: "还电", time: data?.returnTime ?? null },
    { label: "完成", time: data?.completeTime ?? null },
    { label: "取消", time: data?.cancelTime ?? null },
  ];
});

async function load(): Promise<void> {
  loading.value = true;
  try {
    detail.value = await fetchOrderDetail(orderNo);
  } catch {
    // The interceptor reported it (403 out-of-scope / 400 unknown order).
  } finally {
    loading.value = false;
  }
}

function goBack(): void {
  void router.push("/orders");
}

function openCabinet(cabinetNo: string): void {
  void router.push(`/cabinets/${encodeURIComponent(cabinetNo)}`);
}

function statusTag(value: number): "danger" | "warning" | "primary" | "success" | "info" {
  switch (value) {
    case 1:
    case 2:
      return "primary";
    case 3:
      return "warning";
    case 4:
      return "danger";
    case 5:
      return "success";
    case 6:
    case 7:
      return "info";
    default:
      return "danger";
  }
}

// ---------- refund ----------
const refundVisible = ref(false);
const refundAction = ref("");
const refundAmount = ref("");
const submitting = ref(false);
const busyAction = ref("");

const refundTitle = computed(() => ORDER_ACTION_LABEL[refundAction.value] ?? "退款");
const refundHint = computed(() =>
  refundAction.value === "reversal"
    ? "已完成订单资金已结算入账（含分账），只能走冲正：退款成功同时写 REFUND_REVERSAL 负向分账行。"
    : "进行中/异常订单走普通退款（退入钱包余额）。同订单同原因幂等，重复提交不会二次退款。",
);

function openRefund(action: string): void {
  refundAction.value = action;
  refundAmount.value = "";
  refundVisible.value = true;
}

async function submitRefund(): Promise<void> {
  const amount = refundAmount.value.trim() === "" ? undefined : Number(refundAmount.value);
  if (amount !== undefined && (!Number.isFinite(amount) || amount <= 0)) {
    ElMessage.warning("退款金额需为正整数（单位：分）");
    return;
  }
  submitting.value = true;
  busyAction.value = refundAction.value;
  try {
    if (refundAction.value === "reversal") {
      await reversalOrder(orderNo, amount);
    } else {
      await refundOrder(orderNo, amount);
    }
    ElMessage.success(`订单 ${orderNo} 退款已受理`);
    refundVisible.value = false;
    await load();
  } catch {
    // Interceptor reported it (over-refund rejected server-side with 400).
  } finally {
    submitting.value = false;
    busyAction.value = "";
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

.refund-alert {
  margin-bottom: 12px;
}
</style>
