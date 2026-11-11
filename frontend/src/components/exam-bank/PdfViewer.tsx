import { forwardRef, useImperativeHandle, useRef } from 'react'
import { Viewer } from '@react-pdf-viewer/core'
import { pageNavigationPlugin } from '@react-pdf-viewer/page-navigation'
import { zoomPlugin } from '@react-pdf-viewer/zoom'
import { rotatePlugin } from '@react-pdf-viewer/rotate'
import '@react-pdf-viewer/core/lib/styles/index.css'
import '@react-pdf-viewer/page-navigation/lib/styles/index.css'
import '@react-pdf-viewer/zoom/lib/styles/index.css'

export interface PdfViewerHandle {
  jumpTo: (page: number) => void // 1-based
}

interface Props {
  url: string
  fileName: string
  initialPage?: number // 1-based
}

// pdfjs worker（pdfjs 3.x）
import workerSrc from 'pdfjs-dist/build/pdf.worker.js?url'
import * as pdfjsLib from 'pdfjs-dist'

// 必须设置 worker，否则 PDF 渲染白屏
pdfjsLib.GlobalWorkerOptions.workerSrc = workerSrc

const PdfViewer = forwardRef<PdfViewerHandle, Props>(({ url, fileName, initialPage }, ref) => {
  const pageNav = pageNavigationPlugin()
  const zoom = zoomPlugin()
  const rotate = rotatePlugin()

  const pageNavRef = useRef(pageNav)
  pageNavRef.current = pageNav

  useImperativeHandle(ref, () => ({
    jumpTo: (page: number) => pageNavRef.current.jumpToPage(Math.max(0, page - 1)),
  }), [])

  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0, background: '#525659' }}>
      {/* 工具栏：用插件渲染函数，避免依赖 defaultLayout */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8, padding: '4px 10px',
        background: 'var(--bg-secondary)', borderBottom: '1px solid var(--border-color)', flexShrink: 0, fontSize: 12,
      }}>
        {pageNav.GoToPreviousPageButton && <pageNav.GoToPreviousPageButton />}
        {pageNav.CurrentPageLabel && <pageNav.CurrentPageLabel />}
        {pageNav.GoToNextPageButton && <pageNav.GoToNextPageButton />}
        <span style={{ width: 1, height: 16, background: 'var(--border-color)' }} />
        {zoom.ZoomOutButton && <zoom.ZoomOutButton />}
        {zoom.ZoomPopover && <zoom.ZoomPopover />}
        {zoom.ZoomInButton && <zoom.ZoomInButton />}
        <span style={{ width: 1, height: 16, background: 'var(--border-color)' }} />
        {rotate.RotateBackwardButton && <rotate.RotateBackwardButton />}
        {rotate.RotateForwardButton && <rotate.RotateForwardButton />}
        <span style={{ flex: 1 }} />
        <span style={{ fontSize: 11, color: 'var(--text-muted)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 200 }}>{fileName}</span>
      </div>

      <div style={{ flex: 1, minHeight: 0 }}>
        <Viewer
          fileUrl={url}
          initialPage={initialPage ? initialPage - 1 : 0}
          defaultScale={1.2}
          plugins={[pageNav, zoom, rotate]}
        />
      </div>
    </div>
  )
})

PdfViewer.displayName = 'PdfViewer'

export default PdfViewer
