import { readonly, ref } from 'vue'

// 沿用首页的存储键，让已有的白天／黑夜选择直接应用到工作台。
const storageKey = 'quantcloud.landing.theme'
const preference = ref('dark')

if (typeof window !== 'undefined') {
  try {
    if (window.localStorage.getItem(storageKey) === 'light') preference.value = 'light'
  } catch {
    // 存储不可用时，本次访问仍可切换。
  }
}

export const colorTheme = readonly(preference)

export function toggleColorTheme() {
  preference.value = preference.value === 'dark' ? 'light' : 'dark'
  try {
    window.localStorage.setItem(storageKey, preference.value)
  } catch {
    // 保留当前页面中的选择。
  }
}

export function syncColorTheme(event) {
  if (event.key === storageKey) {
    preference.value = event.newValue === 'light' ? 'light' : 'dark'
  }
}
