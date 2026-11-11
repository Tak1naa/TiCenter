import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react'
import { ChevronLeft, ChevronRight, Scan, TextSelect, MessageSquarePlus, Plus, FileText } from 'lucide-react'
import type { ContextAttachment, Reference, WorkspaceDocument } from '../../types'

const PdfPage = lazy(() => import('./PdfPage'))
type Box = [number, number, number, number]

export default function DocumentWorkspace({ document: doc, target, onAttach }: {
  document: WorkspaceDocument
  target?: Reference
  onAttach: (ref: ContextAttachment, focus: boolean) => void
}) {
  const [metadata, setMetadata] = useState<WorkspaceDocument>(doc)
  const [page, setPage] = useState(target?.page || 1)
  const [total, setTotal] = useState(1)
  const [parsed, setParsed] = useState('')
  const [mode, setMode] = useState<'original' | 'text'>('original')
  const [boxMode, setBoxMode] = useState(false)
  const [selection, setSelection] = useState<{ quote?: string; bbox?: Box }>()
  const [error, setError] = useState('')
  const [ready, setReady] = useState(false)
  const [imageError, setImageError] = useState(false)
  const sheet = useRef<HTMLDivElement>(null)
  const drag = useRef<[number, number] | null>(null)
  const onPages = useCallback((n: number) => setTotal(n), [])
  const isPdf = /\.pdf$/i.test(doc.fileName)
  const isText = /\.(txt|md)$/i.test(doc.fileName)
  const versionChanged = !!target?.generationId && !!metadata.generationId && target.generationId !== metadata.generationId

  useEffect(() => {
    const abort = new AbortController()
    setReady(false); setError('')
    fetch(`/api/document/${doc.fileId}/workspace`, { signal: abort.signal })
      .then(async r => { if (!r.ok) throw new Error('文件不可用，请刷新文件库'); return r.json() })
      .then(data => { setMetadata(data); setTotal(Math.max(1, data.totalPages || 1)); setReady(true) })
      .catch(e => { if (e.name !== 'AbortError') setError(e.message) })
    return () => abort.abort()
  }, [doc.fileId, target])

  useEffect(() => {
    setPage(target?.page || 1); setSelection(target?.bbox ? { bbox: target.bbox } : undefined)
  }, [doc.fileId, target])

  useEffect(() => {
    setParsed(''); setSelection(undefined); setImageError(false)
    if (!ready || versionChanged) return
    const abort = new AbortController()
    fetch(`/api/document/preview?fileId=${doc.fileId}&page=${page}&generationId=${encodeURIComponent(metadata.generationId || '')}`, { signal: abort.signal })
      .then(async r => { if (!r.ok) throw new Error('页面解析暂不可用'); return r.json() })
      .then(data => setParsed(data.text || ''))
      .catch(e => { if (e.name !== 'AbortError') setError(e.message) })
    return () => abort.abort()
  }, [doc.fileId, page, ready, versionChanged])

  const attach = (focus: boolean) => {
    if (!metadata.generationId || versionChanged) return
    onAttach({ fileId: doc.fileId, fileName: doc.fileName, generationId: metadata.generationId,
      documentVersion: metadata.documentVersion, page,
      kind: selection?.quote ? 'selection' : selection?.bbox ? 'region' : 'page',
      quote: selection?.quote, bbox: selection?.bbox,
    }, focus)
  }
  const captureText = () => {
    if (boxMode) return
    const selected = window.getSelection()
    const quote = selected?.toString().trim()
    if (quote && selected?.anchorNode && selected.focusNode && sheet.current?.contains(selected.anchorNode)
        && sheet.current.contains(selected.focusNode)) {
      if (quote.length > 4000) { setError('选区过长，请缩小到 4000 字以内'); return }
      setSelection({ quote })
    }
  }
  const point = (e: React.PointerEvent) => {
    const rect = sheet.current!.getBoundingClientRect()
    return [Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width)),
      Math.max(0, Math.min(1, (e.clientY - rect.top) / rect.height))] as [number, number]
  }
  const box = selection?.bbox || (!versionChanged ? target?.bbox : undefined)
  const generation = encodeURIComponent(metadata.generationId || '')
  const originalUrl = `/api/document/raw?fileId=${doc.fileId}&generationId=${generation}`
  const imageUrl = `/api/document/page-image?fileId=${doc.fileId}&page=${page}&generationId=${generation}`
  return <section className="document-workspace" aria-label={`文档 ${doc.fileName}`}>
    <div className="document-toolbar">
      <div className="page-navigation">
        <button aria-label="上一页" disabled={page <= 1} onClick={() => { setPage(p => p - 1); setSelection(undefined) }}><ChevronLeft size={16} /></button>
        <label>第 <input aria-label="页码" type="number" min={1} max={total} value={page} onChange={e => { const n = Number(e.target.value); if (n >= 1 && n <= total) setPage(n) }} /> / {total} 页</label>
        <button aria-label="下一页" disabled={page >= total} onClick={() => { setPage(p => p + 1); setSelection(undefined) }}><ChevronRight size={16} /></button>
      </div>
      <div className="document-tools">
        <button aria-pressed={mode === 'text'} onClick={() => { setMode(m => m === 'text' ? 'original' : 'text'); setSelection(undefined) }}><TextSelect size={15} />解析文本</button>
        <button aria-pressed={boxMode} disabled={mode === 'text' || isText} onClick={() => { setBoxMode(v => !v); setSelection(undefined) }}><Scan size={15} />框选</button>
      </div>
    </div>
    {versionChanged ? <div className="workspace-error" role="alert">
      此引用来自旧版本，文件已更新。旧引用不会跳到新版内容。
      <button onClick={() => window.dispatchEvent(new CustomEvent('workspace:open-current', { detail: doc }))}>打开当前版本</button>
    </div> : error ? <p className="workspace-error" role="alert">{error}</p> : null}
    {!versionChanged && <div className="document-scroll">
      {!ready ? <p className="workspace-status">正在读取文件信息…</p> : <div ref={sheet}
        className={`document-sheet ${boxMode ? 'is-box-selecting' : ''}`}
        onMouseUp={captureText} onKeyUp={captureText}
        onPointerDown={e => { if (boxMode) { drag.current = point(e); e.currentTarget.setPointerCapture(e.pointerId); e.preventDefault() } }}
        onPointerMove={e => { if (!drag.current) return; const end = point(e); setSelection({ bbox: [Math.min(drag.current[0], end[0]), Math.min(drag.current[1], end[1]), Math.max(drag.current[0], end[0]), Math.max(drag.current[1], end[1])] }) }}
        onPointerUp={() => { if (drag.current && (!selection?.bbox || selection.bbox[2] - selection.bbox[0] < .01 || selection.bbox[3] - selection.bbox[1] < .01)) setSelection(undefined); drag.current = null }}
        onPointerCancel={() => { drag.current = null; setSelection(undefined) }}>
        {mode === 'text' || isText ? <pre className="parsed-page">{parsed || '该页尚无解析文本。可等待解析完成后再添加上下文。'}</pre>
          : isPdf ? <Suspense fallback={<p className="workspace-status">正在加载 PDF…</p>}><PdfPage url={originalUrl} page={page} onPages={onPages} /></Suspense>
          : imageError ? <p role="alert" className="workspace-error">页面转换失败。可切换解析文本，或由管理员重试解析。</p>
          : <img className="document-page-image" src={imageUrl} alt={`${doc.fileName} 第 ${page} 页`} onError={() => setImageError(true)} draggable={false} />}
        {box && <div className="selection-box" style={{ left: `${box[0] * 100}%`, top: `${box[1] * 100}%`, width: `${(box[2] - box[0]) * 100}%`, height: `${(box[3] - box[1]) * 100}%` }} />}
      </div>}
    </div>}
    <div className="selection-toolbar">
      <span><FileText size={14} />{selection?.quote ? `已选 ${selection.quote.length} 字` : selection?.bbox ? '已框选 · 文本按整页提供' : '当前页面'}</span>
      <button disabled={!ready || !metadata.generationId || versionChanged} onClick={() => attach(false)}><Plus size={15} />添加到对话</button>
      <button className="workspace-primary" disabled={!ready || !metadata.generationId || versionChanged} onClick={() => attach(true)}><MessageSquarePlus size={15} />在侧边聊天中提问</button>
    </div>
  </section>
}
