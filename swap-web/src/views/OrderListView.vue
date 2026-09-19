<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">换电订单</h2>
        <p class="page-subtitle">
          列表由视图层收口：柜号一次批量解析、可退金额取资金口径（<b>不在此页做金额加减</b>），
          且<b>不下发</b> idemKey 等内部列。退款/冲正按钮由后端
          <span class="mono">allowedActions</span> 判定通道，前端只按码渲染。
        </p>
      </div>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div class="toolbar">
      <el-input
        v-model="orderNo"
        size="small"
        style="width: 220px"
        placeholder="订单号（模糊）"
        clearable
        @keyup.enter="onFilterChange"
        @clear="onFilterChange"
      />
      <el-select v-model="status" size="small" style="width: 150px" @change="onFilterChange">
        <el-option label="全部状态" :value="-1" />
        <el-option v-for="(label, code) in ORDER_STATUS" :key="code" :label="label" :value="Number(code)" />
      </el-select>
      <el-input
        v-model="userId"
        size="small"
        style="width: 160px"
        placeholder="用户 ID（精确）"
        clearable
        @keyup.enter="onFilterChange"
        @clear="onFilterChange"
      />
      <el-button size="small" type="primary" @click="onFilterChange">查询</el-button>
      <span class="text-muted">共 {{ total }} 条</span>
    </div>

    <el-table v-loading="loading" :data="rows" size="small" row-key="orderNo" class="table">
      <el-table-column label="订单号" width="210">
        <template #default="{ row }">
          <el-link type="primary" class="mono" @click="openDetail(row.orderNo)">{{ row.orderNo }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="80">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" type="info">{{ row.orderType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="用户" width="90">
        <template #default="{ row }">
          <span class="mono">{{ row.userId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="柜" width="140">
        <template #default="{ row }">
          <el-link
            v-if="row.cabinetNo"
            type="primary"
            class="mono"
            @click="openCabinet(row.cabinetNo)"
          >
            {{ row.cabinetNo }}
          </el-link>
          <span v-else class="text-muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)">
            {{ ORDER_STATUS[row.status as number] ?? `未知(${row.status})` }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="费用" width="100">
        <template #default="{ row }">
          <span class="mono">{{ formatFen(row.feeFen) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="券抵扣" width="100">
        <template #default="{ row }">
          <span class="mono">{{ formatFen(row.discountFen) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="支付方式" width="100">
        <template #default="{ row }">
          <span class="mono">{{ row.payType || "-" }}</span>
        </template>
      </el-table-column>
      <el-table-column label="可退金额" width="110">
        <template #default="{ row }">
          <span class="mono" :class="{ 'text-muted': row.refundableFen === 0 }">
            {{ formatFen(row.refundableFen) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">
          <span class="mono">{{ formatTime(row.createTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="退款通道" width="130" fixed="right">
        <template #default="{ row }">
          <template v-if="row.allowedActions && row.allowedActions.length > 0">
            <el-button
              v-for="action in row.allowedActions"
              :key="action"
              v-access="CODES.refundCreate"
              size="small"
              :type="action === 'reversal' ? 'warning' : 'danger'"
              link
              @click="openRefund(row, action)"
            >
              {{ ORDER_ACTION_LABEL[action] ?? action }}
            </el-button>
          </template>
          <span v-else class="text-muted">不可退</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="openDetail(row.orderNo)">详情</el-button>
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

    <el-dialog v-model="refundVisible" :title="refundTitle" width="520px">
      <el-alert :closable="false" type="warning" class="refund-alert" :title="refundHint" />
      <el-form label-width="100px">
        <el-form-item label="订单号">
          <span class="mono">{{ refundTarget?.orderNo }}</span>
        </el-form-item>
        <el-form-item label="可退金额">
          <span class="mono">{{ formatFen(refundTarget?.refundableFen) }}</span>
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
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { CODES } from "../api/permissions";
import { fetchOrderPage, refundOrder, reversalOrder } from "../api/views";
import { ORDER_ACTION_LABEL, ORDER_STATUS, type OrderListItemVO } from "../api/types";
import { formatFen, formatTime } from "../utils/format";

const router = useRouter();

const rows = ref<OrderListItemVO[]>([]);
const loading = ref(false);
const page = ref(1);
const limit = ref(20);
const total = ref(0);
const status = ref(-1);
const orderNo = ref("");
const userId = ref("");

async function load(): Promise<void> {
  loading.value = true;
  try {
    const parsedUserId = userId.value.trim() === "" ? undefined : Number(userId.value);
    const result = await fetchOrderPage({
      page: page.value,
      limit: limit.value,
      status: status.value === -1 ? undefined : status.value,
      orderNo: orderNo.value.trim() || undefined,
      userId: parsedUserId !== undefined && Number.isFinite(parsedUserId) ? parsedUserId : undefined,
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

function openDetail(no: string): void {
  void router.push(`/orders/${encodeURIComponent(no)}`);
}

function openCabinet(cabinetNo: string): void {
  void router.push(`/cabinets/${encodeURIComponent(cabinetNo)}`);
}

function statusTag(value: number): "danger" | "warning" | "primary" | "success" | "info" {
  switch (value) {
    case 1:
    case 2:
      return "primary"; // PENDING_OPEN / OPENED
    case 3:
      return "warning"; // TAKEN
    case 4:
      return "danger"; // OVERDUE
    case 5:
      return "success"; // COMPLETED
    case 6:
    case 7:
      return "info"; // CANCELLED / TIMEOUT_CLOSED
    default:
      return "danger"; // EXCEPTION / unknown
  }
}

// ---------- refund ----------
const refundVisible = ref(false);
const refundTarget = ref<OrderListItemVO | null>(null);
const refundAction = ref("");
const refundAmount = ref("");
const submitting = ref(false);

const refundTitle = computed(() => ORDER_ACTION_LABEL[refundAction.value] ?? "退款");
const refundHint = computed(() =>
  refundAction.value === "reversal"
    ? "已完成订单资金已结算入账（含分账），只能走冲正：退款成功同时写 REFUND_REVERSAL 负向分账行。"
    : "进行中/异常订单走普通退款（退入钱包余额）。同订单同原因幂等，重复提交不会二次退款。",
);

function openRefund(row: OrderListItemVO, action: string): void {
  refundTarget.value = row;
  refundAction.value = action;
  refundAmount.value = "";
  refundVisible.value = true;
}

async function submitRefund(): Promise<void> {
  const target = refundTarget.value;
  if (!target) {
    return;
  }
  const amount = refundAmount.value.trim() === "" ? undefined : Number(refundAmount.value);
  if (amount !== undefined && (!Number.isFinite(amount) || amount <= 0)) {
    ElMessage.warning("退款金额需为正整数（单位：分）");
    return;
  }
  submitting.value = true;
  try {
    if (refundAction.value === "reversal") {
      await reversalOrder(target.orderNo, amount);
    } else {
      await refundOrder(target.orderNo, amount);
    }
    ElMessage.success(`订单 ${target.orderNo} 退款已受理`);
    refundVisible.value = false;
    await load();
  } catch {
    // Interceptor reported it (over-refund is rejected server-side with 400).
  } finally {
    submitting.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.table {
  margin-top: 12px;
}

.refund-alert {
  margin-bottom: 12px;
}
</style>
