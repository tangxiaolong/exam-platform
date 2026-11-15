<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { orgPortalApi, type OrgMember, type OrgPortalSummary, type OrgQuota, type OrgQuotaRecharge } from '@/api'

const loading = ref(false)
const submitting = ref(false)
const myOrgs = ref<OrgPortalSummary[]>([])
const currentOrgId = ref<number | null>(null)
const profile = ref<OrgPortalSummary | null>(null)
const members = ref<OrgMember[]>([])
const quotas = ref<OrgQuota[]>([])
const recharges = ref<OrgQuotaRecharge[]>([])
const lastResult = ref<{ batchNo: string; grantedCount: number; skippedCount: number } | null>(null)

const form = reactive({
  entitlementType: 'QUESTION_BANK',
  amount: 1,
  remark: '',
  userIds: [] as number[]
})

const activeMembers = computed(() => members.value.filter((m) => m.status === 'ACTIVE'))

async function loadMyOrgs() {
  loading.value = true
  try {
    myOrgs.value = await orgPortalApi.myOrgs()
    if (!currentOrgId.value && myOrgs.value.length) {
      currentOrgId.value = myOrgs.value[0].orgId
    }
  } finally {
    loading.value = false
  }
  await loadDetail()
}

async function loadDetail() {
  if (!currentOrgId.value) return
  const orgId = currentOrgId.value
  const [p, m, q, r] = await Promise.all([
    orgPortalApi.profile(orgId),
    orgPortalApi.members(orgId),
    orgPortalApi.quotas(orgId),
    orgPortalApi.quotaRecharges(orgId)
  ])
  profile.value = p
  members.value = m
  quotas.value = q
  recharges.value = r
}

async function switchOrg() {
  lastResult.value = null
  form.userIds = []
  await loadDetail()
}

async function submitGrant() {
  if (!currentOrgId.value) return
  if (form.amount <= 0) {
    ElMessage.warning('发放数量必须大于 0')
    return
  }
  submitting.value = true
  try {
    const data: Record<string, unknown> = {
      entitlementType: form.entitlementType,
      amount: form.amount,
      remark: form.remark || undefined
    }
    if (form.userIds.length) {
      data.userIds = form.userIds
    }
    const result = await orgPortalApi.grant(currentOrgId.value, data)
    lastResult.value = {
      batchNo: result.batchNo,
      grantedCount: result.grantedCount,
      skippedCount: result.skippedCount
    }
    ElMessage.success(`发放完成：成功 ${result.grantedCount} 人，跳过 ${result.skippedCount} 人`)
    await loadDetail()
  } finally {
    submitting.value = false
  }
}

function balance(row: OrgQuota) {
  return (row.totalAmount ?? 0) - (row.usedAmount ?? 0)
}

function isExpired(row: OrgQuota) {
  return !!row.expireAt && new Date(row.expireAt).getTime() <= Date.now()
}

function formatDate(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 16) : '-'
}

onMounted(loadMyOrgs)
</script>

