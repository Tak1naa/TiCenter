import { forwardRef, useImperativeHandle, useEffect, useRef, useState } from 'react'
import { Loader } from 'lucide-react'

export interface DocxViewerHandle {
  jumpTo: (page: number) => void // 1-based
}

interface Props {
  url: string
  fileName: string
  initialPage?: number // 1-based
}

const DocxViewer = forwardRef<DocxViewerHandle, Props>(({ url, fileName, initialPage }, ref) => {
  const containerRef = useRef<HTMLDivElement>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [pageCount, setPageCount] = useState(0)

  // 页 offsetTop 映射（渲染后构建）
  const pageOffsets = useRef<number[]>([])
  const pendingJump = useRef<number | null>(null)

  useEffect(() => {
    let cancelled = false
    const container = containerRef.current
    if (!container) return

    ;(async () => {
      try {
        const { renderAsync } = await import('docx-preview')
        if (cancelled) return
        const resp = await fetch(url)
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
        const buffer = await resp.arrayBuffer()
        if (cancelled) return
        container.innerHTML = ''
        await renderAsync(buffer, container, undefined, {
          className: 'docx-preview',
          inWrapper: true,
          ignoreWidth: false,
          ignoreHeight: false,
          breakPages: true,
          ignoreLastRenderedPageBreak: true,
          experimental: true,
          trimXmlDeclaration: true,
          renderHeaders: true,
          renderFooters: true,
          renderFootnotes: true,
          renderEndnotes: true,
        })
        if (cancelled) return
        // 收集每页 section 的位置
        const pages = container.querySelectorAll('.docx-wrapper > section')
        pageOffsets.current = Array.from(pages).map(p => (p as HTMLElement).offsetTop)
        setPageCount(pageOffsets.current.length)
        setLoading(false)
        // 打开定位：initialPage
        const target = pendingJump.current ?? initialPage ?? 1
        if (pageOffsets.current.length) jumpToInternal(target)
      } catch (err: any) {
        if (!cancelled) { setError(err?.message || '渲染失败'); setLoading(false) }
      }
    })()

    return () => { cancelled = true; container.innerHTML = '' }
  }, [url, initialPage])

  const jumpToInternal = (page: number) => {
    const idx = Math.max(0, Math.min(pageOffsets.current.length - 1, page - 1))
    if (pageOffsets.current.length === 0) { pendingJump.current = page; return }
    const container = containerRef.current
    if (!container) return
    const el = container.querySelectorAll('.docx-wrapper > section')[idx] as HTMLElement | undefined
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  useImperativeHandle(ref, () => ({
    jumpTo: (page: number) => jumpToInternal(page),
  }), [pageCount])

  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0, background: '#525659' }}>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8, padding: '4px 10px',
        background: 'var(--bg-secondary)', borderBottom: '1px solid var(--border-color)', flexShrink: 0, fontSize: 12,
      }}>
        <span style={{ fontSize: 11, color: 'var(--text-muted)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 200 }}>{fileName}</span>
        <span style={{ flex: 1 }} />
        {pageCount > 0 && <span style={{ fontSize: 11, color: 'var(--text-secondary)' }}>{pageCount} 页</span>}
      </div>
      <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
        {loading && (
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%', color: '#9aa0a6', gap: 12 }}>
            <Loader size={24} className="animate-spin" />
            <span style={{ fontSize: 13 }}>渲染文档中...</span>
          </div>
        )}
        {error && (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', padding: 20 }}>
            <div style={{ padding: 16, borderRadius: 8, background: 'rgba(239,68,68,0.15)', color: '#f28b82', fontSize: 13, maxWidth: 380, textAlign: 'center' }}>{error}</div>
          </div>
        )}
        <div ref={containerRef} style={{ background: '#fff', minHeight: '100%' }} />
      </div>
    </div>
  )
})

DocxViewer.displayName = 'DocxViewer'

export default DocxViewer
