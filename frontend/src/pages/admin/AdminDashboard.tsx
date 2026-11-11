import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'

export default function AdminDashboard() {
  const [stats, setStats] = useState<any>(null)
  const [chatLogs, setChatLogs] = useState<any[]>([])
  const [uploadLogs, setUploadLogs] = useState<any[]>([])
  const [activeTab, setActiveTab] = useState<'overview' | 'chats' | 'uploads' | 'ips'>('overview')
  const [loading, setLoading] = useState(true)
  const navigate = useNavigate()

  const token = localStorage.getItem('admin-token')

  useEffect(() => {
    if (!token) { navigate('/admin/login'); return }
    fetchStats()
  }, [token])

  const fetchStats = async () => {
    setLoading(true)
    try {
      const r = await fetch('/api/admin/stats', { headers: { 'X-Admin-Token': token || '' } })
      if (r.status === 401) { localStorage.removeItem('admin-token'); navigate('/admin'); return }
      const d = await r.json()
      setStats(d)
    } catch { }
    setLoading(false)
  }

  const fetchChats = async () => {
    try {
      const r = await fetch('/api/admin/chat-logs?size=50', { headers: { 'X-Admin-Token': token || '' } })
      const d = await r.json()
      setChatLogs(d.items || [])
    } catch { }
  }

  const fetchUploads = async () => {
    try {
      const r = await fetch('/api/admin/upload-logs', { headers: { 'X-Admin-Token': token || '' } })
      setUploadLogs(await r.json())
    } catch { }
  }

  const logout = async () => {
    await fetch('/api/admin/logout', { method: 'POST', headers: { 'X-Admin-Token': token || '' } })
    localStorage.removeItem('admin-token')
    navigate('/admin')
  }

  const tabBtn = (tab: typeof activeTab, label: string) => (
    <button onClick={() => { setActiveTab(tab); if (tab === 'chats') fetchChats(); if (tab === 'uploads') fetchUploads() }}
      style={{
        padding: '8px 16px', border: 'none', borderRadius: 6,
        background: activeTab === tab ? 'var(--bg-hover)' : 'none',
        color: activeTab === tab ? 'var(--text-primary)' : 'var(--text-secondary)',
        cursor: 'pointer', fontSize: 13, fontFamily: 'inherit',
      }}>
      {label}
    </button>
  )

  return (
    <div style={{ height: '100vh', display: 'flex', flexDirection: 'column', background: 'var(--bg-primary)' }}>
      {/* Header */}
      <header style={{
        height: 48, minHeight: 48, borderBottom: '1px solid var(--border-color)',
        display: 'flex', alignItems: 'center', padding: '0 16px', gap: 8,
        background: 'var(--bg-secondary)',
      }}>
        <span style={{ fontSize: 14, fontWeight: 500, color: 'var(--text-primary)' }}>Admin Dashboard</span>
        <div style={{ flex: 1 }} />
        {tabBtn('overview', '概览')}
        {tabBtn('chats', '对话')}
        {tabBtn('uploads', '上传')}
        {tabBtn('ips', 'IP统计')}
        <span style={{ width: 1, height: 20, background: 'var(--border-color)' }} />
        <button onClick={logout}
          style={{ padding: '6px 12px', border: 'none', borderRadius: 6, background: 'none', color: 'var(--accent-red)', cursor: 'pointer', fontSize: 12, fontFamily: 'inherit' }}>
          退出
        </button>
      </header>

      {/* Content */}
      <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>
        {loading ? (
          <div style={{ textAlign: 'center', color: 'var(--text-muted)', padding: 48 }}>加载中...</div>
        ) : activeTab === 'overview' && stats ? (
          <div>
            {/* Cards */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 16, marginBottom: 24 }}>
              <StatCard label="题库总数" value={stats.files?.total || 0} color="var(--accent-blue)" />
              <StatCard label="已解析" value={stats.files?.completed || 0} color="var(--accent-green)" />
              <StatCard label="解析失败" value={stats.files?.failed || 0} color="var(--accent-red)" />
              <StatCard label="总对话数" value={stats.chats?.total || 0} color="var(--accent-purple)" />
              <StatCard label="题库大小" value={stats.examBankSizeMB + ' MB'} color="var(--accent-yellow)" />
            </div>

            {/* Storage bar */}
            <div style={{ marginBottom: 24, padding: 16, borderRadius: 10, background: 'var(--bg-secondary)', border: '1px solid var(--border-color)' }}>
              <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text-primary)', marginBottom: 4 }}>
                存储空间
              </div>
              <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 }}>
                总 {stats.storage?.totalGB}GB / 已用 {stats.storage?.usedGB}GB / 可用 {stats.storage?.freeGB}GB
              </div>
              <div style={{ height: 8, background: 'var(--bg-tertiary)', borderRadius: 4, overflow: 'hidden' }}>
                <div style={{
                  height: '100%', width: `${stats.storage?.usedPercent || 0}%`,
                  background: stats.storage?.usedPercent > 80 ? 'var(--accent-red)'
                              : stats.storage?.usedPercent > 50 ? 'var(--accent-yellow)'
                              : 'var(--accent-green)',
                  borderRadius: 4, transition: 'width 300ms',
                }} />
              </div>
            </div>

            {/* IP summary */}
            <div style={{ padding: 16, borderRadius: 10, background: 'var(--bg-secondary)', border: '1px solid var(--border-color)' }}>
              <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text-primary)', marginBottom: 8 }}>
                近期访问IP (Top 10)
              </div>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                <thead>
                  <tr style={{ color: 'var(--text-muted)', textAlign: 'left' }}>
                    <th style={{ padding: '4px 8px' }}>IP</th>
                    <th style={{ padding: '4px 8px', textAlign: 'right' }}>次数</th>
                    <th style={{ padding: '4px 8px' }}>最近访问</th>
                  </tr>
                </thead>
                <tbody>
                  {(stats.ipStats || []).slice(0, 10).map((ip: any, i: number) => (
                    <tr key={i} style={{ borderTop: '1px solid var(--border-color)', color: 'var(--text-secondary)' }}>
                      <td style={{ padding: '4px 8px' }}>{ip.ip}</td>
                      <td style={{ padding: '4px 8px', textAlign: 'right' }}>{ip.count}</td>
                      <td style={{ padding: '4px 8px', fontSize: 11 }}>{ip.lastAccess}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        ) : activeTab === 'chats' ? (
          <div style={{ padding: 16, borderRadius: 10, background: 'var(--bg-secondary)', border: '1px solid var(--border-color)' }}>
            <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text-primary)', marginBottom: 12 }}>对话记录</div>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
              <thead>
                <tr style={{ color: 'var(--text-muted)', textAlign: 'left' }}>
                  <th style={{ padding: '4px 8px' }}>时间</th>
                  <th style={{ padding: '4px 8px' }}>会话</th>
                  <th style={{ padding: '4px 8px' }}>角色</th>
                  <th style={{ padding: '4px 8px' }}>内容</th>
                </tr>
              </thead>
              <tbody>
                {chatLogs.map((c: any, i: number) => (
                  <tr key={i} style={{ borderTop: '1px solid var(--border-color)', color: 'var(--text-secondary)' }}>
                    <td style={{ padding: '4px 8px', fontSize: 11, whiteSpace: 'nowrap' }}>{c.createdAt?.substring(0, 16) || '-'}</td>
                    <td style={{ padding: '4px 8px', fontSize: 11 }}>{c.sessionId?.substring(0, 8) || '-'}</td>
                    <td style={{ padding: '4px 8px', color: c.role === 'user' ? 'var(--accent-blue)' : 'var(--accent-green)' }}>{c.role}</td>
                    <td style={{ padding: '4px 8px', maxWidth: 400, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{c.content?.substring(0, 80) || '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : activeTab === 'uploads' ? (
          <div style={{ padding: 16, borderRadius: 10, background: 'var(--bg-secondary)', border: '1px solid var(--border-color)' }}>
            <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text-primary)', marginBottom: 12 }}>上传记录</div>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
              <thead>
                <tr style={{ color: 'var(--text-muted)', textAlign: 'left' }}>
                  <th style={{ padding: '4px 8px' }}>文件</th>
                  <th style={{ padding: '4px 8px' }}>类型</th>
                  <th style={{ padding: '4px 8px' }}>状态</th>
                  <th style={{ padding: '4px 8px' }}>解析方式</th>
                  <th style={{ padding: '4px 8px' }}>大小</th>
                </tr>
              </thead>
              <tbody>
                {uploadLogs.map((f: any, i: number) => (
                  <tr key={i} style={{ borderTop: '1px solid var(--border-color)', color: 'var(--text-secondary)' }}>
                    <td style={{ padding: '4px 8px', maxWidth: 240, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.fileName || '-'}</td>
                    <td style={{ padding: '4px 8px' }}>{f.fileType || '-'}</td>
                    <td style={{ padding: '4px 8px', color: f.parseStatus === 'completed' ? 'var(--accent-green)' : f.parseStatus === 'failed' ? 'var(--accent-red)' : 'var(--accent-yellow)' }}>{f.parseStatus}</td>
                    <td style={{ padding: '4px 8px' }}>{f.parseMethod || '-'}</td>
                    <td style={{ padding: '4px 8px' }}>{f.fileSize ? (f.fileSize / 1024).toFixed(1) + 'KB' : '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : activeTab === 'ips' && stats ? (
          <div style={{ padding: 16, borderRadius: 10, background: 'var(--bg-secondary)', border: '1px solid var(--border-color)' }}>
            <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text-primary)', marginBottom: 12 }}>IP 访问统计</div>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
              <thead>
                <tr style={{ color: 'var(--text-muted)', textAlign: 'left' }}>
                  <th style={{ padding: '4px 8px' }}>IP</th>
                  <th style={{ padding: '4px 8px', textAlign: 'right' }}>访问次数</th>
                  <th style={{ padding: '4px 8px' }}>首次访问</th>
                  <th style={{ padding: '4px 8px' }}>最近访问</th>
                </tr>
              </thead>
              <tbody>
                {(stats.ipStats || []).map((ip: any, i: number) => (
                  <tr key={i} style={{ borderTop: '1px solid var(--border-color)', color: 'var(--text-secondary)' }}>
                    <td style={{ padding: '4px 8px' }}>{ip.ip}</td>
                    <td style={{ padding: '4px 8px', textAlign: 'right', fontWeight: 500, color: ip.count > 50 ? 'var(--accent-red)' : 'var(--text-secondary)' }}>{ip.count}</td>
                    <td style={{ padding: '4px 8px', fontSize: 11 }}>{ip.firstAccess}</td>
                    <td style={{ padding: '4px 8px', fontSize: 11 }}>{ip.lastAccess}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </div>
    </div>
  )
}

function StatCard({ label, value, color }: { label: string; value: string | number; color: string }) {
  return (
    <div style={{ padding: 16, borderRadius: 10, background: 'var(--bg-secondary)', border: '1px solid var(--border-color)' }}>
      <div style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 4 }}>{label}</div>
      <div style={{ fontSize: 24, fontWeight: 600, color }}>{value}</div>
    </div>
  )
}
