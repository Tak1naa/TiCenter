import { useState, useRef, useEffect } from 'react'
import * as React from 'react'
import { useChat } from '../../hooks/useChat'
import { useAppContext } from '../../hooks/useAppContext'
import type { Reference, ChatMessage } from '../../types'
import { ArrowUp, Square, FileText, ChevronRight, ThumbsUp, ThumbsDown, X, NotebookPen } from 'lucide-react'
import { buildAuthHeaders } from '../../lib/security'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import remarkBreaks from 'remark-breaks'
import rehypeKatex from 'rehype-katex'

interface Props {
  onOpenFile?: (reference: Reference) => void
}

export default function ChatPanel({ onOpenFile }: Props) {
  const { selectedFileIds, pendingContexts, removeContext, clearContexts, chatFocusRequest } = useAppContext()
  const { messages, isStreaming, sendMessage, stopStreaming, historyLoaded } = useChat()
  const [input, setInput] = useState('')
  const scrollRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  useEffect(() => { if (chatFocusRequest) requestAnimationFrame(() => inputRef.current?.focus()) }, [chatFocusRequest])
  const stickToBottom = useRef(true)

  // 用户上翻后停止自动滚动；滚回底部附近重新吸附
  const handleScroll = () => {
    const el = scrollRef.current
    if (!el) return
    stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80
  }

  useEffect(() => {
    const el = scrollRef.current
    if (el && stickToBottom.current) {
      // 流式中每个 token 触发一次：直赋 scrollTop 而非 smooth（smooth 会排队动画导致卡顿）
      el.scrollTop = el.scrollHeight
    }
  }, [messages, isStreaming])

  const handleSend = () => {
    if (!input.trim() || isStreaming) return
    void sendMessage(input.trim(), Array.from(selectedFileIds), pendingContexts)
    clearContexts()
    setInput('')
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      <div className="chat-messages" ref={scrollRef} onScroll={handleScroll} style={{ flex: 1, overflow: 'auto', padding: '24px 32px' }}>
        {/* Progress bar during streaming */}
        {isStreaming && (
          <div style={{
            maxWidth: 760, margin: '0 auto 16px', height: 3,
            background: 'var(--bg-tertiary)', borderRadius: 2, overflow: 'hidden',
          }}>
            <div style={{
              height: '100%', width: '30%',
              background: 'var(--accent-blue)',
              borderRadius: 2,
              animation: 'progress-slide 1.5s ease-in-out infinite',
            }} />
          </div>
        )}
        {!historyLoaded ? null : messages.length === 0 ? (
          <div style={{
            display: 'flex', flexDirection: 'column',
            alignItems: 'center', justifyContent: 'center',
            height: '100%', paddingBottom: 80,
          }}>
            <h1 style={{ fontSize: 28, fontWeight: 500, color: 'var(--text-primary)', letterSpacing: '-0.02em', marginBottom: 12 }}>
              有哪里不理解？
            </h1>
            <p style={{ fontSize: 14, color: 'var(--text-secondary)', maxWidth: 480, textAlign: 'center', lineHeight: 1.6 }}>
              在文档中选中文字、框选题目，或添加当前页，再把你的问题发给我。
            </p>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 20, maxWidth: 760, margin: '0 auto', width: '100%' }}>
            {messages.map(msg => (
              <MessageBubble key={msg.id} msg={msg} onOpenFile={onOpenFile} />
            ))}
          </div>
        )}
      </div>

      <div className="chat-compose" style={{ padding: '0 32px 20px' }}>
        <div style={{ maxWidth: 760, margin: '0 auto' }}>
          <div className="context-attachments" aria-label="待发送上下文">
            {pendingContexts.map((ref, index) => <div className="context-attachment" key={index} title={ref.quote || ref.fileName}>
              <FileText size={12} /><span>{ref.fileName} · p{ref.page}{ref.kind === 'selection' ? ' · 选区' : ref.kind === 'region' ? ' · 框选' : ''}</span>
              <button aria-label={`移除上下文 ${index + 1}`} onClick={() => removeContext(index)}><X size={12} /></button>
            </div>)}
          </div>
          <div style={{
            display: 'flex', alignItems: 'center',
            background: 'var(--bg-tertiary)', borderRadius: 24,
            border: '1px solid var(--border-color)',
            padding: '6px 8px 6px 20px', boxShadow: 'var(--shadow-sm)',
          }}>
            <input
              ref={inputRef}
              aria-label="向 AI 助手提问"
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={pendingContexts.length ? '针对这些资料提问…' : '提问，或先在文件中选择资料…'}
              style={{
                flex: 1, background: 'none', border: 'none', outline: 'none',
                color: 'var(--text-primary)', fontSize: 14, fontFamily: 'inherit', lineHeight: '24px',
              }}
            />
            {isStreaming ? (
              <button onClick={stopStreaming}
                aria-label="停止生成"
                style={{ width: 36, height: 36, borderRadius: 18, background: 'var(--bg-hover)', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                <Square size={14} fill="currentColor" />
              </button>
            ) : (
              <button onClick={handleSend} disabled={!input.trim()}
                aria-label="发送消息"
                style={{ width: 36, height: 36, borderRadius: 18, background: input.trim() ? 'var(--text-primary)' : 'var(--border-color)', border: 'none', color: input.trim() ? 'var(--bg-primary)' : 'var(--text-muted)', cursor: input.trim() ? 'pointer' : 'default', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, transition: 'background 150ms' }}>
                <ArrowUp size={18} strokeWidth={2.5} />
              </button>
            )}
          </div>
          <p style={{ fontSize: 11, color: 'var(--text-muted)', textAlign: 'center', marginTop: 8 }}>
            回答基于题库内容生成，引用来源均标注文件名和页码
          </p>
        </div>
      </div>
    </div>
  )
}