<template>
  <div v-loading="loading" class="portal">
    <el-empty v-if="!loading && !myOrgs.length" description="你还没有加入任何机构，请联系平台运营添加" />

    <template v-else-if="myOrgs.length">
      <div class="toolbar">
        <el-select v-model="currentOrgId" style="width: 260px" @change="switchOrg">
          <el-option
            v-for="org in myOrgs"
            :key="org.orgId"
            :label="`${org.orgName}（${org.orgCode}）`"
            :value="org.orgId"
          />
        </el-select>
        <el-tag v-if="profile" :type="profile.orgStatus === 'ENABLED' ? 'success' : 'danger'" effect="plain">
          {{ profile.orgStatus === 'ENABLED' ? '启用中' : '已停用' }}
        </el-tag>
        <el-tag v-if="profile" type="info" effect="plain">我的角色：{{ profile.myRole }}</el-tag>
        <el-tag v-if="profile" type="warning" effect="plain">{{ profile.orgType }}</el-tag>
      </div>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-card shadow="never" header="额度账单">
            <el-table :data="quotas" size="small" empty-text="暂无额度账户（不限额发放）">
              <el-table-column prop="entitlementType" label="权益类型" min-width="130" />
              <el-table-column label="资源" min-width="90">
                <template #default="{ row }">
                  {{ row.resourceId ? `#${row.resourceId}` : '通用' }}
                </template>
              </el-table-column>
              <el-table-column prop="totalAmount" label="累计充值" min-width="100" />
              <el-table-column prop="usedAmount" label="已发放" min-width="90" />
              <el-table-column label="剩余" min-width="80">
                <template #default="{ row }">
                  <strong>{{ balance(row) }}</strong>
                </template>
              </el-table-column>
              <el-table-column label="有效期" min-width="150">
                <template #default="{ row }">
                  <span v-if="!row.expireAt" class="muted">长期有效</span>
                  <el-tag v-else-if="isExpired(row)" type="danger" effect="plain">已过期</el-tag>
                  <span v-else>{{ formatDate(row.expireAt) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>

        <el-col :span="12">
          <el-card shadow="never" header="成员">
            <el-table :data="members" size="small" empty-text="机构内还没有成员">
              <el-table-column prop="userId" label="用户 ID" min-width="90" />
              <el-table-column prop="role" label="机构内角色" min-width="110" />
              <el-table-column prop="status" label="状态" min-width="90" />
            </el-table>
          </el-card>
        </el-col>
      </el-row>

      <el-card shadow="never" header="充值流水">
        <el-table :data="recharges" size="small" empty-text="暂无充值记录（额度由平台运营充值）">
          <el-table-column label="时间" min-width="150">
            <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="entitlementType" label="权益类型" min-width="120" />
          <el-table-column prop="amount" label="份数" min-width="80" />
          <el-table-column label="有效期" min-width="150">
            <template #default="{ row }">
              <span v-if="!row.expireAt" class="muted">未指定</span>
              <span v-else>{{ formatDate(row.expireAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注（采购单号）" min-width="160" />
        </el-table>
      </el-card>

      <el-card shadow="never" header="给自己的人发权益" class="grant">
        <el-form :inline="true" :model="form">
          <el-form-item label="权益类型">
            <el-select v-model="form.entitlementType" style="width: 160px">
              <el-option label="会员" value="MEMBER" />
              <el-option label="题库" value="QUESTION_BANK" />
              <el-option label="课程" value="COURSE" />
              <el-option label="考试" value="EXAM" />
            </el-select>
          </el-form-item>
          <el-form-item label="每人份数">
            <el-input-number v-model="form.amount" :min="1" :max="1000" />
          </el-form-item>
          <el-form-item label="指定成员">
            <el-select
              v-model="form.userIds"
              multiple
              collapse-tags
              collapse-tags-tooltip
              placeholder="不选则发给全部有效成员"
              style="width: 240px"
            >
              <el-option
                v-for="m in activeMembers"
                :key="m.userId"
                :label="`#${m.userId}（${m.role}）`"
                :value="m.userId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="form.remark" placeholder="如采购单号" style="width: 180px" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="submitting" @click="submitGrant">立即发放</el-button>
          </el-form-item>
        </el-form>

        <el-alert
          v-if="lastResult"
          type="success"
          :closable="false"
          show-icon
          :title="`批次 ${lastResult.batchNo}：成功 ${lastResult.grantedCount} 人，跳过 ${lastResult.skippedCount} 人`"
        />
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="发放会先扣减机构额度；余额不足时整批拒绝，不会半途发放"
        />
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.portal {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.grant {
  margin-top: 4px;
}

.grant :deep(.el-alert) {
  margin-top: 10px;
}

.muted {
  color: #94a3b8;
  font-size: 12px;
}
</style>
