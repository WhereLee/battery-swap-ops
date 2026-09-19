<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">换电柜</h2>
        <p class="page-subtitle">
          列表走资产域分页（<b>密钥已在服务端脱敏</b>，页面拿不到 secret）与站点范围过滤；
          心跳年龄由服务端算好，前端不做时间差推算。
        </p>
      </div>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div class="toolbar">
      <el-input
        v-model="cabinetNo"
        size="small"
        style="width: 220px"
        placeholder="柜编号（模糊）"
        clearable
        @keyup.enter="onFilterChange"
        @clear="onFilterChange"
      />
      <el-select v-model="status" size="small" style="width: 150px" @change="onFilterChange">
        <el-option label="全部状态" :value="-1" />
        <el-option v-for="(label, code) in CABINET_STATUS" :key="code" :label="label" :value="Number(code)" />
      </el-select>
      <span class="text-muted">共 {{ total }} 个柜</span>
      <span class="text-muted">心跳阈值内视为在线；年龄越大越可能掉线（离线扫描任务会据此告警）</span>
    </div>

    <el-table v-loading="loading" :data="rows" size="small" row-key="id" class="table">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="柜编号" width="200">
        <template #default="{ row }">
          <el-link type="primary" class="mono" @click="openDetail(row.cabinetNo)">{{ row.cabinetNo }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="站点 ID" width="110">
        <template #default="{ row }">
          <span class="mono">{{ row.stationId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="仓位数" width="90">
        <template #default="{ row }">
          <span class="mono">{{ row.cellCount }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="cabinetStatusTag(row.status)">
            {{ CABINET_STATUS[row.status as number] ?? `未知(${row.status})` }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="心跳年龄" width="120">
        <template #default="{ row }">
          <span class="mono" :class="{ 'text-muted': row.heartbeatAgeMs === null }">
            {{ formatAge(row.heartbeatAgeMs) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="最近心跳" width="170">
        <template #default="{ row }">
          <span class="mono">{{ formatTime(row.lastHeartbeatTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="代际 bootId" width="180">
        <template #default="{ row }">
          <span class="mono">{{ row.lastBootId ?? "-" }}</span>
        </template>
      </el-table-column>
      <el-table-column label="事件序号水位" width="130">
        <template #default="{ row }">
          <span class="mono">{{ row.lastEventSeq ?? "-" }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="openDetail(row.cabinetNo)">详情</el-button>
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
import { fetchCabinetPage } from "../api/views";
import { CABINET_STATUS, type CabinetVO } from "../api/types";
import { formatAge, formatTime } from "../utils/format";

const router = useRouter();

const rows = ref<CabinetVO[]>([]);
const loading = ref(false);
const page = ref(1);
const limit = ref(20);
const total = ref(0);
const status = ref(-1);
const cabinetNo = ref("");

async function load(): Promise<void> {
  loading.value = true;
  try {
    const result = await fetchCabinetPage({
      page: page.value,
      limit: limit.value,
      status: status.value === -1 ? undefined : status.value,
      cabinetNo: cabinetNo.value.trim() || undefined,
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
  void router.push(`/cabinets/${encodeURIComponent(no)}`);
}

function cabinetStatusTag(value: number): "success" | "warning" | "danger" | "info" {
  switch (value) {
    case 1:
      return "success"; // ONLINE
    case 2:
      return "warning"; // FULL
    case 3:
      return "danger"; // FAULT
    default:
      return "info"; // MAINTENANCE / DISABLED
  }
}

onMounted(load);
</script>

<style scoped>
.table {
  margin-top: 12px;
}
</style>
