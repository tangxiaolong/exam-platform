import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

/** 后端统一响应体 */
export interface ApiResult<T = any> {
  code: string
  message: string
  data: T
  traceId?: string
}

export interface PageResult<T = any> {
  list: T[]
  total: number
  pageNo: number
  pageSize: number
}

const TOKEN_KEY = 'exam_admin_token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
}

const http: AxiosInstance = axios.create({
  baseURL: '/api/v1',
  timeout: 20000
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/** 文件下载等非 JSON 响应（响应体是 Blob）不走统一响应体的错误码校验 */
function isBinaryResponse(response: { config?: AxiosRequestConfig }): boolean {
  const type = response.config?.responseType
  return type === 'blob' || type === 'arraybuffer'
}

http.interceptors.response.use(
  (response) => {
    if (isBinaryResponse(response)) {
      return response
    }
    const body = response.data as ApiResult
    // 空响应体（如 204）不参与错误码判断
    if (body && body.code && body.code !== '0') {
      if (body.code.startsWith('AUTH_')) {
        clearToken()
        if (!location.hash.includes('/login')) {
          location.hash = '#/login'
        }
      }
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message || body.code))
    }
    return response
  },
  async (error) => {
    // 下载接口在失败时返回 JSON，但请求声明的 responseType 是 blob，需要先读回错误信息
    const data = error?.response?.data
    if (data instanceof Blob) {
      try {
        const body = JSON.parse(await data.text()) as ApiResult
        ElMessage.error(body.message || '下载失败')
        return Promise.reject(new Error(body.message || body.code))
      } catch {
        // 忽略解析失败，落到通用提示
      }
    }
    ElMessage.error(error?.response?.status === 404 ? '接口不存在' : '网络异常，请稍后重试')
    return Promise.reject(error)
  }
)

/** 统一返回 data，业务代码无需重复拆包 */
export async function request<T = any>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<ApiResult<T>>(config)
  if (isBinaryResponse(response)) {
    return response.data as T
  }
  return response.data.data
}

export default http
