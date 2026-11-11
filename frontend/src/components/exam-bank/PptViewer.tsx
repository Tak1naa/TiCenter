import { forwardRef, useImperativeHandle, useEffect, useRef, useState } from 'react'
import { Loader } from 'lucide-react'
import type { PptxViewer as PptxViewerType } from '@aiden0z/pptx-renderer'

export interface PptViewerHandle {
  jumpTo: (page: number) => void // 1-based
}

interface Props {
  url: string
  fileName: string
  initialPage?: number // 1-based
}

const PptViewer = forwardRef<PptViewerHandle, Props>(({ url, fileName, initialPage }, ref) => {
  const containerRef = useRef<HTMLDivElement>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [slideCount, setSlideCount] = useState(0)

  const viewerRef = useRef<PptxViewerType | null>(null)
  const pendingJump = useRef<number | null>(null)

  useEffect(() => {
    let cancelled = false
    let viewer: PptxViewerType | null = null
    ;(async () => {
      try {
        // 库的真实 API：PptxViewer.open(buffer, container, opts)（无 default 导出）
        const { PptxViewer } = await import('@aiden0z/pptx-renderer')
        if (cancelled) return
        const resp = await fetch(url)
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
        const buffer = await resp.arrayBuffer()
        if (cancelled || !containerRef.current) return
        viewer = await PptxViewer.open(buffer, containerRef.current, {
          fitMode: 'contain',
          renderMode: 'list',
          listOptions: { showSlideLabels: true },
        })
        viewerRef.current = viewer
        if (cancelled) return
        setSlideCount(viewer?.slideCount ?? 0)
        setLoading(false)
        jumpToInternal(pendingJump.current ?? initialPage ?? 1)
      } catch (err: any) {
        if (!cancelled) { setError(err?.message || 'PPT 渲染失败'); setLoading(false) }
      }
    })()
    return () => {
      cancelled = true
      viewerRef.current = null
      try { viewer?.destroy?.() } catch { /* ignore */ }
    }
  }, [url, initialPage])

  const jumpToInternal = (page: number) => {
    const v = viewerRef.current
    if (!v) { pendingJump.current = page; return }
    const max = Math.max(0, (v.slideCount ?? 1) - 1)
    v.goToSlide(Math.max(0, Math.min(max, page - 1)))
  }

  useImperativeHandle(ref, () => ({
    jumpTo: (page: number) => jumpToInternal(page),
  }), [slideCount])

  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0, background: '#525659' }}>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8, padding: '4px 10px',
        background: 'var(--bg-secondary)', borderBottom: '1px solid var(--border-color)', flexShrink: 0, fontSize: 12,
      }}>
        <span style={{ fontSize: 11, color: 'var(--text-muted)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 200 }}>{fileName}</span>
        <span style={{ flex: 1 }} />
        {slideCount > 0 && <span style={{ fontSize: 11, color: 'var(--text-secondary)' }}>{slideCount} 页</span>}
      </div>
      <div style={{ flex: 1, minHeight: 0, overflow: 'auto', padding: 8 }}>
        {loading && (
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%', color: '#9aa0a6', gap: 12 }}>
            <Loader size={24} className="animate-spin" />
            <span style={{ fontSize: 13 }}>渲染演示文稿中...</span>
          </div>
        )}
        {error && (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', padding: 20 }}>
            <div style={{ padding: 16, borderRadius: 8, background: 'rgba(239,68,68,0.15)', color: '#f28b82', fontSize: 13, maxWidth: 380, textAlign: 'center' }}>{error}</div>
          </div>
        )}
        <div ref={containerRef} />
      </div>
    </div>
  )
})

PptViewer.displayName = 'PptViewer'

export default PptViewer
