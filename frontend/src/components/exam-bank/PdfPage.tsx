import { useEffect, useRef, useState } from 'react'
import * as pdfjs from 'pdfjs-dist'
import worker from 'pdfjs-dist/build/pdf.worker.js?url'
import 'pdfjs-dist/web/pdf_viewer.css'

pdfjs.GlobalWorkerOptions.workerSrc = worker

/** One page at a time keeps CPU/memory bounded and gives selection a stable page anchor. */
export default function PdfPage({ url, page, onPages }: { url: string; page: number; onPages: (n: number) => void }) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const layer = useRef<HTMLDivElement>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    let cancelled = false
    let rendering: ReturnType<pdfjs.PDFPageProxy['render']> | undefined
    const loading = pdfjs.getDocument({ url, withCredentials: true })
    setError('')
    async function render() {
      try {
        const doc = await loading.promise
        if (cancelled) return
        onPages(doc.numPages)
        const pdfPage = await doc.getPage(Math.min(page, doc.numPages))
        if (cancelled || !canvas.current || !layer.current) return
        const viewport = pdfPage.getViewport({ scale: 1.3 })
        const ctx = canvas.current.getContext('2d')!
        canvas.current.width = viewport.width
        canvas.current.height = viewport.height
        layer.current.replaceChildren()
        layer.current.style.width = `${viewport.width}px`
        layer.current.style.height = `${viewport.height}px`
        layer.current.style.setProperty('--scale-factor', '1.3')
        rendering = pdfPage.render({ canvasContext: ctx, viewport })
        await rendering.promise
        if (cancelled) return
        await pdfjs.renderTextLayer({ textContentSource: await pdfPage.getTextContent(), container: layer.current, viewport }).promise
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : 'PDF 加载失败')
      }
    }
    void render()
    return () => { cancelled = true; rendering?.cancel(); void loading.destroy() }
  }, [url, page, onPages])
  return error ? <p role="alert" className="workspace-error">{error}</p> : (
    <div className="pdf-sheet">
      <canvas ref={canvas} />
      <div ref={layer} className="textLayer" />
    </div>
  )
}