/** 引用去重：同一文件+页码只保留第一条。 */
function dedupeRefs(refs: Reference[]): Reference[] {
  const seen = new Set<string>()
  const out: Reference[] = []
  for (const r of refs) {
    const key = `${r.fileName}:${r.page}`
    if (seen.has(key)) continue
    seen.add(key)
    out.push(r)
  }
  return out
}

/** 把模型输出的 \(...\) / \[...\] 转成 remark-math 支持的 $...$ / $$...$$（remark-math 6.x 不支持反斜杠括号）。 */
function normalizeMath(md: string): string {
  if (!md) return md
  // \[...\] 块级 → $$...$$（先处理块级避免与行内冲突）
  let s = md.replace(/\\\[\s*([\s\S]*?)\s*\\\]/g, (_, inner) => `\n\n$$\n${inner}\n$$\n\n`)
  // \(...\) 行内 → $...$
  s = s.replace(/\\\(([\s\S]*?)\\\)/g, (_, inner) => `$${inner}$`)
  return s
}

/** 单条消息气泡（memo：仅自身变化才重渲染，避免流式时全量重渲拖慢输入框）。 */
const MessageBubble = React.memo(function MessageBubble({ msg, onOpenFile }: { msg: ChatMessage; onOpenFile?: Props['onOpenFile'] }) {
  return (
    <div style={{ display: 'flex', justifyContent: msg.role === 'user' ? 'flex-end' : 'flex-start' }}>
      <div className="message-bubble" style={{
        maxWidth: '100%',
        minWidth: msg.role === 'user' ? undefined : 120,
        padding: msg.role === 'user' ? '10px 18px' : '16px 20px',
        borderRadius: msg.role === 'user' ? 20 : 16,
        border: msg.role === 'user' ? 'none' : '1px solid var(--chat-bubble-border)',
        background: msg.role === 'user' ? 'var(--chat-user-bg)' : 'var(--chat-ai-bg)',
        boxShadow: msg.role === 'user' ? 'none' : 'var(--shadow-sm)',
      }}>
        {!!msg.contextRefs?.length && <div className="message-contexts">{msg.contextRefs.map((r, i) => <div key={i}>{r.fileName} · 第 {r.page} 页 · {r.kind === 'selection' ? '文字选区' : r.kind === 'region' ? '框选（页级文本）' : '整页'}</div>)}</div>}
        {/* 思考过程折叠块（Claude Code 风格） */}
        {msg.thinking ? <ThinkingBlock text={msg.thinking} isStreaming={!!msg.isStreaming} /> : null}

        {/* 用户消息：纯文本；AI 流式输出：轻量 Markdown；AI 已完成：完整渲染（含公式） */}
        {msg.role === 'user' ? (
          <div style={{
            fontSize: 14, lineHeight: 1.75, userSelect: 'text',
            color: '#ffffff', whiteSpace: 'pre-wrap', wordBreak: 'break-word',
          }}>
            {msg.content}
          </div>
        ) : msg.isStreaming ? (
          <StreamingText content={msg.content} />
        ) : (
          <div className="markdown-body" style={{ fontSize: 14, lineHeight: 1.8, color: 'var(--text-primary)' }}>
            <ReactMarkdown
              remarkPlugins={[remarkGfm, remarkMath, remarkBreaks]}
              rehypePlugins={[rehypeKatex]}
              components={{
                p: ({ children }) => <p style={{ margin: '0.3em 0' }}>{children}</p>,
                table: ({ children }) => (
                  <div style={{ overflowX: 'auto', margin: '8px 0' }}>
                    <table style={{ borderCollapse: 'collapse', width: '100%', fontSize: 13 }}>{children}</table>
                  </div>
                ),
                th: ({ children }) => (
                  <th style={{ border: '1px solid var(--border-color)', padding: '6px 10px', background: 'var(--bg-tertiary)', textAlign: 'left', fontWeight: 600 }}>{children}</th>
                ),
                td: ({ children }) => (
                  <td style={{ border: '1px solid var(--border-color)', padding: '6px 10px' }}>{children}</td>
                ),
                code: ({ className, children, ...props }: any) => {
                  const isInline = !className
                  if (isInline) {
                    return <code style={{ background: 'var(--bg-tertiary)', padding: '1px 5px', borderRadius: 4, fontSize: '0.9em', fontFamily: 'monospace' }} {...props}>{children}</code>
                  }
                  return <code className={className} style={{ display: 'block', padding: '10px 14px', background: 'var(--bg-tertiary)', borderRadius: 6, fontSize: 13, overflowX: 'auto', fontFamily: 'monospace' }} {...props}>{children}</code>
                },
                pre: ({ children }) => <pre style={{ margin: '8px 0' }}>{children}</pre>,
                blockquote: ({ children }) => (
                  <blockquote style={{ borderLeft: '3px solid var(--accent-blue)', paddingLeft: 12, margin: '8px 0', color: 'var(--text-secondary)' }}>{children}</blockquote>
                ),
                a: ({ href, children }) => (
                  <a href={href} target="_blank" rel="noopener noreferrer" style={{ color: 'var(--accent-blue)' }}>{children}</a>
                ),
              }}
            >
              {normalizeMath(msg.content)}
            </ReactMarkdown>
          </div>
        )}

        {/* 截断提示弹窗 */}
        {msg.truncated && !msg.isStreaming && (
          <div style={{
            marginTop: 10, padding: '8px 12px',
            background: 'rgba(245,158,11,0.12)', borderRadius: 8,
            border: '1px solid rgba(245,158,11,0.35)',
            color: 'var(--text-secondary)', fontSize: 12.5,
            display: 'flex', alignItems: 'center', gap: 8,
          }}>
            <span style={{ fontSize: 14 }}>⚠</span>
            <span>
              回复内容较长，可能因输出长度限制被截断。可在
              <b style={{ color: 'var(--text-primary)', margin: '0 2px' }}>个人中心 → 模型选择 → 高级设置</b>
              中增大 Max Tokens 后重试。
            </span>
          </div>
        )}

        {/* 引用来源 — 同页去重 + 点击跳转 + 只显示主要引用 */}
        {msg.references && msg.references.length > 0 && !msg.isStreaming && (
          <div style={{ marginTop: 12, paddingTop: 10, borderTop: '1px solid var(--border-color)' }}>
            <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', marginBottom: 6, letterSpacing: '0.02em' }}>
              引用来源
            </div>
            {dedupeRefs(msg.references).slice(0, 8).map((ref, i) => (
              <div
                key={i}
                role="button" tabIndex={0}
                onKeyDown={e => { if (e.key === 'Enter' && ref.fileId) onOpenFile?.(ref) }}
                onClick={() => { if (ref.fileId) onOpenFile?.(ref) }}
                title={ref.fileId ? `点击打开 ${ref.fileName} 第 ${ref.page} 页` : undefined}
                style={{
                  display: 'flex', alignItems: 'flex-start', gap: 6, padding: '3px 0', fontSize: 12,
                  color: ref.fileId ? 'var(--accent-blue)' : 'var(--text-secondary)',
                  cursor: ref.fileId ? 'pointer' : 'default',
                  textDecoration: ref.fileId ? 'underline' : 'none',
                  textDecorationStyle: 'dotted', textUnderlineOffset: 3,
                }}>
                <FileText size={14} style={{ flexShrink: 0, marginTop: 2 }} />
                <span>[{ref.fileName}] 第 {ref.page} 页</span>
              </div>
            ))}
            {dedupeRefs(msg.references).length > 8 && (
              <div style={{ fontSize: 11, color: 'var(--text-muted)', padding: '2px 0' }}>
                ... 共 {dedupeRefs(msg.references).length} 处引用，仅显示主要 8 处
              </div>
            )}
          </div>
        )}
        {msg.role === 'assistant' && msg.requestId && !msg.isStreaming && (
          <FeedbackButtons requestId={msg.requestId} />
        )}
        {msg.role === 'assistant' && !!msg.content && !msg.isStreaming && !!msg.references?.length && <SaveDraft message={msg} />}
      </div>
    </div>
  )
})

