import { useState, useRef, useCallback, useEffect } from 'react'
import type { FileTreeNode } from '../../types'
import {
  Folder, File as FileIcon, FileText, Image,
  AlertCircle, Loader, ChevronRight, ChevronDown,
  Upload, X, CheckSquare, Square,
} from 'lucide-react'
import { validateFile, buildAuthHeaders } from '../../lib/security'
import { useAppContext } from '../../hooks/useAppContext'

const API_BASE = '/api'

interface Props {
  onFileSelect?: (fileId: number, fileName: string) => void
}

export default function SourcesPanel({ onFileSelect }: Props) {
  const { selectedFileIds, toggleFile, setSelectedFileIds } = useAppContext()
  const [tree, setTree] = useState<FileTreeNode[]>([])
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [dragOver, setDragOver] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [uploadProgress, setUploadProgress] = useState(0)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const fetchTree = useCallback(() => {
    const token = localStorage.getItem('student-token')
    fetch(`${API_BASE}/document/tree`, { headers: token ? { 'X-Student-Token': token } : undefined })
      .then(r => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json() })
      .then(setTree)
      .catch(() => {})
  }, [])

  useEffect(() => { fetchTree() }, [fetchTree])

  const toggle = (path: string) => {
    setExpanded(prev => { const next = new Set(prev); next.has(path) ? next.delete(path) : next.add(path); return next })
  }

  const uploadFile = useCallback(async (file: File) => {
    const validation = validateFile(file)
    if (!validation.valid) { setUploadError(validation.reason || '文件校验失败'); return }
    setUploading(true); setUploadProgress(0); setUploadError(null)
    try {
      const formData = new FormData(); formData.append('file', file)
      const xhr = new XMLHttpRequest()
      xhr.upload.onprogress = (e) => { if (e.lengthComputable) setUploadProgress(Math.round((e.loaded / e.total) * 100)) }
      await new Promise<void>((resolve, reject) => {
        xhr.open('POST', `${API_BASE}/document/upload`); xhr.setRequestHeader('X-Client-Version', '1.0')
        xhr.onload = () => { if (xhr.status >= 200 && xhr.status < 300) resolve(); else reject(new Error(`上传失败 (${xhr.status})`)) }
        xhr.onerror = () => reject(new Error('网络错误')); xhr.send(formData)
      })
      setUploadProgress(100)
      await fetch(`${API_BASE}/document/scan`, { method: 'POST', headers: buildAuthHeaders(), body: JSON.stringify({ forceReParse: false }) })
      fetchTree()
    } catch (err: any) { setUploadError(err.message || '上传失败') }
    finally { setUploading(false) }
  }, [fetchTree])

  const handleDrop = useCallback((e: React.DragEvent) => { e.preventDefault(); setDragOver(false); const files = Array.from(e.dataTransfer.files); if (files.length) uploadFile(files[0]) }, [uploadFile])
  const handleFileSelect = useCallback((e: React.ChangeEvent<HTMLInputElement>) => { const files = e.target.files; if (files?.length) { uploadFile(files[0]); e.target.value = '' } }, [uploadFile])

  const statusDot = (status?: string) => {
    const s: React.CSSProperties = { width: 6, height: 6, borderRadius: 3, flexShrink: 0 }
    switch (status) {
      case 'completed': return <span title="已解析"><div style={{ ...s, background: '#34a853' }} /></span>
      case 'processing': return <Loader size={10} style={{ color: '#f9ab00', flexShrink: 0 }} className="animate-spin" />
      case 'failed': return <span title="解析失败"><AlertCircle size={10} style={{ color: '#ea4335', flexShrink: 0 }} /></span>
      default: return <span title="待解析"><div style={{ ...s, background: 'var(--text-muted)' }} /></span>
    }
  }

  const fileIcon = (name: string) => {
    if (name.endsWith('.pdf')) return <FileText size={14} style={{ flexShrink: 0 }} />
    if (/\.(png|jpg|jpeg|gif|bmp)$/i.test(name)) return <Image size={14} style={{ flexShrink: 0 }} />
    if (/\.(doc|docx)$/i.test(name)) return <FileText size={14} style={{ flexShrink: 0, color: 'var(--accent-blue)' }} />
    if (/\.(ppt|pptx)$/i.test(name)) return <FileText size={14} style={{ flexShrink: 0, color: 'var(--accent-red)' }} />
    return <FileIcon size={14} style={{ flexShrink: 0 }} />
  }

  // 收集稳定文件 ID 用于全选；路径只用于预览，不作为授权选择器。
  const allFileIds = useCallback((nodes: FileTreeNode[]): number[] => {
    const result: number[] = []
    for (const n of nodes) {
      if (n.type === 'file' && n.fileId != null) result.push(n.fileId)
      if (n.children) result.push(...allFileIds(n.children))
    }
    return result
  }, [])

  const selectableIds = allFileIds(tree)
  const allSelected = selectableIds.length > 0 && selectableIds.every(id => selectedFileIds.has(id))

  const toggleAll = () => {
    if (allSelected) { setSelectedFileIds(new Set()) }
    else { setSelectedFileIds(new Set(selectableIds)) }
  }

  const renderNode = (node: FileTreeNode, depth: number = 0): React.ReactNode => {
    if (node.type === 'directory') {
      const open = expanded.has(node.path)
      return (
        <div key={node.path}>
          <div onClick={() => toggle(node.path)}
            style={{ display: 'flex', alignItems: 'center', gap: 6, height: 32, cursor: 'pointer', paddingLeft: 8 + depth * 16, paddingRight: 8, color: 'var(--text-secondary)', fontSize: 13, fontWeight: 500 }}
            onMouseEnter={e => { e.currentTarget.style.background = 'var(--bg-hover)' }}
            onMouseLeave={e => { e.currentTarget.style.background = 'none' }}>
            {open ? <ChevronDown size={14} style={{ flexShrink: 0 }} /> : <ChevronRight size={14} style={{ flexShrink: 0 }} />}
            <Folder size={14} style={{ flexShrink: 0, color: 'var(--text-muted)' }} />
            <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{node.name}</span>
          </div>
          {open && (node.children || []).map((c: FileTreeNode) => renderNode(c, depth + 1))}
        </div>
      )
    }

    const isSelected = node.fileId != null && selectedFileIds.has(node.fileId)
    return (
      <div key={node.path}
        style={{
          display: 'flex', alignItems: 'center', gap: 6, height: 32, cursor: 'pointer',
          paddingLeft: 8 + depth * 16 + 20, paddingRight: 8, fontSize: 13,
          color: isSelected ? 'var(--accent-green)' : 'var(--text-secondary)',
          background: isSelected ? 'var(--accent-green-light)' : 'none',
        }}
        onMouseEnter={e => { if (!isSelected) e.currentTarget.style.background = 'var(--bg-hover)' }}
        onMouseLeave={e => { if (!isSelected) e.currentTarget.style.background = isSelected ? 'var(--accent-green-light)' : 'none' }}>
        {/* 勾选框 */}
        <span onClick={e => { e.stopPropagation(); if (node.fileId != null) toggleFile(node.fileId) }}>
          {isSelected
            ? <CheckSquare size={14} style={{ flexShrink: 0, color: 'var(--accent-green)' }} />
            : <Square size={14} style={{ flexShrink: 0, color: 'var(--text-muted)' }} />}
        </span>
        <span onClick={() => { if (onFileSelect && node.fileId != null) onFileSelect(node.fileId, node.name) }}
          style={{ display: 'flex', alignItems: 'center', gap: 6, flex: 1, overflow: 'hidden' }}>
          {fileIcon(node.name)}
          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{node.name}</span>
        </span>
        {statusDot(node.status)}
      </div>
    )
  }

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      {/* Header */}
      <div style={{ padding: '16px 16px 12px', borderBottom: '1px solid var(--border-color)' }}>
        <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text-primary)' }}>题库来源</div>
        {selectableIds.length > 0 && (
          <div onClick={toggleAll}
            style={{ display: 'flex', alignItems: 'center', gap: 4, cursor: 'pointer', fontSize: 11, color: 'var(--text-muted)', marginTop: 4 }}>
            {allSelected ? <CheckSquare size={12} style={{ color: 'var(--accent-green)' }} /> : <Square size={12} />}
            全选 ({selectableIds.length})
          </div>
        )}
      </div>

      {/* Upload zone */}
      <div onDragOver={e => { e.preventDefault(); setDragOver(true) }} onDragLeave={e => { e.preventDefault(); setDragOver(false) }}
        onDrop={handleDrop} onClick={() => fileInputRef.current?.click()}
        style={{ margin: '8px 12px', padding: '16px 12px', border: `2px dashed ${dragOver ? 'var(--accent-blue)' : 'var(--border-color)'}`, borderRadius: 8, background: dragOver ? 'var(--accent-blue-light)' : 'var(--bg-tertiary)', cursor: 'pointer', textAlign: 'center', transition: 'border-color 150ms, background 150ms' }}>
        <input ref={fileInputRef} type="file" accept=".pdf,.docx,.doc,.pptx,.ppt,.txt,.md,.png,.jpg,.jpeg,.bmp,.tiff" style={{ display: 'none' }} onChange={handleFileSelect} />
        {uploading ? (
          <div>
            <Loader size={20} className="animate-spin" style={{ color: 'var(--accent-blue)', margin: '0 auto 8px' }} />
            <div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>上传中 {uploadProgress}%</div>
            <div style={{ height: 3, background: 'var(--border-color)', borderRadius: 2, marginTop: 6, overflow: 'hidden' }}>
              <div style={{ height: '100%', width: `${uploadProgress}%`, background: 'var(--accent-blue)', transition: 'width 200ms' }} />
            </div>
          </div>
        ) : (
          <div>
            <Upload size={20} style={{ color: 'var(--text-muted)', margin: '0 auto 6px' }} />
            <div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>拖拽或点击上传题库文件</div>
            <div style={{ fontSize: 10, color: 'var(--text-muted)', marginTop: 2 }}>支持 PDF / DOCX / PPTX / TXT / 图片</div>
          </div>
        )}
        {uploadError && (
          <div style={{ marginTop: 8, padding: '6px 10px', background: 'var(--accent-red-light)', borderRadius: 6, fontSize: 11, color: 'var(--accent-red)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <span>{uploadError}</span>
            <button onClick={e => { e.stopPropagation(); setUploadError(null) }} style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 0, color: 'var(--accent-red)' }}><X size={12} /></button>
          </div>
        )}
      </div>

      {/* File tree */}
      <div style={{ flex: 1, overflow: 'auto', padding: '4px 0' }}>
        {tree.length === 0 ? (
          <div style={{ padding: '24px 16px', color: 'var(--text-muted)', fontSize: 12, textAlign: 'center', lineHeight: 1.6 }}>
            <div style={{ marginBottom: 8 }}>暂无题库文件</div>
            <div>拖拽文件到此处或点击上传</div>
          </div>
        ) : tree.map(node => renderNode(node))}
      </div>
    </div>
  )
}
