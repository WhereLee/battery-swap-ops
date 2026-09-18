<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">告警与建议单</h2>
        <p class="page-subtitle">
          按钮由后端 <span class="mono">allowedActions</span> 决定（前端不复制状态机），再按权限码
          <span class="mono">v-access</span> 过滤；两表无站点归属列，属全局口径资源（S8 方案 §4.8）。
        </p>
      </div>
      <el-button size="small" :loading="alarmLoading || suggestionLoading" @click="reload">刷新</el-button>
    </div>

    <el-tabs v-model="tab">
      <!-- ============ 告警 ============ -->
      <el-tab-pane label="告警" name="alarm">
        <div class="toolbar">
          <el-radio-group v-model="alarmHandled" size="small" @change="onAlarmFilterChange">
            <el-radio-button :value="0">未处理</el-radio-button>
            <el-radio-button :value="1">已处理</el-radio-button>
            <el-radio-button :value="-1">全部</el-radio-button>
          </el-radio-group>
          <span class="text-muted">共 {{ alarmTotal }} 条</span>
        </div>

        <el-table v-loading="alarmLoading" :data="alarms" size="small" row-key="id" class="table">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column label="类型" width="170">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" class="mono">{{ row.alarmType }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="设备" width="190">
            <template #default="{ row }">
              <div class="mono">{{ row.deviceNo }}</div>
              <div class="text-muted">{{ row.deviceType }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="content" label="内容" min-width="220" show-overflow-tooltip />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.handled === 0 ? 'danger' : 'success'" size="small">
                {{ row.handled === 0 ? "未处理" : "已处理" }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="160">
            <template #default="{ row }">
              <span class="mono">{{ formatTime(row.createTime) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="关联工单" width="90">
            <template #default="{ row }">
              <span v-if="row.workOrderId" class="mono">#{{ row.workOrderId }}</span>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="can(row, 'handle')"
                v-access="CODES.alarmHandle"
                size="small"
                type="primary"
                link
                :loading="busyId === row.id"
                @click="onHandleAlarm(row)"
              >
                标记已处理
              </el-button>
              <el-button
                v-if="can(row, 'create-work-order')"
                v-access="CODES.workOrderManage"
                size="small"
                type="warning"
                link
                @click="openCreateOrder(row)"
              >
                建工单
              </el-button>
              <span v-if="!row.allowedActions || row.allowedActions.length === 0" class="text-muted">
                无可执行动作
              </span>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager">
          <el-pagination
            v-model:current-page="alarmPage"
            v-model:page-size="alarmLimit"
            :total="alarmTotal"
            :page-sizes="[20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            background
            size="small"
            @current-change="loadAlarms"
            @size-change="onAlarmSizeChange"
          />
        </div>
      </el-tab-pane>

      <!-- ============ Agent 建议单 ============ -->
      <el-tab-pane label="运维建议单（Agent）" name="suggestion">
        <div class="toolbar">
          <el-select v-model="suggestionStatus" size="small" style="width: 160px" @change="onSuggestionFilterChange">
            <el-option label="待确认" :value="1" />
            <el-option label="执行中" :value="5" />
            <el-option label="已执行" :value="2" />
            <el-option label="已驳回" :value="3" />
            <el-option label="执行失败" :value="4" />
            <el-option label="全部" :value="-1" />
          </el-select>
          <span class="text-muted">共 {{ suggestionTotal }} 条</span>
          <span class="text-muted">
            闭环：Agent 提议 → 人工确认（CAS 抢执行权）→ 执行结果回写；列表即审计。
          </span>
        </div>

        <el-table v-loading="suggestionLoading" :data="suggestions" size="small" row-key="id" class="table">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="expand mono">
                <div>幂等键：{{ row.idemKey || "-" }}</div>
                <div>执行结果：{{ row.resultJson || "-" }}</div>
                <div>失败原因：{{ row.errorMsg || "-" }}</div>
                <div>确认人 / 时间：{{ row.confirmer || "-" }} / {{ formatTime(row.confirmTime) }}</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column label="单号" width="180">
            <template #default="{ row }">
              <span class="mono">{{ row.actionNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="动作类型" width="160">
            <template #default="{ row }">
              <!-- Chinese label for reading, raw enum in the tooltip because that string
                   is what the API contract and the audit log actually carry. -->
              <el-tooltip :content="row.actionType" placement="top">
                <el-tag size="small" effect="plain">{{ agentActionLabel(row.actionType) }}</el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="提议理由" min-width="220" show-overflow-tooltip />
          <el-table-column label="关联告警" width="170">
            <template #default="{ row }">
              <span v-if="row.alarmId" class="mono">#{{ row.alarmId }} {{ row.alarmType ?? "" }}</span>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="suggestionTagType(row.status)" size="small">
                {{ SUGGESTION_STATUS[row.status as number] ?? `未知(${row.status})` }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="proposer" label="提议者" width="110" />
          <el-table-column label="创建时间" width="160">
            <template #default="{ row }">
              <span class="mono">{{ formatTime(row.createTime) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="can(row, 'confirm')"
                v-access="CODES.suggestionManage"
                size="small"
                type="primary"
                link
                :loading="busyId === row.id"
                @click="onConfirmSuggestion(row)"
              >
                确认执行
              </el-button>
              <el-button
                v-if="can(row, 'reject')"
                v-access="CODES.suggestionManage"
                size="small"
                type="danger"
                link
                @click="openReject(row)"
              >
                驳回
              </el-button>
              <span v-if="!row.allowedActions || row.allowedActions.length === 0" class="text-muted">
                已终结
              </span>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager">
          <el-pagination
            v-model:current-page="suggestionPage"
            v-model:page-size="suggestionLimit"
            :total="suggestionTotal"
            :page-sizes="[20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            background
            size="small"
            @current-change="loadSuggestions"
            @size-change="onSuggestionSizeChange"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 建工单：severity 留空即由后端按告警类型派生（HIGH/MEDIUM/LOW） -->
    <el-dialog v-model="createVisible" title="从告警创建工单" width="480px">
      <el-form label-width="90px">
        <el-form-item label="告警">
          <span class="mono">#{{ createTarget?.id }} {{ createTarget?.alarmType }}</span>
        </el-form-item>
        <el-form-item label="设备">
          <span class="mono">{{ createTarget?.deviceNo }}</span>
        </el-form-item>
        <el-form-item label="严重级">
          <el-select v-model="createSeverity" placeholder="留空＝按告警类型自动派生" clearable style="width: 100%">
            <el-option label="HIGH（高）" value="HIGH" />
            <el-option label="MEDIUM（中）" value="MEDIUM" />
            <el-option label="LOW（低）" value="LOW" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreateOrder">创建</el-button>
      </template>
    </el-dialog>

    <!-- 驳回：remark 走 query 参数（后端 @RequestParam） -->
    <el-dialog v-model="rejectVisible" title="驳回建议单" width="480px">
      <el-form label-width="90px">
        <el-form-item label="建议单">
          <span class="mono">{{ rejectTarget?.actionNo }}（{{ rejectTarget?.actionType }}）</span>
        </el-form-item>
        <el-form-item label="驳回理由">
          <el-input v-model="rejectRemark" type="textarea" :rows="3" maxlength="200" show-word-limit
                    placeholder="写入审计（可选，但建议填写）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="submitReject">驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { CODES } from "../api/permissions";
import {
  confirmSuggestion,
  createWorkOrderFromAlarm,
  fetchAlarmPage,
  fetchSuggestionPage,
  handleAlarm,
  rejectSuggestion,
} from "../api/views";
import { SUGGESTION_STATUS, agentActionLabel, type AlarmItemVO, type SuggestionVO } from "../api/types";
import { formatTime } from "../utils/format";

const tab = ref<"alarm" | "suggestion">("alarm");
const busyId = ref(-1);
const submitting = ref(false);

// ---------- alarms ----------
const alarms = ref<AlarmItemVO[]>([]);
const alarmLoading = ref(false);
const alarmPage = ref(1);
const alarmLimit = ref(20);
const alarmTotal = ref(0);
/** -1 is a UI-only sentinel for "no filter"; the API takes undefined. */
const alarmHandled = ref(0);

async function loadAlarms(): Promise<void> {
  alarmLoading.value = true;
  try {
    const result = await fetchAlarmPage({
      page: alarmPage.value,
      limit: alarmLimit.value,
      handled: alarmHandled.value === -1 ? undefined : alarmHandled.value,
    });
    alarms.value = result.list;
    alarmTotal.value = result.total;
  } catch {
    // Interceptor reported it; keep the last good page rather than blanking the table.
  } finally {
    alarmLoading.value = false;
  }
}

function onAlarmFilterChange(): void {
  alarmPage.value = 1;
  void loadAlarms();
}

function onAlarmSizeChange(): void {
  alarmPage.value = 1;
  void loadAlarms();
}

async function onHandleAlarm(row: AlarmItemVO): Promise<void> {
  busyId.value = row.id;
  try {
    await handleAlarm(row.id);
    ElMessage.success(`告警 #${row.id} 已标记处理`);
    await loadAlarms();
  } catch {
    // A concurrent handler already closed it: the refresh above will show handled=1.
  } finally {
    busyId.value = -1;
  }
}

// ---------- suggestions ----------
const suggestions = ref<SuggestionVO[]>([]);
const suggestionLoading = ref(false);
const suggestionPage = ref(1);
const suggestionLimit = ref(20);
const suggestionTotal = ref(0);
const suggestionStatus = ref(1);

async function loadSuggestions(): Promise<void> {
  suggestionLoading.value = true;
  try {
    const result = await fetchSuggestionPage({
      page: suggestionPage.value,
      limit: suggestionLimit.value,
      status: suggestionStatus.value === -1 ? undefined : suggestionStatus.value,
    });
    suggestions.value = result.list;
    suggestionTotal.value = result.total;
  } catch {
    // See loadAlarms.
  } finally {
    suggestionLoading.value = false;
  }
}

function onSuggestionFilterChange(): void {
  suggestionPage.value = 1;
  void loadSuggestions();
}

function onSuggestionSizeChange(): void {
  suggestionPage.value = 1;
  void loadSuggestions();
}

function suggestionTagType(status: number): "primary" | "success" | "warning" | "danger" | "info" {
  switch (status) {
    case 1:
      return "warning"; // PROPOSED
    case 2:
      return "success"; // EXECUTED
    case 3:
      return "info"; // REJECTED
    case 4:
      return "danger"; // FAILED
    case 5:
      return "primary"; // EXECUTING
    default:
      return "info";
  }
}

async function onConfirmSuggestion(row: SuggestionVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认执行建议单 ${row.actionNo}（${row.actionType}）？确认即抢执行权并真正下发，重复确认会被 CAS 拒绝。`,
      "人工确认",
      { type: "warning", confirmButtonText: "确认执行", cancelButtonText: "取消" },
    );
  } catch {
    return;
  }
  busyId.value = row.id;
  try {
    await confirmSuggestion(row.id);
    ElMessage.success(`建议单 ${row.actionNo} 已确认，执行中`);
    await loadSuggestions();
  } catch {
    // Interceptor reported the CAS conflict / execution failure.
  } finally {
    busyId.value = -1;
  }
}

// ---------- dialogs ----------
const createVisible = ref(false);
const createTarget = ref<AlarmItemVO | null>(null);
const createSeverity = ref("");

function openCreateOrder(row: AlarmItemVO): void {
  createTarget.value = row;
  createSeverity.value = "";
  createVisible.value = true;
}

async function submitCreateOrder(): Promise<void> {
  const target = createTarget.value;
  if (!target) {
    return;
  }
  submitting.value = true;
  try {
    const order = await createWorkOrderFromAlarm(target.id, createSeverity.value || undefined);
    // createFromAlarm is idempotent by alarmId: an existing order comes back unchanged.
    ElMessage.success(`工单 ${order.woNo} 就绪（严重级 ${order.severity}）`);
    createVisible.value = false;
    await loadAlarms();
  } catch {
    // Interceptor reported it (e.g. illegal severity).
  } finally {
    submitting.value = false;
  }
}

const rejectVisible = ref(false);
const rejectTarget = ref<SuggestionVO | null>(null);
const rejectRemark = ref("");

function openReject(row: SuggestionVO): void {
  rejectTarget.value = row;
  rejectRemark.value = "";
  rejectVisible.value = true;
}

async function submitReject(): Promise<void> {
  const target = rejectTarget.value;
  if (!target) {
    return;
  }
  submitting.value = true;
  try {
    await rejectSuggestion(target.id, rejectRemark.value.trim() || undefined);
    ElMessage.success(`建议单 ${target.actionNo} 已驳回`);
    rejectVisible.value = false;
    await loadSuggestions();
  } catch {
    // Interceptor reported it.
  } finally {
    submitting.value = false;
  }
}

// ---------- shared ----------
/** Capability bits come from the backend; the UI never derives them from status. */
function can(row: AlarmItemVO | SuggestionVO, action: string): boolean {
  return row.allowedActions?.includes(action) ?? false;
}

function reload(): void {
  if (tab.value === "alarm") {
    void loadAlarms();
  } else {
    void loadSuggestions();
  }
}

onMounted(() => {
  void loadAlarms();
  // The suggestion tab is loaded lazily on first open, but prefetched here so the
  // badge-less tab is not empty on click when the user has suggestion:read.
  void loadSuggestions();
});
</script>

<style scoped>
.table {
  margin-top: 12px;
}

.expand {
  padding: 8px 16px;
  line-height: 1.8;
  color: #606266;
}
</style>