function SaveDraft({ message }: { message: ChatMessage }) {
  const [open, setOpen] = useState(false)
  const [kind, setKind] = useState('note')
  const [title, setTitle] = useState('')
  const [content, setContent] = useState(message.content)
  const [status, setStatus] = useState('')
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const save = async (e: React.FormEvent) => {
    e.preventDefault(); setSaving(true); setStatus('')
    try {
      const contextRefs = (message.references || []).filter(r => r.fileId && r.generationId).slice(0, 8)
        .map(r => ({ fileId: r.fileId, generationId: r.generationId, page: r.page, kind: 'page' }))
      if (!contextRefs.length) throw new Error('引用没有可验证的版本，请重新提问')
      const response = await fetch('/api/workspace/items', { method: 'POST', headers: buildAuthHeaders(),
        body: JSON.stringify({ kind, title, content, contextRefs }) })
      if (!response.ok) throw new Error(response.status === 409 ? '资料已更新，请重新核对后保存' : '保存失败，请稍后重试')
      setSaved(true); setOpen(false); setStatus('已保存到“我的整理”')
    } catch (e) { setStatus(e instanceof Error ? e.message : '保存失败') }
    finally { setSaving(false) }
  }
  return <div className="save-draft">
    {!open && <button disabled={saved} onClick={() => setOpen(true)}><NotebookPen size={13} />保存整理</button>}
    {open && <form onSubmit={save}>
      <label>保存为<select value={kind} onChange={e => setKind(e.target.value)}><option value="note">笔记</option><option value="question">题目草稿</option></select></label>
      <input aria-label="整理标题" placeholder="标题" maxLength={160} required value={title} onChange={e => setTitle(e.target.value)} />
      <textarea aria-label="整理内容" required maxLength={60000} value={content} onChange={e => setContent(e.target.value)} />
      <p>请核对答案与引用。保存内容只属于你的整理，题目草稿不会直接发布到正式题库。</p>
      <div><button type="submit" disabled={saving}>{saving ? '保存中…' : '确认保存'}</button><button type="button" onClick={() => setOpen(false)}>取消</button></div>
    </form>}
    {status && <p role="status">{status}</p>}
  </div>
}

