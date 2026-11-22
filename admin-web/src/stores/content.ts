import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { projectApi, type Project, type Subject } from '@/api'

/**
 * 内容运营的「当前项目 / 当前科目」。
 *
 * <p>题库、知识点、使用分析、课程四个页面都挂在「项目 → 科目」这层上下文下面。
 * 之前每页各写一个下拉框、各自从列表第一条取科目，于是：切换页面要重选一遍，
 * 多项目租户下题库页永远只显示第一个项目的科目。把这份状态收到一处，选一次全站生效。</p>
 */
const PROJECT_KEY = 'exam_admin_ctx_project'
const SUBJECT_KEY = 'exam_admin_ctx_subject'

function readStored(key: string): number | null {
  const raw = localStorage.getItem(key)
  if (raw === null || raw === '') return null
  const value = Number(raw)
  return Number.isFinite(value) && value > 0 ? value : null
}

export const useContentStore = defineStore('content', () => {
  const projects = ref<Project[]>([])
  const subjects = ref<Subject[]>([])
  const projectId = ref<number | null>(readStored(PROJECT_KEY))
  const subjectId = ref<number | null>(readStored(SUBJECT_KEY))
  const loading = ref(false)

  /** 课程列表只回 projectId / subjectId，靠这两个映射才能显示成名字 */
  const projectMap = computed(() => new Map(projects.value.map((p) => [p.id as number, p.name])))
  const subjectMap = computed(() => new Map(subjects.value.map((s) => [s.id as number, s.name])))

  const projectName = computed(() => (projectId.value ? projectMap.value.get(projectId.value) ?? '' : ''))
  const subjectName = computed(() => (subjectId.value ? subjectMap.value.get(subjectId.value) ?? '' : ''))
  const hasProjects = computed(() => projects.value.length > 0)
  const hasSubjects = computed(() => subjects.value.length > 0)

  function persist() {
    if (projectId.value) localStorage.setItem(PROJECT_KEY, String(projectId.value))
    else localStorage.removeItem(PROJECT_KEY)
    if (subjectId.value) localStorage.setItem(SUBJECT_KEY, String(subjectId.value))
    else localStorage.removeItem(SUBJECT_KEY)
  }

  async function fetchProjects() {
    const page = await projectApi.page({ pageNo: 1, pageSize: 200 })
    projects.value = page.list || []
  }

  async function loadSubjects(id: number | null) {
    if (!id) {
      subjects.value = []
      return
    }
    subjects.value = (await projectApi.subjects(id)) || []
  }

  /**
   * 首屏对齐。
   *
   * <p>localStorage 里记的项目可能已经被删除或下线，不能直接信缓存——每次都用真实列表校验，
   * 失效就退到第一条。否则页面会拿着一个不存在的 projectId 去查科目，静默得到空列表。</p>
   */
  async function ensureReady() {
    if (!projects.value.length) {
      loading.value = true
      try {
        await fetchProjects()
      } finally {
        loading.value = false
      }
    }
    if (!projects.value.length) {
      projectId.value = null
      subjectId.value = null
      subjects.value = []
      persist()
      return
    }
    if (!projects.value.some((p) => p.id === projectId.value)) {
      projectId.value = projects.value[0].id ?? null
      subjectId.value = null
      subjects.value = []
    }
    if (!subjects.value.length) {
      await loadSubjects(projectId.value)
    }
    if (!subjects.value.some((s) => s.id === subjectId.value)) {
      subjectId.value = subjects.value[0]?.id ?? null
    }
    persist()
  }

  /** 新建/删除项目后要让下拉框跟上，不能继续用旧列表 */
  async function refreshProjects() {
    await fetchProjects()
    if (!projects.value.some((p) => p.id === projectId.value)) {
      projectId.value = projects.value[0]?.id ?? null
      subjectId.value = null
      await loadSubjects(projectId.value)
      subjectId.value = subjects.value[0]?.id ?? null
    }
    persist()
  }

  async function selectProject(id: number | null) {
    if (id === projectId.value && subjects.value.length) return
    projectId.value = id
    subjectId.value = null
    await loadSubjects(id)
    subjectId.value = subjects.value[0]?.id ?? null
    persist()
  }

  function selectSubject(id: number | null) {
    subjectId.value = id
    persist()
  }

  /** 项目内的科目被增删后，下拉框需要重新拉一次 */
  async function reloadSubjects() {
    await loadSubjects(projectId.value)
    if (!subjects.value.some((s) => s.id === subjectId.value)) {
      subjectId.value = subjects.value[0]?.id ?? null
    }
    persist()
  }

  return {
    projects,
    subjects,
    projectId,
    subjectId,
    loading,
    projectMap,
    subjectMap,
    projectName,
    subjectName,
    hasProjects,
    hasSubjects,
    ensureReady,
    refreshProjects,
    selectProject,
    selectSubject,
    reloadSubjects
  }
})
