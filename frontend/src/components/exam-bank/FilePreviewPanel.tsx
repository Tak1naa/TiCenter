import { lazy, Suspense, useState, useRef, useCallback, useEffect } from 'react'
import { X, GripHorizontal, RotateCw } from 'lucide-react'
import type { PdfViewerHandle } from './PdfViewer'
import type { DocxViewerHandle } from './DocxViewer'
import type { PptViewerHandle } from './PptViewer'

const PdfViewer = lazy(() => import('./PdfViewer'))
const DocxViewer = lazy(() => import('./DocxViewer'))
const PptViewer = lazy(() => import('./PptViewer'))
const XlsxViewer = lazy(() => import('./XlsxViewer'))

interface Props {
  fileId: number
  fileName: string
  onClose: () => void
  initialPage?: number
}

const IMAGE_EXTS = /\.(png|jpg|jpeg|gif|bmp|tiff)$/i
const PDF_EXTS = /\.pdf$/i
const DOCX_EXTS = /\.(docx|doc)$/i
const PPT_EXTS = /\.(pptx|ppt)$/i
const XLSX_EXTS = /\.(xlsx|xls)$/i

export default function FilePreviewPanel({ fileId, fileName, onClose, initialPage = 1 }: Props) {
  // 浮动窗口位置和尺寸
  const [pos, setPos] = useState({ x: 120, y: 60 })
  const [size, setSize] = useState({ w: 720, h: 560 })
  const dragging = useRef(false)
  const resizing = useRef(false)
  const dragStart = useRef({ x: 0, y: 0, px: 0, py: 0, pw: 0, ph: 0 })

  const viewerRef = useRef<PdfViewerHandle | DocxViewerHandle | PptViewerHandle | null>(null)

  // 图片专用状态（旋转/缩放）
  const [zoom, setZoom] = useState(100)
  const [rotation, setRotation] = useState(0)
  const [imgError, setImgError] = useState<string | null>(null)

  const onMouseMove = useCallback((e: MouseEvent) => {
    if (dragging.current) {
      setPos({
        x: Math.max(0, dragStart.current.px + (e.clientX - dragStart.current.x)),
        y: Math.max(0, dragStart.current.py + (e.clientY - dragStart.current.y)),
      })
    }
    if (resizing.current) {
      setSize({
        w: Math.max(300, dragStart.current.pw + (e.clientX - dragStart.current.x)),
        h: Math.max(300, dragStart.current.ph + (e.clientY - dragStart.current.y)),
      })
    }
  }, [])

  const onMouseUp = useCallback(() => {
    dragging.current = false; resizing.current = false
    document.body.style.userSelect = ''; document.body.style.cursor = ''
  }, [])

  useEffect(() => {
    window.addEventListener('mousemove', onMouseMove)
    window.addEventListener('mouseup', onMouseUp)
    return () => {
      window.removeEventListener('mousemove', onMouseMove)
      window.removeEventListener('mouseup', onMouseUp)
    }
  }, [onMouseMove, onMouseUp])

  const isPdf = PDF_EXTS.test(fileName)
  const isDocx = DOCX_EXTS.test(fileName)
  const isPpt = PPT_EXTS.test(fileName)
  const isXlsx = XLSX_EXTS.test(fileName)
  const isImage = IMAGE_EXTS.test(fileName)

  const rawUrl = `/api/document/raw?fileId=${encodeURIComponent(fileId)}`

  // 引用跳转：打开后自动跳到指定页
  useEffect(() => {
    if (initialPage > 1 && (isPdf || isDocx || isPpt)) {
      // 等组件挂载渲染完成后再跳（组件内部也会用 initialPage 定位）
      const t = setTimeout(() => {
        viewerRef.current?.jumpTo(initialPage)
      }, 600)
      return () => clearTimeout(t)
    }
  }, [initialPage, isPdf, isDocx, isPpt])

  const renderContent = () => {
    if (isPdf) return <PdfViewer ref={viewerRef as React.Ref<PdfViewerHandle>} url={rawUrl} fileName={fileName} initialPage={initialPage} />
    if (isDocx) return <DocxViewer ref={viewerRef as React.Ref<DocxViewerHandle>} url={rawUrl} fileName={fileName} initialPage={initialPage} />
    if (isPpt) return <PptViewer ref={viewerRef as React.Ref<PptViewerHandle>} url={rawUrl} fileName={fileName} initialPage={initialPage} />
    if (isXlsx) return <XlsxViewer url={rawUrl} fileName={fileName} />
    if (isImage) {
      return (
        <div style={{
          flex: 1, overflow: 'auto', background: '#525659',
          display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 12,
        }}>
          {imgError ? (
            <div style={{ padding: 16, borderRadius: 8, background: 'rgba(239,68,68,0.15)', color: '#f28b82', fontSize: 13 }}>{imgError}</div>
          ) : (
            <img
              src={rawUrl}
              alt={fileName}
              style={{
                width: `${zoom}%`, maxWidth: `${zoom}%`, height: 'auto',
                boxShadow: '0 2px 16px rgba(0,0,0,0.4)', borderRadius: 2, background: '#fff',
                transform: `rotate(${rotation}deg)`, transition: 'transform 200ms ease',
              }}
              onError={() => setImgError('图片加载失败')}
            />
          )}
        </div>
      )
    }
    // 未知类型：降级为文本预览
    return (
      <div style={{ flex: 1, overflow: 'auto', background: '#fff', padding: 20, fontSize: 13, color: '#333' }}>
        <iframe src={rawUrl} style={{ width: '100%', height: '100%', border: 'none' }} title={fileName} />
      </div>
    )
  }

  return (
    <div style={{
      position: 'fixed', top: pos.y, left: pos.x,
      width: size.w, height: size.h,
      background: 'var(--bg-primary)',
      border: '1px solid var(--border-color)',
      borderRadius: 10,
      boxShadow: '0 8px 40px rgba(0,0,0,0.35)',
      display: 'flex', flexDirection: 'column',
      zIndex: 1000, overflow: 'hidden',
    }}>
      {/* 标题栏 — 可拖拽 */}
      <div
        onMouseDown={e => {
          dragging.current = true
          dragStart.current = { x: e.clientX, y: e.clientY, px: pos.x, py: pos.y, pw: size.w, ph: size.h }
          document.body.style.userSelect = 'none'; document.body.style.cursor = 'move'
        }}
        style={{
          display: 'flex', alignItems: 'center', height: 36, minHeight: 36,
          padding: '0 8px 0 12px', gap: 6,
          background: 'var(--bg-secondary)',
          borderBottom: '1px solid var(--border-color)',
          cursor: 'move', userSelect: 'none',
          borderTopLeftRadius: 10, borderTopRightRadius: 10,
        }}>
        <GripHorizontal size={12} style={{ color: 'var(--text-muted)' }} />
        <span style={{ fontSize: 12, fontWeight: 500, color: 'var(--text-primary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1, minWidth: 0 }}>
          {fileName}
        </span>
        {isImage && (
          <>
            <button onClick={() => setZoom(z => Math.max(25, z - 25))} disabled={zoom <= 25} style={roundBtn}>−</button>
            <span style={{ fontSize: 10, color: 'var(--text-secondary)', minWidth: 32, textAlign: 'center' }}>{zoom}%</span>
            <button onClick={() => setZoom(z => Math.min(300, z + 25))} disabled={zoom >= 300} style={roundBtn}>+</button>
            <button onClick={() => setRotation(r => (r + 90) % 360)} style={{ ...roundBtn, ...(rotation !== 0 ? { color: 'var(--accent-blue)' } : {}) }} title="旋转 90°"><RotateCw size={13} /></button>
            <span style={{ width: 1, height: 16, background: 'var(--border-color)', flexShrink: 0 }} />
          </>
        )}
        <button onClick={onClose} style={roundBtn} title="关闭"><X size={13} /></button>
      </div>

      {/* 内容区 */}
      <Suspense fallback={<div style={{ padding: 20, color: 'var(--text-secondary)' }}>正在加载预览器…</div>}>
        {renderContent()}
      </Suspense>

      {/* 右下角拖拽尺寸 */}
      <div
        onMouseDown={e => {
          e.stopPropagation(); resizing.current = true
          dragStart.current = { x: e.clientX, y: e.clientY, px: pos.x, py: pos.y, pw: size.w, ph: size.h }
          document.body.style.userSelect = 'none'; document.body.style.cursor = 'nwse-resize'
        }}
        style={{ position: 'absolute', bottom: 0, right: 0, width: 20, height: 20, cursor: 'nwse-resize', background: 'linear-gradient(135deg, transparent 50%, var(--text-muted) 50%, transparent 55%)', borderBottomRightRadius: 10 }}
      />
    </div>
  )
}

const roundBtn: React.CSSProperties = {
  width: 26, height: 26, borderRadius: 13, border: 'none', flexShrink: 0,
  background: 'none', color: 'var(--text-secondary)', cursor: 'pointer',
  display: 'flex', alignItems: 'center', justifyContent: 'center',
}
