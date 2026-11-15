<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const form = reactive({ username: 'admin', password: 'admin123' })
const loading = ref(false)

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    const user = await auth.login(form.username, form.password)
    if (user.roleCode !== 'ADMIN' && user.roleCode !== 'ORG_ADMIN') {
      ElMessage.error('该账号不是平台或机构管理员')
      auth.logout()
      return
    }
    ElMessage.success('登录成功')
    // 角色决定落点：机构管理员进不了平台后台，默认首页只能是机构门户
    const fallback = user.roleCode === 'ORG_ADMIN' ? '/org-portal' : '/dashboard'
    const redirect = (route.query.redirect as string) || fallback
    router.push(redirect)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <div class="logo">考</div>
        <div>
          <h1>综合考试平台</h1>
          <p>运营管理后台</p>
        </div>
      </div>

      <el-form @submit.prevent="submit">
        <el-form-item>
          <el-input v-model="form.username" size="large" placeholder="账号" :prefix-icon="'User'" />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            size="large"
            type="password"
            show-password
            placeholder="密码"
            :prefix-icon="'Lock'"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
          登录
        </el-button>
      </el-form>

      <p class="tip">演示账号：admin / admin123</p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: radial-gradient(circle at 20% 20%, #1d4ed8 0%, #0f172a 55%, #020617 100%);
}

.login-card {
  width: 380px;
  padding: 34px 32px 24px;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 20px 60px rgba(2, 6, 23, 0.45);
}

.brand {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 26px;
}

.logo {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: linear-gradient(135deg, #3b82f6, #6366f1);
  color: #fff;
  font-weight: 700;
  font-size: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
}

h1 {
  font-size: 18px;
  margin: 0;
}

p {
  margin: 2px 0 0;
  font-size: 12px;
  color: #94a3b8;
}

.submit {
  width: 100%;
  margin-top: 6px;
}

.tip {
  text-align: center;
  margin-top: 18px;
  font-size: 12px;
  color: #9ca3af;
}
</style>
