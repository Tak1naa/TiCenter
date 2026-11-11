// 轻量 cookie 封装：localStorage 与 cookie 双写，本地存储清空时从 cookie 恢复。
// 仅用于非敏感状态。API key 绝不能写入 localStorage 或 JS 可读 cookie。

const COOKIE_KEYS = ['exam_bank_session_id'] as const

export function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp('(?:^|;\\s*)' + encodeURIComponent(name) + '=([^;]*)'))
  return match ? decodeURIComponent(match[1]) : null
}

export function writeCookie(name: string, value: string, days = 365) {
  const expires = new Date(Date.now() + days * 86400e3).toUTCString()
  document.cookie = `${encodeURIComponent(name)}=${encodeURIComponent(value)};expires=${expires};path=/;SameSite=Lax`
}

export function clearCookie(name: string) {
  document.cookie = `${encodeURIComponent(name)}=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/;SameSite=Lax`
}

/** 读 localStorage，缺失时从 cookie 恢复（并回写 localStorage）。 */
export function getLocalOrCookie(key: string): string | null {
  const fromLs = localStorage.getItem(key)
  if (fromLs !== null) return fromLs
  const fromCookie = readCookie(key)
  if (fromCookie !== null) {
    localStorage.setItem(key, fromCookie)
    return fromCookie
  }
  return null
}

/** 写 localStorage 并同步到 cookie。 */
export function setLocalAndCookie(key: string, value: string) {
  localStorage.setItem(key, value)
  if ((COOKIE_KEYS as readonly string[]).includes(key)) writeCookie(key, value)
}

/** 从 localStorage 和 cookie 中删除。 */
export function removeLocalAndCookie(key: string) {
  localStorage.removeItem(key)
  clearCookie(key)
}