function FeedbackButtons({ requestId }: { requestId: string }) {
  const [submitted, setSubmitted] = useState<'up' | 'down' | null>(null)
  const submit = async (helpful: boolean) => {
    if (submitted) return
    const response = await fetch(`/api/chat/${encodeURIComponent(requestId)}/feedback`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ helpful, errorType: helpful ? null : 'ANSWER' }),
    })
    if (response.ok) setSubmitted(helpful ? 'up' : 'down')
  }
  return (
    <div style={{ display: 'flex', gap: 6, marginTop: 10, color: 'var(--text-muted)' }}>
      <button aria-label="回答有帮助" onClick={() => submit(true)} disabled={!!submitted}
        style={{ border: 'none', background: 'none', color: submitted === 'up' ? 'var(--accent-blue)' : 'inherit', cursor: submitted ? 'default' : 'pointer', padding: 3 }}>
        <ThumbsUp size={14} />
      </button>
      <button aria-label="回答需要改进" onClick={() => submit(false)} disabled={!!submitted}
        style={{ border: 'none', background: 'none', color: submitted === 'down' ? 'var(--accent-red)' : 'inherit', cursor: submitted ? 'default' : 'pointer', padding: 3 }}>
        <ThumbsDown size={14} />
      </button>
      {submitted && <span style={{ fontSize: 11 }}>已记录反馈</span>}
    </div>
  )
}

