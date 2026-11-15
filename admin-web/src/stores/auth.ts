import { defineStore } from 'pinia'
import { ref } from 'vue'
import { authApi, type UserView } from '@/api'
import { clearToken, getToken, setToken } from '@/api/request'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(getToken())
  const user = ref<UserView | null>(null)

  async function login(username: string, password: string) {
    const data = await authApi.login({ username, password })
    token.value = data.token
    user.value = data.user
    setToken(data.token)
    return data.user
  }

  async function loadProfile() {
    if (!token.value) return null
    user.value = await authApi.me()
    return user.value
  }

  function logout() {
    token.value = ''
    user.value = null
    clearToken()
  }

  return { token, user, login, loadProfile, logout }
})
