<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orgApi, userApi, type ExpiredQuotaView, type Org, type OrgMember, type OrgQuota, type OrgQuotaRecharge } from '@/api'

const loading = ref(false)
const list = ref<Org[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10, keyword: '', type: '', status: '' })

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<Org>({ code: '', name: '', type: 'ENTERPRISE', status: 'ENABLED' })

const drawerVisible = ref(false)
const currentOrg = ref<Org | null>(null)
const members = ref<OrgMember[]>([])
const quotaList = ref<OrgQuota[]>([])
const rechargeList = ref<OrgQuotaRecharge[]>([])
const userOptions = ref<{ id: number; label: string }[]>([])
const memberUserId = ref<number | undefined>(undefined)

const grantVisible = ref(false)
const grantForm = reactive({
  entitlementType: 'QUESTION_BANK',
  resourceId: undefined as number | undefined,
  amount: 1,
  expireDays: 30,
  scopedToAll: true,
  selectedUserIds: [] as number[]
})
const grantResult = ref<any>(null)

const typeLabel: Record<string, string> = { INSTITUTION: '培训机构', ENTERPRISE: '企业' }
const statusLabel: Record<string, string> = { ENABLED: '启用', DISABLED: '停用' }
const entitlementLabel: Record<string, string> = {
  MEMBER: '会员',
  QUESTION_BANK: '题库包',
  COURSE: '课程',
  EXAM: '考试资格'
}

async function load() {
  loading.value = true
  try {
    const page = await orgApi.page(query)
    list.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { code: '', name: '', type: 'ENTERPRISE', status: 'ENABLED', contactName: '', contactPhone: '', remark: '' })
  dialogVisible.value = true
}

function openEdit(row: Org) {
  editingId.value = row.id ?? null
  Object.assign(form, row)
  dialogVisible.value = true
}

async function save() {
  if (!form.code.trim() || !form.name.trim()) return ElMessage.warning('组织编码与名称必填')
  if (editingId.value) {
    await orgApi.update(editingId.value, { ...form })
  } else {
    await orgApi.create({ ...form })
  }
  ElMessage.success('保存成功')
  dialogVisible.value = false
  load()
}

async function remove(row: Org) {
  await ElMessageBox.confirm(`确认删除组织「${row.name}」？`, '提示', { type: 'warning' })
  await orgApi.remove(row.id!)
  ElMessage.success('已删除')
  load()
}

