import { useState, useCallback, useRef, useEffect } from 'react'
import type { ChatMessage, Reference, ContextAttachment } from '../types'
import { moderateContent, buildAuthHeaders } from '../lib/security'
import { getLocalOrCookie, setLocalAndCookie } from '../lib/cookie'
import { getAIConfigForRequest } from '../lib/aiConfig'

const API_BASE = '/api'
const SESSION_KEY = 'exam_bank_session_id'

function getOrCreateSessionId(): string {
  const stored = getLocalOrCookie(SESSION_KEY)
  if (stored) return stored
  // sessionId 以登录学号开头（后端强制归属校验）
  let studentNo = 'guest'
  try {
    const info = JSON.parse(localStorage.getItem('student-info') || '{}')
    if (info.studentNo) studentNo = info.studentNo
  } catch { /* ignore */ }
  const id = `${studentNo}_${crypto.randomUUID().substring(0, 8)}`
  setLocalAndCookie(SESSION_KEY, id)
  return id
}

export function useChat() {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [isStreaming, setIsStreaming] = useState(false)
  const [sessionId] = useState(getOrCreateSessionId)
  const [historyLoaded, setHistoryLoaded] = useState(false)
  const abortRef = useRef<AbortController | null>(null)
  const assistantContentRef = useRef('')

  // Load chat history from backend on mount
  useEffect(() => {
    let cancelled = false
    async function load() {
      try {
        const studentToken = localStorage.getItem('student-token')
        const resp = await fetch(`${API_BASE}/chat/history?sessionId=${encodeURIComponent(sessionId)}`, {
          headers: studentToken ? { 'X-Student-Token': studentToken } : undefined,
        })
        if (!resp.ok || cancelled) return
        const data = await resp.json()
        if (!Array.isArray(data) || data.length === 0) {
          setHistoryLoaded(true)
          return
        }
        const msgs: ChatMessage[] = data.map((h: any) => ({
          id: String(h.id),
          role: h.role as 'user' | 'assistant',
          content: h.content || '',
          references: parseReferencesJson(h.referencesJson),
        }))
        if (!cancelled) setMessages(msgs)
      } catch { /* silent — history is best-effort */ }
      finally { if (!cancelled) setHistoryLoaded(true) }
    }
    load()
    return () => { cancelled = true }
  }, [sessionId])

  const sendMessage = useCallback(async (question: string, selectedFileIds?: number[], contextRefs?: ContextAttachment[]) => {
    const moderation = moderateContent(question)
    if (!moderation.allowed) {
      const blockedMsg: ChatMessage = {
        id: Date.now().toString(),
        role: 'user',
        content: question,
      }
      const errorMsg: ChatMessage = {
        id: (Date.now() + 1).toString(),
        role: 'assistant',
        content: moderation.reason || '输入包含不合规内容，请修改后重试。',
        references: [],
      }
      setMessages(prev => [...prev, blockedMsg, errorMsg])
      return
    }

    const sanitized = moderation.sanitized

    const userMsg: ChatMessage = {
      id: Date.now().toString(),
      role: 'user',
      content: sanitized,
      contextRefs,
    }
    setMessages(prev => [...prev, userMsg])

    const assistantMsg: ChatMessage = {
      id: (Date.now() + 1).toString(),
      role: 'assistant',
      content: '',
      references: [],
      isStreaming: true,
      contextRefs,
    }
    setMessages(prev => [...prev, assistantMsg])

    setIsStreaming(true)
    abortRef.current = new AbortController()
    assistantContentRef.current = ''
    // 首次发送时请求浏览器通知权限（AI 回复完成时推送）
    if ('Notification' in window && Notification.permission === 'default') {
      Notification.requestPermission().catch(() => {})
    }

    try {
      const aiConfig = getAIConfigForRequest()
      const body = {
        question: sanitized,
        sessionId,
        selectedFileIds: contextRefs?.length ? [...new Set(contextRefs.map(r => r.fileId))] : (selectedFileIds ?? []),
        contextRefs: contextRefs?.map(({ fileId, generationId, page, kind, quote, bbox }) => ({ fileId, generationId, page, kind, quote, bbox })),
        provider: aiConfig.providerId || undefined,
        model: aiConfig.model || undefined,
        // Zhipu credentials are server-managed only; never forward a browser value.
        apiKey: /^zhipu(_en)?$/.test(aiConfig.providerId || '') ? undefined : (aiConfig.apiKey || undefined),
        baseUrl: aiConfig.baseUrl || undefined,
        maxTokens: aiConfig.maxTokens || 8192,
        // 多模态模型：命中 preset 标记或模型名启发式
        multimodal: isMultimodal(aiConfig),
      }
      const headers = buildAuthHeaders()
      // 学生会话 token（统一鉴权）
      const studentToken = localStorage.getItem('student-token')
      if (studentToken) headers['X-Student-Token'] = studentToken
      const response = await fetch(`${API_BASE}/chat/ask`, {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        signal: abortRef.current.signal,
      })

      // 非 2xx：后端返回 JSON 错误体（401/403/429/500），直接显示而非当 SSE 解析成空气泡
      if (!response.ok) {
        let errMsg = `请求失败（HTTP ${response.status}）`
        try {
          const j = await response.json()
          if (j && j.error) errMsg = String(j.error)
        } catch { /* 非 JSON 错误体 */ }
        throw new Error(errMsg)
      }

      const reader = response.body?.getReader()
      if (!reader) return

      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (let i = 0; i < lines.length; i++) {
          const line = lines[i]
          if (line.startsWith('event:') || !line.startsWith('data:')) continue
          const data = line.slice(5).trim()
          if (!data) continue

          const prevEventLine = lines[i - 1]
          const isSource = prevEventLine?.startsWith('event:source')
          const isDone = prevEventLine?.startsWith('event:done')
          const isReason = prevEventLine?.startsWith('event:reason')
          const isError = prevEventLine?.startsWith('event:error')
          const isMeta = prevEventLine?.startsWith('event:meta')

          if (isMeta) {
            try {
              const meta = JSON.parse(data)
              setMessages(prev => prev.map(m => m.id === assistantMsg.id
                ? { ...m, requestId: meta.requestId, retrievalMode: meta.retrievalMode, degraded: !!meta.degraded }
                : m))
            } catch { /* skip malformed metadata */ }
          } else if (isError) {
            // 服务端错误事件（AI 服务不可用/会话错误等）→ 显示到消息气泡
            try {
              const errData = JSON.parse(data)
              const errMsg = errData.error || '服务错误'
              setMessages(prev => prev.map(m =>
                m.id === assistantMsg.id
                  ? { ...m, content: m.content ? m.content + `\n\n⚠ ${errMsg}` : `⚠ ${errMsg}` }
                  : m
              ))
            } catch {
              setMessages(prev => prev.map(m =>
                m.id === assistantMsg.id
                  ? { ...m, content: m.content ? m.content + `\n\n⚠ ${data}` : `⚠ ${data}` }
                  : m
              ))
            }
          } else if (isDone) {
            try {
              const doneData = JSON.parse(data)
              if (doneData.sessionId && doneData.sessionId !== sessionId) {
                setLocalAndCookie(SESSION_KEY, doneData.sessionId)
              }
              if (doneData.requestId) {
                setMessages(prev => prev.map(m => m.id === assistantMsg.id
                  ? { ...m, requestId: doneData.requestId }
                  : m))
              }
              // done 事件携带过滤后的引用（AI 实际引用的页），直接覆盖
              if (Array.isArray(doneData.references)) {
                setMessages(prev => prev.map(m =>
                  m.id === assistantMsg.id
                    ? { ...m, references: doneData.references as Reference[] }
                    : m
                ))
              }
              if (doneData.truncated) {
                setMessages(prev => prev.map(m =>
                  m.id === assistantMsg.id
                    ? { ...m, truncated: true }
                    : m
                ))
              }
              // 回复完成 → 浏览器推送通知
              try {
                const preview = assistantContentRef.current?.slice(0, 120) || 'AI 回复已生成'
                notifyComplete(preview, !!doneData.truncated)
              } catch { /* push not available */ }
            } catch { /* skip */ }
          } else if (isReason) {
            // 思考过程实时累积到 msg.thinking（不写入正文）
            try {
              const parsed = JSON.parse(data)
              if (parsed.content) {
                setMessages(prev => prev.map(m =>
                  m.id === assistantMsg.id
                    ? { ...m, thinking: (m.thinking || '') + parsed.content }
                    : m
                ))
              }
            } catch { /* skip parse errors */ }
          } else if (isSource) {
            try {
              const ref: Reference = JSON.parse(data)
              // 引用去重：同一文件+页码只保留一条
              setMessages(prev => prev.map(m => {
                if (m.id !== assistantMsg.id) return m
                const refs = m.references || []
                if (refs.some(r => r.fileName === ref.fileName && r.page === ref.page)) return m
                return { ...m, references: [...refs, ref] }
              }))
            } catch { /* skip parse errors */ }
          } else {
            // answer 事件：JSON 包裹 {content}，换行转义为 \n，保证 SSE 单行 data
            try {
              const parsed = JSON.parse(data)
              if (parsed.content) {
                assistantContentRef.current += parsed.content
                setMessages(prev => prev.map(m =>
                  m.id === assistantMsg.id
                    ? { ...m, content: m.content + parsed.content }
                    : m
                ))
              }
            } catch {
              // 兼容纯文本 answer（老版本后端）
              assistantContentRef.current += data
              setMessages(prev => prev.map(m =>
                m.id === assistantMsg.id
                  ? { ...m, content: m.content + data }
                  : m
              ))
            }
          }
        }
      }
    } catch (err: any) {
      if (err.name !== 'AbortError') {
        console.error('聊天请求失败:', err)
        // 连接失败可见：AI 服务不可用时给用户明确提示（不再静默空白）
        const isNetwork = err instanceof TypeError || (err && (err as any).status === 0)
        setMessages(prev => prev.map(m =>
          m.id === assistantMsg.id
            ? { ...m, content: isNetwork ? '⚠ AI 服务暂时不可用，请稍后重试。' : `⚠ 请求失败：${err?.message || '未知错误'}` }
            : m
        ))
      }
    } finally {
      setMessages(prev => prev.map(m =>
        m.id === assistantMsg.id ? { ...m, isStreaming: false } : m
      ))
      setIsStreaming(false)
    }
  }, [sessionId])

  const stopStreaming = useCallback(() => {
    abortRef.current?.abort()
  }, [])

  return { messages, isStreaming, sendMessage, stopStreaming, setMessages, historyLoaded }
}

