import { useState, useEffect } from 'react'
import { RefreshCw } from 'lucide-react'

export default function AdminLogs() {
  const [logs, setLogs] = useState<any[]>([])
  const [page, setPage] = useState(0)
  const [total, setTotal] = useState(0)
  const [filter, setFilter] = useState('')
  const token = localStorage.getItem('admin-token') || ''

  const fetchLogs = () => {
    fetch(`/api/admin/logs?page=${page}&size=30&action=${filter}`, {
      headers: { 'X-Admin-Token': token },
    }).then(r => r.json()).then(d => {
      setLogs(d.items || [])
      setTotal(d.total || 0)
    }).catch(() => {})
  }

  useEffect(() => { fetchLogs() }, [page, filter])

  const td: React.CSSProperties = {
    padding: '8px 12px', fontSize: 12.5, borderBottom: '1px solid var(--border-color)',
    color: 'var(--text-primary)',
  }

  const actionColors: Record<string, string> = {
    DISABLE_STUDENT: '#ca8a04', ENABLE_STUDENT: '#16a34a',
    DELETE_STUDENT: '#dc2626', BATCH_DELETE_CHATS: '#dc2626',
    BATCH_DELETE_FILES: '#dc2626', ADMIN_LOGOUT: '#6b7280',
  }

  return (
    <div style={{ padding: '28px 32px', maxWidth: 1200 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 }}>
        <div>
          <h1 style={{ fontSize: 20, fontWeight: 600, color: 'var(--text-primary)', margin: '0 0 4px' }}>
            审计日志
          </h1>
          <p style={{ fontSize: 12, color: 'var(--text-muted)', margin: 0 }}>总计 {total} 条操作记录</p>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <select value={filter} onChange={e => { setFilter(e.target.value); setPage(0) }}
            style={{
              height: 30, padding: '0 8px', borderRadius: 6,
              border: '1px solid var(--border-color)', background: 'var(--bg-secondary)',
              color: 'var(--text-primary)', fontSize: 12, fontFamily: 'inherit', outline: 'none',
            }}>
            <option value="">全部操作</option>
            <option value="DISABLE">禁用</option>
            <option value="ENABLE">启用</option>
            <option value="DELETE">删除</option>
            <option value="BATCH">批量操作</option>
            <option value="ADMIN_LOGOUT">管理员登出</option>
          </select>
          <button onClick={fetchLogs}
            style={{
              height: 30, padding: '0 10px', borderRadius: 6, border: '1px solid var(--border-color)',
              background: 'var(--bg-secondary)', color: 'var(--text-primary)', cursor: 'pointer',
              display: 'flex', alignItems: 'center', gap: 4, fontSize: 12, fontFamily: 'inherit',
            }}>
            <RefreshCw size={12} />
          </button>
        </div>
      </div>

      <div style={{
        borderRadius: 12, overflow: 'hidden',
        border: '1px solid var(--border-color)', background: 'var(--bg-secondary)',
      }}>
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'left', width: 130 }}>操作者</th>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'left', width: 170 }}>操作</th>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'left' }}>目标</th>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'left', width: 120 }}>IP</th>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'left', width: 160 }}>时间</th>
            </tr>
          </thead>
          <tbody>
            {logs.map(l => (
              <tr key={l.id}>
                <td style={{ ...td, fontWeight: 500 }}>{l.actorName}</td>
                <td style={{ ...td }}>
                  <span style={{
                    padding: '2px 8px', borderRadius: 8, fontSize: 11,
                    background: (actionColors[l.action] || '#6b7280') + '18',
                    color: actionColors[l.action] || '#6b7280',
                    fontWeight: 500,
                  }}>
                    {l.action}
                  </span>
                </td>
                <td style={{ ...td, color: 'var(--text-secondary)', fontSize: 12 }}>
                  {l.target ? l.target.replace(/^.*\.(\w+)\(.*/, '$1') : '-'}
                </td>
                <td style={{ ...td, fontFamily: 'monospace', fontSize: 10.5, color: 'var(--text-muted)' }}>
                  {l.ip}
                </td>
                <td style={{ ...td, color: 'var(--text-muted)', fontSize: 11.5 }}>
                  {l.createdAt ? l.createdAt.substring(0, 19).replace('T', ' ') : '-'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div style={{ display: 'flex', justifyContent: 'center', gap: 8, marginTop: 16 }}>
        <button disabled={page === 0} onClick={() => setPage(p => p - 1)} style={{ height: 28, padding: '0 10px', borderRadius: 5, border: '1px solid var(--border-color)', background: 'var(--bg-secondary)', color: 'var(--text-primary)', cursor: 'pointer', fontSize: 11.5, fontFamily: 'inherit', opacity: page === 0 ? 0.4 : 1 }}>上一页</button>
        <span style={{ fontSize: 12, color: 'var(--text-muted)', lineHeight: '28px' }}>{page + 1} / {Math.ceil(total / 30) || 1}</span>
        <button disabled={(page + 1) * 30 >= total} onClick={() => setPage(p => p + 1)} style={{ height: 28, padding: '0 10px', borderRadius: 5, border: '1px solid var(--border-color)', background: 'var(--bg-secondary)', color: 'var(--text-primary)', cursor: 'pointer', fontSize: 11.5, fontFamily: 'inherit', opacity: (page + 1) * 30 >= total ? 0.4 : 1 }}>下一页</button>
      </div>
    </div>
  )
}