async function openMembers(row: Org) {
  currentOrg.value = row
  drawerVisible.value = true
  const [m, q, r] = await Promise.all([
    orgApi.members(row.id!),
    orgApi.quotas(row.id!),
    orgApi.quotaRecharges(row.id!)
  ])
  members.value = m
  quotaList.value = q
  rechargeList.value = r
  if (!userOptions.value.length) {
    const page = await userApi.page({ pageNo: 1, pageSize: 100 })
    userOptions.value = page.list.map((u) => ({ id: u.id, label: `${u.nickname || u.username}（${u.username}）` }))
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

async function addMember() {
  if (!memberUserId.value) return ElMessage.warning('请选择用户')
  await orgApi.addMember(currentOrg.value!.id!, { userId: memberUserId.value })
  ElMessage.success('已加入组织')
  memberUserId.value = undefined
  members.value = await orgApi.members(currentOrg.value!.id!)
}

async function removeMember(member: OrgMember) {
  await orgApi.removeMember(currentOrg.value!.id!, member.userId)
  ElMessage.success('已移出')
  members.value = await orgApi.members(currentOrg.value!.id!)
}

function openGrant() {
  Object.assign(grantForm, {
    entitlementType: 'QUESTION_BANK',
    resourceId: undefined,
    amount: 1,
    expireDays: 30,
    scopedToAll: true,
    selectedUserIds: []
  })
  grantResult.value = null
  grantVisible.value = true
}

function userName(userId: number) {
  const found = userOptions.value.find((u) => u.id === userId)
  return found ? found.label : `用户 #${userId}`
}

async function submitGrant() {
  const expireAt = grantForm.expireDays > 0
    ? new Date(Date.now() + grantForm.expireDays * 86400000).toISOString().replace('Z', '').slice(0, 19)
    : undefined
  const payload = {
    entitlementType: grantForm.entitlementType,
    resourceId: grantForm.resourceId,
    amount: grantForm.amount,
    expireAt,
    userIds: grantForm.scopedToAll ? undefined : grantForm.selectedUserIds
  }
  grantResult.value = await orgApi.grant(currentOrg.value!.id!, payload)
  ElMessage.success(`授权完成：成功 ${grantResult.value.grantedCount} 人`)
}

const expiredVisible = ref(false)
const expiredLoading = ref(false)
const expiredList = ref<ExpiredQuotaView[]>([])

/** 过期额度只是「不能再发」，账户仍在库里供对账追溯，所以这里是只读列表。 */
async function openExpired() {
  expiredVisible.value = true
  expiredLoading.value = true
  try {
    expiredList.value = await orgApi.expiredQuotas()
  } finally {
    expiredLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">机构 / 企业</h3>
    <p class="page-desc">
      组织是租户内部的业务实体，用于机构采购与企业授权：批量授权把同一个权益额度发放给成员，重复提交不会重复发放。
    </p>

    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="组织名称" style="width: 200px" clearable
                @keyup.enter="load" />
      <el-select v-model="query.type" placeholder="类型" style="width: 140px" clearable @change="load">
        <el-option v-for="(label, key) in typeLabel" :key="key" :label="label" :value="key" />
      </el-select>
      <el-select v-model="query.status" placeholder="状态" style="width: 130px" clearable @change="load">
        <el-option v-for="(label, key) in statusLabel" :key="key" :label="label" :value="key" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <div class="spacer" />
      <el-button plain @click="openExpired">过期额度</el-button>
      <el-button type="primary" @click="openCreate">新建组织</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="code" label="编码" width="150" />
      <el-table-column prop="name" label="组织名称" min-width="180" />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ typeLabel[row.type] || row.type }}</template>
      </el-table-column>
      <el-table-column prop="contactName" label="联系人" width="120" />
      <el-table-column prop="contactPhone" label="联系电话" width="140" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" effect="plain">
            {{ statusLabel[row.status] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openMembers(row)">成员/授权</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑组织' : '新建组织'" width="560px">
      <el-form label-width="100px">
        <el-form-item label="组织编码" required>
          <el-input v-model="form.code" placeholder="如 ORG-2026-001" />
        </el-form-item>
        <el-form-item label="组织名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.type">
            <el-radio-button label="ENTERPRISE">企业</el-radio-button>
            <el-radio-button label="INSTITUTION">培训机构</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="form.contactName" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="form.contactPhone" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 160px">
            <el-option label="启用" value="ENABLED" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="drawerVisible" :title="`成员 · ${currentOrg?.name || ''}`" size="640px">
      <div class="toolbar">
        <el-select v-model="memberUserId" filterable placeholder="选择用户加入组织" style="width: 300px">
          <el-option v-for="u in userOptions" :key="u.id" :label="u.label" :value="u.id" />
        </el-select>
        <el-button type="primary" @click="addMember">加入</el-button>
        <div class="spacer" />
        <el-button type="warning" plain @click="openGrant">批量授权</el-button>
      </div>

      <el-table :data="members" border size="small">
        <el-table-column prop="userId" label="用户ID" width="100" />
        <el-table-column label="用户" min-width="160">
          <template #default="{ row }">{{ userName(row.userId) }}</template>
        </el-table-column>
        <el-table-column prop="role" label="角色" width="100" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" effect="plain" size="small">
              {{ row.status === 'ACTIVE' ? '有效' : '已移出' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="row.status === 'ACTIVE'" link type="danger"
                       @click="removeMember(row)">移出</el-button>
          </template>
        </el-table-column>
      </el-table>

      <h4 class="section-title">额度账户</h4>
      <el-table :data="quotaList" border size="small" empty-text="该机构没有额度账户（发权益不受额度限制）">
        <el-table-column prop="entitlementType" label="权益类型" width="130" />
        <el-table-column label="资源" width="90">
          <template #default="{ row }">{{ row.resourceId ? `#${row.resourceId}` : '通用' }}</template>
        </el-table-column>
        <el-table-column prop="totalAmount" label="累计充值" width="100" />
        <el-table-column prop="usedAmount" label="已发放" width="100" />
        <el-table-column label="剩余" width="90">
          <template #default="{ row }"><strong>{{ balance(row) }}</strong></template>
        </el-table-column>
        <el-table-column label="有效期" min-width="130">
          <template #default="{ row }">
            <span v-if="!row.expireAt" class="muted">长期有效</span>
            <el-tag v-else-if="isExpired(row)" type="danger" effect="plain" size="small">已过期</el-tag>
            <span v-else>{{ formatDate(row.expireAt) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <h4 class="section-title">充值流水</h4>
      <el-table :data="rechargeList" border size="small" empty-text="暂无充值记录">
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="entitlementType" label="权益类型" width="130" />
        <el-table-column prop="amount" label="份数" width="80" />
        <el-table-column label="有效期" width="130">
          <template #default="{ row }">
            <span v-if="!row.expireAt" class="muted">未指定</span>
            <span v-else>{{ formatDate(row.expireAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注（采购单号）" min-width="140" />
      </el-table>
    </el-drawer>

    <el-dialog v-model="grantVisible" title="批量授权" width="600px">
      <el-form label-width="110px">
        <el-form-item label="权益类型">
          <el-select v-model="grantForm.entitlementType" style="width: 200px">
            <el-option v-for="(label, key) in entitlementLabel" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="资源 ID">
          <el-input-number v-model="grantForm.resourceId" :min="0" />
          <span class="hint">课程/项目等资源 ID，可留空</span>
        </el-form-item>
        <el-form-item label="每人数量">
          <el-input-number v-model="grantForm.amount" :min="1" :max="999" />
        </el-form-item>
        <el-form-item label="有效期(天)">
          <el-input-number v-model="grantForm.expireDays" :min="0" :max="3650" />
          <span class="hint">0 表示长期有效</span>
        </el-form-item>
        <el-form-item label="授权范围">
          <el-radio-group v-model="grantForm.scopedToAll">
            <el-radio :label="true">组织全部有效成员</el-radio>
            <el-radio :label="false">指定成员</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="!grantForm.scopedToAll" label="指定成员">
          <el-select v-model="grantForm.selectedUserIds" multiple style="width: 100%"
                     placeholder="选择本组织成员">
            <el-option v-for="m in members.filter((x) => x.status === 'ACTIVE')" :key="m.userId"
                       :label="userName(m.userId)" :value="m.userId" />
          </el-select>
        </el-form-item>
      </el-form>

      <el-alert v-if="grantResult" type="success" :closable="false" show-icon
                :title="`批次 ${grantResult.batchNo}：目标 ${grantResult.targetCount} 人，成功 ${grantResult.grantedCount} 人${grantResult.skippedCount ? '，跳过 ' + grantResult.skippedCount + ' 人' : ''}`" />

      <template #footer>
        <el-button @click="grantVisible = false">关闭</el-button>
        <el-button type="primary" @click="submitGrant">执行授权</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="expiredVisible" title="已过期的机构额度" width="760px">
      <p class="page-desc">
        过期只影响「还能不能继续给自己的人发权益」，账户本身不会被删除——作废的余额留在这里供对账追溯。
      </p>
      <el-table v-loading="expiredLoading" :data="expiredList" border stripe empty-text="没有已过期的额度账户">
        <el-table-column prop="orgName" label="机构" min-width="170" />
        <el-table-column prop="entitlementType" label="权益类型" width="150" />
        <el-table-column label="资源" width="100">
          <template #default="{ row }">{{ row.resourceId ? `#${row.resourceId}` : '通用' }}</template>
        </el-table-column>
        <el-table-column prop="balance" label="作废余额" width="110" />
        <el-table-column label="过期时间" width="170">
          <template #default="{ row }">{{ (row.expireAt || '').replace('T', ' ').slice(0, 16) }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="expiredVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hint {
  margin-left: 10px;
  font-size: 12px;
  color: #9ca3af;
}

.section-title {
  margin: 18px 0 8px;
  font-size: 14px;
  color: #334155;
}

.muted {
  color: #94a3b8;
  font-size: 12px;
}
</style>
