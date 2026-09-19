<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">工单详情</h2>
        <p class="page-subtitle">
          五步动作链：分诊 → 派单 → 处置 → 验收 → 关闭。按钮是否出现由后端
          <span class="mono">allowedActions</span> 决定，权限码由 <span class="mono">v-access</span> 过滤；
          并发下抢不到状态跃迁的一端会收到 400（CAS），刷新即可看到真实状态。
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
                  <span class="mono">{{ detail.order.woNo }}</span>
                  <el-tag size="small" class="gap-left" :type="severityTag(detail.order.severity)">
                    {{ detail.order.severity }}
                  </el-tag>
                  <el-tag size="small" class="gap-left" :type="statusTag(detail.order.status)">
                    {{ WORK_ORDER_STATUS[detail.order.status] ?? detail.order.status }}
                  </el-tag>
                  <el-tag v-if="detail.order.slaBreached === 1" size="small" type="danger" class="gap-left">
                    SLA 已超期
                  </el-tag>
                </span>
                <span class="actions">
                  <el-button
                    v-for="action in detail.order.allowedActions"
                    :key="action"
                    v-access="CODES.workOrderManage"
                    size="small"
                    type="primary"
                    :loading="busyAction === action"
                    @click="openAction(action)"
                  >
                    {{ WORK_ORDER_ACTION_LABEL[action] ?? action }}
                  </el-button>
                  <span v-if="detail.order.allowedActions.length === 0" class="text-muted">工单已关闭，无可用动作</span>
                </span>
              </div>
            </template>

            <el-descriptions :column="3" border size="small">
              <el-descriptions-item label="工单 ID">
                <span class="mono">{{ detail.order.id }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="来源">
                <span class="mono">{{ detail.order.source }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="报障用户">
                <span class="mono">{{ detail.order.reporterUserId ?? "-" }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="设备类型">
                <span class="mono">{{ detail.order.deviceType }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="设备编号">
                <el-link
                  v-if="detail.order.deviceType === 'CABINET'"
                  type="primary"
                  class="mono"
                  @click="openCabinet(detail.order.deviceNo)"
                >
                  {{ detail.order.deviceNo }}
                </el-link>
                <span v-else class="mono">{{ detail.order.deviceNo }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="站点 ID">
                <span class="mono">{{ detail.order.stationId ?? "-" }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="处理人">
                <span class="mono">{{ detail.order.handlerId ?? "-" }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="SLA 截止">
                <span class="mono">{{ formatTime(detail.order.slaDeadline) }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="验收时间">
                <span class="mono">{{ formatTime(detail.order.verifyTime) }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="创建时间">
                <span class="mono">{{ formatTime(detail.order.createTime) }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="关闭时间">
                <span class="mono">{{ formatTime(detail.order.closeTime) }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="最近更新">
                <span class="mono">{{ formatTime(detail.order.updateTime) }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="标题" :span="3">{{ detail.order.title }}</el-descriptions-item>
              <el-descriptions-item label="描述" :span="3">
                {{ detail.order.description || "-" }}
              </el-descriptions-item>
              <el-descriptions-item label="备注" :span="3">{{ detail.order.remark || "-" }}</el-descriptions-item>
            </el-descriptions>
          </el-card>

          <el-card v-if="detail.alarm" shadow="never" class="block">
            <template #header>
              <span>来源告警（列表接口已带出，前端不再串告警接口）</span>
            </template>
            <el-descriptions :column="3" border size="small">
              <el-descriptions-item label="告警 ID">
                <span class="mono">#{{ detail.alarm.id }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="类型">
                <span class="mono">{{ detail.alarm.alarmType }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="设备">
                <span class="mono">{{ detail.alarm.deviceNo }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="内容" :span="2">{{ detail.alarm.content }}</el-descriptions-item>
              <el-descriptions-item label="状态">
                <el-tag size="small" :type="detail.alarm.handled === 0 ? 'danger' : 'success'">
                  {{ detail.alarm.handled === 0 ? "未处理" : "已处理" }}
                </el-tag>
              </el-descriptions-item>
            </el-descriptions>
          </el-card>

          <el-card shadow="never" class="block">
            <template #header>
              <span>流转日志（{{ detail.logs.length }} 条，倒序）</span>
            </template>
            <el-empty v-if="detail.logs.length === 0" description="暂无流转记录" :image-size="60" />
            <el-timeline v-else>
              <el-timeline-item
                v-for="(log, index) in orderedLogs"
                :key="index"
                :timestamp="formatTime(log.createTime)"
                placement="top"
                :type="timelineType(log.toStatus)"
              >
                <div class="log-line">
                  <span class="mono">{{ log.action }}</span>
                  <span class="text-muted">
                    {{ statusName(log.fromStatus) }} → {{ statusName(log.toStatus) }}
                  </span>
                  <span class="text-muted">操作人：{{ log.operator || "-" }}</span>
                </div>
                <div v-if="log.remark" class="text-muted">{{ log.remark }}</div>
              </el-timeline-item>
            </el-timeline>
          </el-card>
        </template>
      </template>
    </el-skeleton>

    <el-dialog v-model="actionVisible" :title="actionTitle" width="480px">
      <el-form label-width="90px">
        <el-form-item v-if="pendingAction === 'triage'" label="严重级">
          <el-select v-model="form.severity" placeholder="留空＝保持原值" clearable style="width: 100%">
            <el-option label="HIGH（高）" value="HIGH" />
            <el-option label="MEDIUM（中）" value="MEDIUM" />
            <el-option label="LOW（低）" value="LOW" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="pendingAction === 'assign'" label="处理人 ID">
          <el-input v-model="form.handlerId" placeholder="必填：管理员账号 ID" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="200" show-word-limit
                    placeholder="写入流转日志（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="actionVisible = false">取消</el-button>
        <el-button type="primary" :loading="busyAction !== ''" @click="submitAction">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { CODES } from "../api/permissions";
import { fetchWorkOrderDetail, workOrderStep } from "../api/views";
import { WORK_ORDER_ACTION_LABEL, WORK_ORDER_STATUS, type WorkOrderDetailVO } from "../api/types";
import { formatTime } from "../utils/format";

const route = useRoute();
const router = useRouter();

const workOrderId = Number(route.params.id);
const detail = ref<WorkOrderDetailVO | null>(null);
const loading = ref(false);

const actionVisible = ref(false);
const pendingAction = ref("");
const busyAction = ref("");
const form = reactive({ severity: "", handlerId: "", remark: "" });

const actionTitle = computed(() => `${WORK_ORDER_ACTION_LABEL[pendingAction.value] ?? pendingAction.value}工单`);
/** Newest first: the page is read top-down and the last transition is what matters. */
const orderedLogs = computed(() => [...(detail.value?.logs ?? [])].reverse());

async function load(): Promise<void> {
  loading.value = true;
  try {
    detail.value = await fetchWorkOrderDetail(workOrderId);
  } catch {
    // The interceptor reported it (403 out-of-scope / 400 unknown id); keep the last payload.
  } finally {
    loading.value = false;
  }
}

function openAction(action: string): void {
  pendingAction.value = action;
  form.severity = detail.value?.order.severity ?? "";
  form.handlerId = "";
  form.remark = "";
  actionVisible.value = true;
}

async function submitAction(): Promise<void> {
  const action = pendingAction.value as "triage" | "assign" | "start" | "verify" | "close";
  if (action === "assign" && !form.handlerId.trim()) {
    ElMessage.warning("派单必须填写处理人 ID");
    return;
  }
  busyAction.value = action;
  try {
    const params: { severity?: string; handlerId?: number; remark?: string } = {};
    if (action === "triage" && form.severity) {
      params.severity = form.severity;
    }
    if (action === "assign") {
      params.handlerId = Number(form.handlerId);
    }
    if (form.remark.trim()) {
      params.remark = form.remark.trim();
    }
    const updated = await workOrderStep(workOrderId, action, params);
    ElMessage.success(`已${WORK_ORDER_ACTION_LABEL[action] ?? action}：${updated.woNo}`);
    actionVisible.value = false;
    await load();
  } catch {
    // Interceptor reported it; a 400 here usually means someone else already advanced it.
    await load();
  } finally {
    busyAction.value = "";
  }
}

function goBack(): void {
  void router.push("/work-orders");
}

function openCabinet(cabinetNo: string): void {
  void router.push(`/cabinets/${encodeURIComponent(cabinetNo)}`);
}

function statusName(value: number | null): string {
  if (value === null || value === undefined) {
    return "-";
  }
  return WORK_ORDER_STATUS[value] ?? String(value);
}

function statusTag(value: number): "danger" | "warning" | "primary" | "success" | "info" {
  switch (value) {
    case 1:
      return "danger";
    case 2:
      return "warning";
    case 3:
    case 4:
      return "primary";
    case 5:
      return "success";
    default:
      return "info";
  }
}

function severityTag(severity: string): "danger" | "warning" | "info" {
  return severity === "HIGH" ? "danger" : severity === "MEDIUM" ? "warning" : "info";
}

function timelineType(status: number | null): "primary" | "success" | "warning" | "danger" | "info" {
  if (status === 6) {
    return "success";
  }
  if (status === 1) {
    return "danger";
  }
  return "primary";
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

.log-line {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  align-items: baseline;
}
</style>
