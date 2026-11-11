import { useState, useEffect, useRef } from 'react'
import { Upload, RefreshCw, Trash2, FileText, Loader, Search, RotateCcw } from 'lucide-react'

export default function AdminFiles() {
  const [files, setFiles] = useState<any[]>([])
  const [page, setPage] = useState(0)
  const [total, setTotal] = useState(0)
  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [msg, setMsg] = useState('')
  const [uploading, setUploading] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const token = localStorage.getItem('admin-token') || ''

  const fetchFiles = () => {
    fetch(`/api/admin/files?page=${page}&size=30&search=${search}&status=${statusFilter}`, {
      headers: { 'X-Admin-Token': token },
    }).then(r => r.json()).then(d => {
      setFiles(d.items || [])
      setTotal(d.total || 0)
    }).catch(() => {})
  }

  useEffect(() => { fetchFiles() }, [page, search, statusFilter])

  const handleUpload = async (file: File) => {
    setUploading(true); setMsg('')
    const fd = new FormData(); fd.append('file', file)
    try {
      const r = await fetch('/api/admin/files/upload', { method: 'POST', headers: { 'X-Admin-Token': token }, body: fd })
      const d = await r.json()
      setMsg(d.message || d.error || '已上传')
      fetchFiles()
    } catch { setMsg('上传失败') }
    finally { setUploading(false) }
  }

  const action = async (url: string, method: string, id?: number) => {
    const r = await fetch(url, {
      method,
      headers: { 'Content-Type': 'application/json', 'X-Admin-Token': token },
      body: method !== 'DELETE' ? JSON.stringify({ id }) : undefined,
    })
    const d = await r.json()
    setMsg(d.message || d.error || '')
    fetchFiles()
  }

  const sizeLabel = (bytes: number) => {
    if (bytes >= 1048576) return `${(bytes / 1048576).toFixed(1)} MB`
    if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
    return `${bytes} B`
  }

  const statusBadge = (status: string) => {
    const map: Record<string, { bg: string; color: string; label: string }> = {
      completed:  { bg: 'var(--accent-green-light, #dcfce7)', color: 'var(--accent-green, #16a34a)', label: '已完成' },
      processing: { bg: 'var(--accent-yellow-light, #fef9c3)', color: 'var(--accent-yellow, #ca8a04)', label: '解析中' },
      failed:     { bg: 'var(--accent-red-light, #fef2f2)', color: 'var(--accent-red, #dc2626)', label: '失败' },
      pending:    { bg: 'var(--bg-tertiary)', color: 'var(--text-muted)', label: '待解析' },
    }
    const s = map[status] || map.pending
    return <span style={{ padding: '2px 8px', borderRadius: 10, fontSize: 11, background: s.bg, color: s.color }}>{s.label}</span>
  }

  const td: React.CSSProperties = {
    padding: '8px 12px', fontSize: 12.5, borderBottom: '1px solid var(--border-color)',
    color: 'var(--text-primary)',
  }

  const btn = (bg?: string, color?: string): React.CSSProperties => ({
    height: 28, padding: '0 10px', borderRadius: 5, border: 'none',
    cursor: 'pointer', fontSize: 11.5, fontFamily: 'inherit',
    display: 'inline-flex', alignItems: 'center', gap: 4,
    background: bg || 'var(--bg-secondary)', color: color || 'var(--text-primary)',
  })

  return (
    <div style={{ padding: '28px 32px', maxWidth: 1300 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 }}>
        <div>
          <h1 style={{ fontSize: 20, fontWeight: 600, color: 'var(--text-primary)', margin: '0 0 4px' }}>
            题库管理
          </h1>
          <p style={{ fontSize: 12, color: 'var(--text-muted)', margin: 0 }}>总计 {total} 个文件</p>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <button onClick={() => action('/api/admin/files/scan', 'POST')}
            style={{ ...btn(), border: '1px solid var(--border-color)' }}>
            <RefreshCw size={13} />重新扫描
          </button>
          <input ref={fileInputRef} type="file"
            accept=".pdf,.docx,.doc,.pptx,.ppt,.txt,.md,.png,.jpg,.jpeg,.bmp,.tiff"
            style={{ display: 'none' }}
            onChange={e => { const f = e.target.files?.[0]; if (f) handleUpload(f); e.target.value = '' }} />
          <button onClick={() => fileInputRef.current?.click()} disabled={uploading}
            style={{ ...btn('var(--accent-blue)', '#fff'), opacity: uploading ? 0.6 : 1 }}>
            {uploading ? <Loader size={13} style={{ animation: 'spin 1s linear infinite' }} /> : <Upload size={13} />}
            上传文件
          </button>
        </div>
      </div>

      {msg && (
        <div style={{ padding: '8px 14px', borderRadius: 8, marginBottom: 16, fontSize: 12,
          background: msg.includes('失败') ? 'var(--accent-red-light, #fef2f2)' : 'var(--accent-green-light, #f0fdf4)',
          color: msg.includes('失败') ? 'var(--accent-red)' : 'var(--accent-green)',
        }}>{msg}</div>
      )}

      <div style={{ marginBottom: 16, display: 'flex', gap: 8 }}>
        <div style={{ position: 'relative', flex: 1, maxWidth: 300 }}>
          <Search size={14} style={{ position: 'absolute', left: 10, top: 10, color: 'var(--text-muted)' }} />
          <input value={search} onChange={e => { setSearch(e.target.value); setPage(0) }}
            placeholder="搜索文件名或路径..."
            style={{ width: '100%', height: 36, paddingLeft: 32, paddingRight: 12, borderRadius: 8, border: '1px solid var(--border-color)', background: 'var(--bg-secondary)', color: 'var(--text-primary)', fontSize: 13, outline: 'none', fontFamily: 'inherit', boxSizing: 'border-box' }} />
        </div>
        <select value={statusFilter} onChange={e => { setStatusFilter(e.target.value); setPage(0) }}
          style={{ height: 36, padding: '0 10px', borderRadius: 8, border: '1px solid var(--border-color)', background: 'var(--bg-secondary)', color: 'var(--text-primary)', fontSize: 12, fontFamily: 'inherit', outline: 'none' }}>
          <option value="">全部状态</option>
          <option value="completed">已完成</option>
          <option value="processing">解析中</option>
          <option value="failed">失败</option>
          <option value="pending">待解析</option>
        </select>
      </div>

      <div style={{ borderRadius: 12, overflow: 'hidden', border: '1px solid var(--border-color)', background: 'var(--bg-secondary)' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'fixed' }}>
          <thead>
            <tr>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'left' }}>文件名</th>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'left', width: 70 }}>类型</th>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'right', width: 80 }}>大小</th>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'center', width: 80 }}>状态</th>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'left', width: 140 }}>时间</th>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'right', width: 120 }}>操作</th>
            </tr>
          </thead>
          <tbody>
            {files.map(f => (
              <tr key={f.id}>
                <td style={{ ...td, display: 'flex', alignItems: 'center', gap: 6, overflow: 'hidden' }}>
                  <FileText size={14} style={{ flexShrink: 0, color: 'var(--text-muted)' }} />
                  <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.fileName}</span>
                  {f.totalPages > 0 && <span style={{ fontSize: 10, color: 'var(--text-muted)', flexShrink: 0 }}>{f.totalPages}页</span>}
                </td>
                <td style={{ ...td, color: 'var(--text-muted)', fontSize: 11, textTransform: 'uppercase' }}>{f.fileType}</td>
                <td style={{ ...td, textAlign: 'right', fontSize: 11.5, color: 'var(--text-muted)' }}>{sizeLabel(f.fileSize)}</td>
                <td style={{ ...td, textAlign: 'center' }}>{statusBadge(f.parseStatus)}</td>
                <td style={{ ...td, color: 'var(--text-muted)', fontSize: 11.5 }}>
                  {f.createdAt ? f.createdAt.substring(0, 16).replace('T', ' ') : '-'}
                </td>
                <td style={{ ...td, textAlign: 'right' }}>
                  {f.parseStatus === 'failed' && (
                    <button onClick={() => action('/api/admin/files/reparse', 'POST', f.id)}
                      style={{ ...btn(), border: '1px solid var(--border-color)' }}>
                      <RotateCcw size={11} />重试
                    </button>
                  )}
                  <button onClick={() => { if (confirm(`确认删除 ${f.fileName}？`)) action('/api/admin/files/delete', 'POST', f.id) }}
                    style={{ ...btn(), color: 'var(--accent-red)', marginLeft: 4 }}>
                    <Trash2 size={11} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div style={{ display: 'flex', justifyContent: 'center', gap: 8, marginTop: 16 }}>
        <button disabled={page === 0} onClick={() => setPage(p => p - 1)} style={{ ...btn(), border: '1px solid var(--border-color)', opacity: page === 0 ? 0.4 : 1 }}>上一页</button>
        <span style={{ fontSize: 12, color: 'var(--text-muted)', lineHeight: '28px' }}>{page + 1} / {Math.ceil(total / 30) || 1}</span>
        <button disabled={(page + 1) * 30 >= total} onClick={() => setPage(p => p + 1)} style={{ ...btn(), border: '1px solid var(--border-color)', opacity: (page + 1) * 30 >= total ? 0.4 : 1 }}>下一页</button>
      </div>
    </div>
  )
}
