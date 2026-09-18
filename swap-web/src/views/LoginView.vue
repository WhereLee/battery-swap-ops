<template>
  <div class="login-wrap">
    <el-card class="login-card" shadow="always">
      <div class="login-head">
        <div class="login-title">换电运营平台 · 管理台</div>
        <div class="login-sub">battery-swap-ops admin console</div>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="onSubmit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="管理端账号" autocomplete="username" clearable />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            autocomplete="current-password"
            show-password
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-button type="primary" class="login-btn" :loading="submitting" @click="onSubmit">登录</el-button>
      </el-form>

      <el-alert type="info" :closable="false" class="login-note">
        <template #title>
          <span class="mono">
            账号来自 admin_user（首个由启动引导创建）；登录成功即下发角色权限码与数据范围，前端不内置任何凭据。
          </span>
        </template>
      </el-alert>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";
import { useAuthStore } from "../stores/auth";

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();

const formRef = ref<FormInstance>();
const submitting = ref(false);
const form = reactive({ username: "", password: "" });

const rules: FormRules = {
  username: [{ required: true, message: "请输入用户名", trigger: "blur" }],
  password: [{ required: true, message: "请输入密码", trigger: "blur" }],
};

/**
 * `?redirect=` comes from an untrusted URL. Only same-origin absolute paths are
 * honoured — "//host" (protocol-relative) and "https://host" would otherwise turn the
 * login page into an open redirect.
 */
function safeRedirect(target: unknown): string {
  if (typeof target !== "string" || target.length === 0) {
    return "/dashboard";
  }
  if (!target.startsWith("/") || target.startsWith("//") || target.includes("\\")) {
    return "/dashboard";
  }
  return target;
}

async function onSubmit(): Promise<void> {
  if (submitting.value) {
    return;
  }
  const valid = (await formRef.value?.validate().catch(() => false)) ?? false;
  if (!valid) {
    return;
  }
  submitting.value = true;
  try {
    await auth.login(form.username, form.password);
  } catch {
    // The axios interceptor already surfaced the reason (400 bad credential, 429 rate
    // limited, 5xx). Re-messaging here would double-report the same failure.
    return;
  } finally {
    submitting.value = false;
  }
  ElMessage.success(`登录成功：${auth.role}`);
  void router.replace(safeRedirect(route.query.redirect));
}
</script>

<style scoped>
.login-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  background: linear-gradient(135deg, #1f2d3d 0%, #3b5364 100%);
}

.login-card {
  width: 380px;
}

.login-head {
  margin-bottom: 16px;
  text-align: center;
}

.login-title {
  font-size: 17px;
  font-weight: 600;
}

.login-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}

.login-btn {
  width: 100%;
}

.login-note {
  margin-top: 14px;
}
</style>
