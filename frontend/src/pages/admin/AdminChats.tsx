import { useState, useEffect } from 'react'
import { Trash2, Download, RefreshCw } from 'lucide-react'

export default function AdminChats() {
  const [chats, setChats] = useState<any[]>([])
  const [page, setPage] = useState(0)
  const [total, setTotal] = useState(0)
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const token = localStorage.getItem('admin-token') || ''

  const fetchChats = () => {
    fetch(`/api/admin/chat-logs?page=${page}&size=30`, { headers: { 'X-Admin-Token': token } })
      .then(r => r.json()).then(d => {
        setChats(d.items || [])
        setTotal(d.total || 0)
      }).catch(() => {})
  }

  useEffect(() => { fetchChats() }, [page])

  const batchDelete = async () => {
    if (selected.size === 0 || !confirm(`确认删除 ${selected.size} 条记录？`)) return
    await fetch('/api/admin/data/batch/chats', {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json', 'X-Admin-Token': token },
      body: JSON.stringify({ ids: Array.from(selected) }),
    }).catch(() => {})
    setSelected(new Set())
    fetchChats()
  }

  const toggleSelect = (id: number) => {
    setSelected(prev => {
      const next = new Set(prev)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })
  }

  const toggleAll = () => {
    if (selected.size === chats.length) { setSelected(new Set()); return }
    setSelected(new Set(chats.map(c => c.id)))
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
            对话记录
          </h1>
          <p style={{ fontSize: 12, color: 'var(--text-muted)', margin: 0 }}>总计 {total} 条</p>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <a href={`/api/admin/data/export/chats?token=${encodeURIComponent(token)}`}
            style={{ ...btn(), border: '1px solid var(--border-color)', textDecoration: 'none' }}>
            <Download size={13} />导出 CSV
          </a>
          {selected.size > 0 && (
            <button onClick={batchDelete}
              style={btn('var(--accent-red-light, #fef2f2)', 'var(--accent-red)')}>
              <Trash2 size={13} />删除选中 ({selected.size})
            </button>
          )}
          <button onClick={fetchChats}
            style={{ ...btn(), border: '1px solid var(--border-color)' }}>
            <RefreshCw size={13} />
          </button>
        </div>
      </div>

      <div style={{
        borderRadius: 12, overflow: 'hidden',
        border: '1px solid var(--border-color)', background: 'var(--bg-secondary)',
      }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'fixed' }}>
          <thead>
            <tr>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'left', width: 40 }}>
                <input type="checkbox" checked={selected.size === chats.length && chats.length > 0}
                  onChange={toggleAll} style={{ accentColor: 'var(--accent-blue)' }} />
              </th>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'left', width: 100 }}>会话 ID</th>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'center', width: 80 }}>角色</th>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'left' }}>内容</th>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'left', width: 150 }}>时间</th>
            </tr>
          </thead>
          <tbody>
            {chats.map(c => (
              <tr key={c.id}>
                <td style={{ ...td, textAlign: 'left' }}>
                  <input type="checkbox" checked={selected.has(c.id)}
                    onChange={() => toggleSelect(c.id)} style={{ accentColor: 'var(--accent-blue)' }} />
                </td>
                <td style={{ ...td, fontFamily: 'monospace', fontSize: 11, color: 'var(--text-muted)' }}>
                  {c.sessionId ? String(c.sessionId).substring(0, 12) + '...' : '-'}
                </td>
                <td style={{ ...td, textAlign: 'center' }}>
                  <span style={{
                    padding: '2px 6px', borderRadius: 8, fontSize: 10.5,
                    background: c.role === 'user' ? 'var(--accent-blue-light, #dbeafe)' : 'var(--accent-green-light, #dcfce7)',
                    color: c.role === 'user' ? 'var(--accent-blue)' : 'var(--accent-green)',
                  }}>
                    {c.role === 'user' ? '用户' : 'AI'}
                  </span>
                </td>
                <td style={{ ...td, overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 0 }}>
                  <span style={{ fontSize: 12, lineHeight: 1.5 }}>
                    {c.content ? String(c.content).substring(0, 100) : '-'}
                  </span>
                </td>
                <td style={{ ...td, color: 'var(--text-muted)', fontSize: 11.5 }}>
                  {c.createdAt ? c.createdAt.substring(0, 19).replace('T', ' ') : '-'}
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