/** 流式中轻量渲染：粗体/标题/行内代码即时生效；公式等完整渲染留待完成。 */
function StreamingText({ content }: { content: string }) {
  const lines = content.split('\n')
  return (
    <div style={{ fontSize: 14, lineHeight: 1.75, userSelect: 'text', color: 'var(--text-primary)', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
      {lines.map((line, i) => {
        // 行内加粗 **...** → <strong>
        const parts = line.split(/\*\*(.+?)\*\*/g).map((p, j) =>
          j % 2 === 1 ? <strong key={j} style={{ fontWeight: 700 }}>{p}</strong> : <span key={j}>{p}</span>
        )
        // 行内代码 `...` → <code>
        const rich = parts.map((el, j) => {
          if (el.type !== 'span') return el
          const text = (el.props.children as string) ?? ''
          const codeParts = text.split(/`([^`]+)`/g).map((p, k) =>
            k % 2 === 1
              ? <code key={k} style={{ background: 'var(--bg-tertiary)', padding: '1px 5px', borderRadius: 4, fontSize: '0.9em', fontFamily: 'monospace' }}>{p}</code>
              : <span key={k}>{p}</span>
          )
          return <span key={j}>{codeParts}</span>
        })
        // 标题 #/##/###
        const heading = line.match(/^(#{1,3})\s+(.+)$/)
        if (heading) {
          const size = heading[1].length === 1 ? 20 : heading[1].length === 2 ? 17 : 15
          return <div key={i} style={{ fontSize: size, fontWeight: 600, margin: '8px 0 4px', color: 'var(--text-primary)' }}>{heading[2]}</div>
        }
        return <div key={i}>{rich}</div>
      })}
      <span style={{ color: 'var(--text-muted)' }}>▊</span>
    </div>
  )
}

/** 思考过程折叠块：流式中灰底实时滚动显示，完成后折叠为一行可展开。 */
function ThinkingBlock({ text, isStreaming }: { text: string; isStreaming: boolean }) {
  const [open, setOpen] = useState(isStreaming) // 流式中默认展开，完成后折叠
  const bodyRef = useRef<HTMLDivElement>(null)
  const userScrolled = useRef(false) // 用户手动上翻后停止自动滚动

  // 流式中自动滚动到思考块底部（仅当用户未主动上翻）
  useEffect(() => {
    if (isStreaming && open && bodyRef.current && !userScrolled.current) {
      bodyRef.current.scrollTop = bodyRef.current.scrollHeight
    }
  }, [text, isStreaming, open])

  return (
    <div style={{
      marginBottom: 10, borderRadius: 8, overflow: 'hidden',
      border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)',
    }}>
      <button onClick={() => setOpen(v => !v)} style={{
        width: '100%', display: 'flex', alignItems: 'center', gap: 6,
        padding: '6px 10px', border: 'none', background: 'none',
        color: 'var(--text-secondary)', cursor: 'pointer', fontSize: 12, fontFamily: 'inherit', textAlign: 'left',
      }}>
        <ChevronRight size={13} style={{ transform: open ? 'rotate(90deg)' : 'none', transition: 'transform 150ms', flexShrink: 0 }} />
        <span style={{ fontWeight: 500 }}>{isStreaming ? '思考中...' : '已思考'}</span>
        <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>{text.length} 字</span>
      </button>
      {open && (
        <div ref={bodyRef}
          onWheel={() => { userScrolled.current = true }}
          onTouchMove={() => { userScrolled.current = true }}
          onScroll={e => {
            const el = e.currentTarget
            // 用户滚到顶部附近 → 解除锁定，允许再次自动滚动
            if (el.scrollTop <= 2) userScrolled.current = false
          }}
          style={{
            maxHeight: 220, overflow: 'auto', padding: '8px 12px 12px',
            fontSize: 12.5, lineHeight: 1.7, color: 'var(--text-secondary)',
            whiteSpace: 'pre-wrap', wordBreak: 'break-word', borderTop: '1px solid var(--border-color)',
          }}>
          {text}
        </div>
      )}
    </div>
  )
}
