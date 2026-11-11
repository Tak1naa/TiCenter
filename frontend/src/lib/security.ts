/**
 * HTML entity escaping for XSS prevention.
 */
export function sanitizeText(text: string): string {
  return text
    .replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '')
    .replace(/on\w+\s*=\s*"[^"]*"/gi, '')
    .replace(/on\w+\s*=\s*'[^']*'/gi, '')
    .replace(/javascript\s*:/gi, '')
}

export function sanitizeHtml(dirty: string): string {
  const map: Record<string, string> = {
    '&': '&amp;', '<': '&lt;', '>': '&gt;',
    '"': '&quot;', "'": '&#x27;', '/': '&#x2F;',
  }
  return dirty.replace(/[&<>"'/]/g, c => map[c])
}

const ALLOWED_TAGS = new Set([
  'p', 'br', 'strong', 'em', 'b', 'i', 'u', 's', 'code', 'pre',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'ul', 'ol', 'li', 'blockquote', 'a', 'table', 'thead', 'tbody',
  'tr', 'th', 'td', 'hr', 'span', 'div',
])

export function sanitizeMarkdown(html: string): string {
  return html.replace(/<(\/?)(\w+)[^>]*>/gi, (match, slash, tag) => {
    if (ALLOWED_TAGS.has(tag.toLowerCase())) {
      if (tag.toLowerCase() === 'a') {
        return match.replace(/on\w+="[^"]*"/gi, '')
          .replace(/href="javascript:[^"]*"/gi, 'href="#"')
      }
      return match
    }
    return `&lt;${slash}${tag}&gt;`
  })
}

/**
 * Build JSON request headers（签名已移除——盐值前端可见无防护价值，鉴权走 token）。
 */
export function buildAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Client-Version': '1.0',
  }
  const csrf = readCookieValue('EXAM_CSRF')
  if (csrf) headers['X-CSRF-Token'] = csrf
  return headers
}

function readCookieValue(name: string): string | null {
  const match = document.cookie.match(new RegExp('(?:^|;\\s*)' + encodeURIComponent(name) + '=([^;]*)'))
  return match ? decodeURIComponent(match[1]) : null
}

/** Adds same-origin credentials and double-submit CSRF to every API mutation. */
export function installSecureFetch() {
  const originalFetch = window.fetch.bind(window)
  window.fetch = (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    if (!url.startsWith('http') || url.startsWith(window.location.origin)) {
      const method = (init.method || (input instanceof Request ? input.method : 'GET')).toUpperCase()
      const headers = new Headers(init.headers || (input instanceof Request ? input.headers : undefined))
      for (const name of ['X-Student-Token', 'X-Admin-Token', 'X-Auth-Token']) {
        const value = headers.get(name)
        if (value === '' || value === 'cookie') headers.delete(name)
      }
      const csrf = readCookieValue('EXAM_CSRF')
      if (csrf && !['GET', 'HEAD', 'OPTIONS'].includes(method)) headers.set('X-CSRF-Token', csrf)
      init = { ...init, headers, credentials: 'same-origin' }
    }
    return originalFetch(input, init)
  }
}

const BLOCKED_PATTERNS: RegExp[] = [
  /<script\b/i,
  /<iframe\b/i,
  /<object\b/i,
  /<embed\b/i,
  /on\w+\s*=\s*["']?/i,
  /javascript\s*:/i,
  /data\s*:\s*text\/html/i,
  /<\/?(script|iframe|object|embed|link|meta|base)\b/i,
]

const SENSITIVE_WORDS = [
  'fuck', 'shit', 'damn', 'asshole', 'bastard',
  '色情', '赌博', '毒品', '枪支', '暴力恐怖',
]

export type ModerationResult = {
  allowed: boolean
  reason?: string
  sanitized: string
}

/**
 * Content moderation — checks for XSS patterns and sensitive words.
 */
export function moderateContent(input: string): ModerationResult {
  for (const pattern of BLOCKED_PATTERNS) {
    if (pattern.test(input)) {
      return {
        allowed: false,
        reason: '检测到潜在的代码注入内容',
        sanitized: sanitizeText(input),
      }
    }
  }

  const lower = input.toLowerCase()
  for (const word of SENSITIVE_WORDS) {
    if (lower.includes(word)) {
      return {
        allowed: false,
        reason: '输入包含不当内容',
        sanitized: '',
      }
    }
  }

  if (input.length > 10000) {
    return {
      allowed: false,
      reason: '输入内容过长（最大10000字符）',
      sanitized: input.substring(0, 10000),
    }
  }

  return {
    allowed: true,
    sanitized: sanitizeText(input),
  }
}

/**
 * Validate file type and size for uploads.
 */
export function validateFile(file: File): { valid: boolean; reason?: string } {
  const allowedExtensions = ['.pdf', '.docx', '.doc', '.pptx', '.ppt', '.txt', '.md', '.png', '.jpg', '.jpeg', '.bmp', '.tiff']
  const ext = '.' + file.name.split('.').pop()?.toLowerCase()
  if (!allowedExtensions.includes(ext)) {
    return { valid: false, reason: `不支持的文件类型: ${ext}` }
  }
  const maxSize = 100 * 1024 * 1024
  if (file.size > maxSize) {
    return { valid: false, reason: '文件过大（最大100MB）' }
  }
  if (file.name.length > 255) {
    return { valid: false, reason: '文件名过长' }
  }
  return { valid: true }
}
