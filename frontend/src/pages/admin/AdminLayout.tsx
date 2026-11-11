import { Outlet } from 'react-router-dom'
import { useState, useEffect } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { LayoutDashboard, Users, MessageSquare, ScrollText, Settings, LogOut, Shield, FolderOpen, Activity, KeyRound, X } from 'lucide-react'

export default function AdminLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const [adminInfo, setAdminInfo] = useState<any>(null)
  const [pwdOpen, setPwdOpen] = useState(false)
  const [oldPwd, setOldPwd] = useState('')
  const [newPwd, setNewPwd] = useState('')
  const [pwdMsg, setPwdMsg] = useState('')
  const [pwdErr, setPwdErr] = useState(false)

  useEffect(() => {
    const token = localStorage.getItem('admin-token')
    if (!token) { navigate('/admin/login', { replace: true }); return }
    fetch('/api/admin/auth/me', { headers: { 'X-Admin-Token': token } })
      .then(r => r.json())
      .then(d => { if (d.error) navigate('/admin/login', { replace: true }); else setAdminInfo(d); })
      .catch(() => {})
  }, [navigate])

  const handleLogout = () => {
    const token = localStorage.getItem('admin-token')
    fetch('/api/admin/auth/logout', { method: 'POST', headers: { 'X-Admin-Token': token || '' } }).catch(() => {})
    localStorage.removeItem('admin-token')
    navigate('/admin/login', { replace: true })
  }

  const handleChangePassword = async () => {
    const token = localStorage.getItem('admin-token')
    setPwdMsg(''); setPwdErr(false)
    if (newPwd.length < 8) { setPwdMsg('新密码至少 8 位'); setPwdErr(true); return }
    try {
      const r = await fetch('/api/admin/auth/change-password', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Admin-Token': token || '' },
        body: JSON.stringify({ oldPassword: oldPwd, newPassword: newPwd }),
      })
      const d = await r.json()
      if (!r.ok) { setPwdMsg(d.error || '修改失败'); setPwdErr(true); return }
      setPwdMsg('密码已修改，请重新登录'); setPwdErr(false)
      setTimeout(() => { setPwdOpen(false); handleLogout() }, 1500)
    } catch { setPwdMsg('网络错误'); setPwdErr(true) }
  }

  const currentPath = location.pathname

  const navItems = [
    { path: '/admin/dashboard', label: '概览', icon: <LayoutDashboard size={16} /> },
    { path: '/admin/monitor', label: '监控', icon: <Activity size={16} /> },
    { path: '/admin/users', label: '用户', icon: <Users size={16} /> },
    { path: '/admin/chats', label: '对话', icon: <MessageSquare size={16} /> },
    { path: '/admin/files', label: '题库', icon: <FolderOpen size={16} /> },
    { path: '/admin/logs', label: '日志', icon: <ScrollText size={16} /> },
    { path: '/admin/settings', label: '设置', icon: <Settings size={16} /> },
  ]

  const navBtnStyle = (active: boolean): React.CSSProperties => ({
    width: '100%', display: 'flex', alignItems: 'center', gap: 10,
    height: 36, padding: '0 14px', borderRadius: 8,
    border: 'none', cursor: 'pointer', fontSize: 13, fontFamily: 'inherit',
    background: active ? 'var(--bg-hover)' : 'transparent',
    color: active ? 'var(--text-primary)' : 'var(--text-secondary)',
    marginBottom: 2,
  })

  return (
    <div style={{ display: 'flex', height: '100vh', background: 'var(--bg-primary)' }}>
      {/* Sidebar */}
      <div style={{
        width: 220, minWidth: 220, borderRight: '1px solid var(--border-color)',
        background: 'var(--bg-secondary)', display: 'flex', flexDirection: 'column',
      }}>
        <div style={{ padding: '20px 16px 16px', borderBottom: '1px solid var(--border-color)' }}>
          <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', letterSpacing: '-0.01em' }}>
            AI 智能题库
          </div>
          <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 2 }}>
            管理后台
          </div>
        </div>

        <div style={{ padding: '8px', flex: 1 }}>
          {navItems.map(item => (
            <button key={item.path}
              onClick={() => navigate(item.path)}
              style={navBtnStyle(currentPath.startsWith(item.path))}>
              {item.icon}
              {item.label}
            </button>
          ))}
        </div>

        <div style={{
          padding: '12px 14px', borderTop: '1px solid var(--border-color)',
          display: 'flex', alignItems: 'center', gap: 10,
        }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--text-primary)' }}>
              {adminInfo?.displayName || adminInfo?.username || '管理员'}
            </div>
            <div style={{ fontSize: 10, color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: 4 }}>
              <Shield size={10} />
              {adminInfo?.role || '-'}
            </div>
          </div>
          <button onClick={() => setPwdOpen(true)}
            title="更改密码"
            style={{ width: 32, height: 32, borderRadius: 6, border: 'none', background: 'var(--bg-hover)', color: 'var(--text-secondary)', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <KeyRound size={14} />
          </button>
          <button onClick={handleLogout}
            title="退出登录"
            style={{ width: 32, height: 32, borderRadius: 6, border: 'none', background: 'var(--bg-hover)', color: 'var(--text-secondary)', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <LogOut size={14} />
          </button>
        </div>
      </div>

      {/* 改密码弹窗 */}
      {pwdOpen && (
        <div style={{
          position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 1000,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }} onClick={() => setPwdOpen(false)}>
          <div style={{
            width: 360, background: 'var(--bg-primary)', borderRadius: 12,
            border: '1px solid var(--border-color)', padding: 20,
          }} onClick={e => e.stopPropagation()}>
            <div style={{ display: 'flex', alignItems: 'center', marginBottom: 16 }}>
              <h3 style={{ fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', margin: 0, flex: 1 }}>
                更改密码
              </h3>
              <button onClick={() => setPwdOpen(false)} style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer' }}>
                <X size={16} />
              </button>
            </div>
            <input type="password" placeholder="旧密码" value={oldPwd}
              onChange={e => setOldPwd(e.target.value)}
              style={pwdInputStyle} />
            <input type="password" placeholder="新密码（至少 8 位）" value={newPwd}
              onChange={e => setNewPwd(e.target.value)}
              style={{ ...pwdInputStyle, marginTop: 10 }} />
            {pwdMsg && (
              <div style={{ marginTop: 10, fontSize: 12, color: pwdErr ? 'var(--accent-red)' : 'var(--accent-green)' }}>
                {pwdMsg}
              </div>
            )}
            <button onClick={handleChangePassword}
              style={{
                width: '100%', height: 36, marginTop: 16, borderRadius: 8, border: 'none',
                background: 'var(--accent-blue)', color: '#fff', cursor: 'pointer', fontSize: 13, fontWeight: 500,
              }}>
              确认修改
            </button>
          </div>
        </div>
      )}

      {/* Content */}
      <div style={{ flex: 1, overflow: 'auto' }}>
        <Outlet />
      </div>
    </div>
  )
}

const pwdInputStyle: React.CSSProperties = {
  width: '100%', height: 34, padding: '0 10px', boxSizing: 'border-box',
  borderRadius: 6, border: '1px solid var(--border-color)',
  background: 'var(--bg-tertiary)', color: 'var(--text-primary)',
  fontSize: 13, outline: 'none', fontFamily: 'inherit',
}
