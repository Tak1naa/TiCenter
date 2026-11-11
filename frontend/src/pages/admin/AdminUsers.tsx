import { useState, useEffect } from 'react'
import {
  Ban, CheckCircle, Trash2, Download,
  Search, RefreshCw
} from 'lucide-react'

export default function AdminUsers() {
  const [users, setUsers] = useState<any[]>([])
  const [stats, setStats] = useState<any>({})
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [total, setTotal] = useState(0)
  const token = localStorage.getItem('admin-token') || ''

  const fetchUsers = () => {
    fetch(`/api/admin/students?page=${page}&size=20&search=${search}`, {
      headers: { 'X-Admin-Token': token },
    }).then(r => r.json()).then(d => { setUsers(d.items || []); setTotal(d.total || 0) }).catch(() => {})
  }

  const fetchStats = () => {
    fetch('/api/admin/students/stats', { headers: { 'X-Admin-Token': token } })
      .then(r => r.json()).then(setStats).catch(() => {})
  }

  useEffect(() => { fetchUsers(); fetchStats() }, [page, search])

  const action = async (url: string, method: string) => {
    await fetch(url, { method, headers: { 'X-Admin-Token': token } }).catch(() => {})
    fetchUsers()
    fetchStats()
  }

  const td: React.CSSProperties = {
    padding: '8px 12px', fontSize: 12.5, borderBottom: '1px solid var(--border-color)',
    color: 'var(--text-primary)', whiteSpace: 'nowrap',
  }

  const btn: React.CSSProperties = {
    height: 28, padding: '0 10px', borderRadius: 5, border: 'none',
    cursor: 'pointer', fontSize: 11.5, fontFamily: 'inherit',
    display: 'inline-flex', alignItems: 'center', gap: 4,
  }

  return (
    <div style={{ padding: '28px 32px', maxWidth: 1200 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 }}>
        <div>
          <h1 style={{ fontSize: 20, fontWeight: 600, color: 'var(--text-primary)', margin: '0 0 4px' }}>
            用户管理
          </h1>
          <p style={{ fontSize: 12, color: 'var(--text-muted)', margin: 0 }}>
            总计 {stats.total || 0} · 活跃 {stats.active || 0} · 禁用 {stats.disabled || 0}
          </p>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <a href={`/api/admin/data/export/students?token=${encodeURIComponent(token)}`}
            style={{ ...btn, background: 'var(--bg-secondary)', color: 'var(--text-primary)',
              border: '1px solid var(--border-color)', textDecoration: 'none' }}>
            <Download size={13} />导出 CSV
          </a>
          <button onClick={() => { fetchUsers(); fetchStats() }}
            style={{ ...btn, background: 'var(--bg-secondary)', color: 'var(--text-primary)', border: '1px solid var(--border-color)' }}>
            <RefreshCw size={13} />
          </button>
        </div>
      </div>

      <div style={{ marginBottom: 16, display: 'flex', gap: 8 }}>
        <div style={{ position: 'relative', flex: 1, maxWidth: 300 }}>
          <Search size={14} style={{ position: 'absolute', left: 10, top: 10, color: 'var(--text-muted)' }} />
          <input value={search} onChange={e => { setSearch(e.target.value); setPage(0) }}
            placeholder="搜索学号或邮箱..."
            style={{
              width: '100%', height: 36, paddingLeft: 32, paddingRight: 12,
              borderRadius: 8, border: '1px solid var(--border-color)',
              background: 'var(--bg-secondary)', color: 'var(--text-primary)',
              fontSize: 13, outline: 'none', fontFamily: 'inherit', boxSizing: 'border-box',
            }} />
        </div>
      </div>

      <div style={{
        borderRadius: 12, overflow: 'hidden',
        border: '1px solid var(--border-color)', background: 'var(--bg-secondary)',
      }}>
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'left' }}>学号</th>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'left' }}>邮箱</th>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'center' }}>状态</th>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'left' }}>注册时间</th>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'left' }}>最后登录</th>
              <th style={{ ...td, background: 'var(--bg-tertiary)', fontSize: 11, fontWeight: 500, color: 'var(--text-muted)', textAlign: 'right' }}>操作</th>
            </tr>
          </thead>
          <tbody>
            {users.map(u => (
              <tr key={u.id}>
                <td style={td}>{u.studentNo}</td>
                <td style={{ ...td, color: 'var(--text-secondary)' }}>
                  {u.email}
                  {u.emailVerified ? <span style={{ marginLeft: 4, fontSize: 10, color: 'var(--accent-green)' }}>✓</span> : null}
                </td>
                <td style={{ ...td, textAlign: 'center' }}>
                  <span style={{
                    padding: '2px 8px', borderRadius: 10, fontSize: 11,
                    background: u.status === 'ACTIVE' ? 'var(--accent-green-light, #dcfce7)' : 'var(--accent-red-light, #fef2f2)',
                    color: u.status === 'ACTIVE' ? 'var(--accent-green, #16a34a)' : 'var(--accent-red, #dc2626)',
                  }}>
                    {u.status === 'ACTIVE' ? '活跃' : '禁用'}
                  </span>
                </td>
                <td style={{ ...td, color: 'var(--text-muted)', fontSize: 11.5 }}>
                  {u.createdAt ? u.createdAt.substring(0, 16).replace('T', ' ') : '-'}
                </td>
                <td style={{ ...td, color: 'var(--text-muted)', fontSize: 11.5 }}>
                  {u.lastLoginAt ? u.lastLoginAt.substring(0, 16).replace('T', ' ') : '-'}
                </td>
                <td style={{ ...td, textAlign: 'right' }}>
                  {u.status === 'ACTIVE' ? (
                    <button onClick={() => action(`/api/admin/students/${u.studentNo}/disable`, 'POST')}
                      style={{ ...btn, background: 'var(--accent-yellow-light, #fef9c3)', color: 'var(--accent-yellow, #ca8a04)' }}>
                      <Ban size={12} />禁用
                    </button>
                  ) : (
                    <button onClick={() => action(`/api/admin/students/${u.studentNo}/enable`, 'POST')}
                      style={{ ...btn, background: 'var(--accent-green-light, #dcfce7)', color: 'var(--accent-green, #16a34a)' }}>
                      <CheckCircle size={12} />启用
                    </button>
                  )}
                  <button onClick={() => {
                    if (confirm(`确认删除学生 ${u.studentNo}？此操作不可撤销。`))
                      action(`/api/admin/students/${u.studentNo}`, 'DELETE')
                  }}
                    style={{ ...btn, background: 'transparent', color: 'var(--accent-red)', marginLeft: 4 }}>
                    <Trash2 size={12} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div style={{ display: 'flex', justifyContent: 'center', gap: 8, marginTop: 16 }}>
        <button disabled={page === 0} onClick={() => setPage(p => p - 1)}
          style={{ ...btn, background: 'var(--bg-secondary)', color: 'var(--text-primary)', border: '1px solid var(--border-color)', opacity: page === 0 ? 0.4 : 1 }}>
          上一页
        </button>
        <span style={{ fontSize: 12, color: 'var(--text-muted)', lineHeight: '28px' }}>
          {page + 1} / {Math.ceil(total / 20) || 1}
        </span>
        <button disabled={(page + 1) * 20 >= total} onClick={() => setPage(p => p + 1)}
          style={{ ...btn, background: 'var(--bg-secondary)', color: 'var(--text-primary)', border: '1px solid var(--border-color)', opacity: (page + 1) * 20 >= total ? 0.4 : 1 }}>
          下一页
        </button>
      </div>
    </div>
  )
}