// 多模态判断：按模型名启发式（gemini/gpt-4o/vision/vl/qwen-vl/llama-4 等）
function isMultimodal(aiConfig: any): boolean {
  const model = (aiConfig.model || '').toLowerCase()
  if (!model) return false
  return /(gemini|gpt-4o|vision|glm-[\d.]+v|vl|qwen2.*vl|llama-4)/.test(model)
}

// 浏览器推送：AI 回复完成时通知（需要用户授权 Notification API）
function notifyComplete(preview: string, truncated: boolean) {
  try {
    if (!('Notification' in window)) return
    if (Notification.permission === 'granted') {
      new Notification(truncated ? 'AI 回复已生成（内容较长，可能被截断）' : 'AI 回复已生成', {
        body: preview,
        tag: 'chat-complete',
      })
    }
  } catch { /* push not available */ }
}

function parseReferencesJson(json: string | null): Reference[] {
  if (!json) return []
  try {
    const arr = JSON.parse(json)
    if (!Array.isArray(arr)) return []
    // 同页去重；稳定 fileId 允许刷新后继续跳转，不暴露服务端绝对路径。
    const seen = new Set<string>()
    const result: Reference[] = []
    for (const r of arr) {
      const fileName = r.fileName || ''
      const page = r.page || 1
      const key = `${fileName}:${page}`
      if (seen.has(key)) continue
      seen.add(key)
      result.push({
        fileName,
        page,
        snippet: r.snippet || '',
        fileId: r.fileId,
        sourceId: r.sourceId,
        documentVersion: r.documentVersion,
        generationId: r.generationId,
        accessScope: r.accessScope,
      })
    }
    return result
  } catch {
    return []
  }
}
