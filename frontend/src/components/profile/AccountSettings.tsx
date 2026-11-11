import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { LogOut } from 'lucide-react'

export default function AccountSettings() {
  const navigate = useNavigate()
  const info = JSON.parse(localStorage.getItem('student-info') || '{}')

  const [oldPwd, setOldPwd] = useState('')
  const [newPwd, setNewPwd] = useState('')
  const [pwdConfirm, setPwdConfirm] = useState('')
  const [pwdMsg, setPwdMsg] = useState('')
  const [pwdError, setPwdError] = useState(false)
  const [pwdLoading, setPwdLoading] = useState(false)

  const [newEmail, setNewEmail] = useState(info.email || '')
  const [emailMsg, setEmailMsg] = useState('')
  const [emailError, setEmailError] = useState(false)
  const [emailLoading, setEmailLoading] = useState(false)

  const handleChangePassword = async (e: React.FormEvent) => {
    e.preventDefault()
    setPwdMsg(''); setPwdError(false)
    if (newPwd !== pwdConfirm) { setPwdMsg('两次密码不一致'); setPwdError(true); return }
    if (!/^(?=.*[A-Za-z])(?=.*\d).{8,}$/.test(newPwd)) {
      setPwdMsg('密码至少8位，且必须包含字母和数字'); setPwdError(true); return
    }
    setPwdLoading(true)
    try {
      const resp = await fetch('/api/student/change-password', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Student-Token': localStorage.getItem('student-token') || '',
        },
        body: JSON.stringify({ oldPassword: oldPwd, newPassword: newPwd }),
      })
      const data = await resp.json()
      if (!resp.ok) { setPwdMsg(data.error || '修改失败'); setPwdError(true); return }
      setPwdMsg(data.message || '密码修改成功'); setPwdError(false)
      setOldPwd(''); setNewPwd(''); setPwdConfirm('')
    } catch { setPwdMsg('网络错误'); setPwdError(true) }
    finally { setPwdLoading(false) }
  }

  const handleBindEmail = async (e: React.FormEvent) => {
    e.preventDefault()
    setEmailMsg(''); setEmailError(false)
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(newEmail)) {
      setEmailMsg('邮箱格式不正确'); setEmailError(true); return
    }
    setEmailLoading(true)
    try {
      const resp = await fetch('/api/student/bind-email', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Student-Token': localStorage.getItem('student-token') || '',
        },
        body: JSON.stringify({ email: newEmail }),
      })
      const data = await resp.json()
      if (!resp.ok) { setEmailMsg(data.error || '绑定失败'); setEmailError(true); return }
      setEmailMsg('邮箱绑定成功')
      setEmailError(false)
      const oldInfo = JSON.parse(localStorage.getItem('student-info') || '{}')
      localStorage.setItem('student-info', JSON.stringify({ ...oldInfo, email: newEmail }))
    } catch { setEmailMsg('网络错误'); setEmailError(true) }
    finally { setEmailLoading(false) }
  }

  const handleLogout = () => {
    fetch('/api/student/logout', {
      method: 'POST',
      headers: { 'X-Student-Token': localStorage.getItem('student-token') || '' },
    }).catch(() => {})
    localStorage.removeItem('student-token')
    localStorage.removeItem('student-info')
    navigate('/login', { replace: true })
  }

  const inputStyle: React.CSSProperties = {
    width: '100%', height: 34, padding: '0 10px',
    borderRadius: 6, border: '1px solid var(--border-color)',
    background: 'var(--bg-tertiary)', color: 'var(--text-primary)',
    fontSize: 13, outline: 'none', fontFamily: 'inherit', boxSizing: 'border-box',
  }

  const btnStyle: React.CSSProperties = {
    height: 34, padding: '0 16px', borderRadius: 6, border: 'none',
    cursor: 'pointer', fontSize: 13, fontWeight: 500, fontFamily: 'inherit',
    background: 'var(--accent-blue)', color: '#fff',
  }

  return (
    <div>
      <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 16 }}>
        账号信息
      </div>

      <div style={{
        padding: 12, borderRadius: 8, background: 'var(--bg-tertiary)',
        marginBottom: 20, fontSize: 13, lineHeight: 1.8, color: 'var(--text-secondary)',
      }}>
        <div>学号：<span style={{ color: 'var(--text-primary)', fontWeight: 500 }}>{info.studentNo || '-'}</span></div>
        <div>邮箱：<span style={{ color: 'var(--text-primary)' }}>{info.email || '未绑定'}</span>
          {info.emailVerified ? <span style={{ marginLeft: 6, fontSize: 11, color: 'var(--accent-green)' }}>已验证</span>
            : info.email ? <span style={{ marginLeft: 6, fontSize: 11, color: 'var(--text-muted)' }}>待验证</span> : null}
        </div>
      </div>

      {/* Change Password */}
      <form onSubmit={handleChangePassword} style={{ marginBottom: 20 }}>
        <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text-primary)', marginBottom: 10 }}>
          更改密码
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <input style={inputStyle} type="password" value={oldPwd} onChange={e => setOldPwd(e.target.value)}
            placeholder="旧密码" />
          <input style={inputStyle} type="password" value={newPwd} onChange={e => setNewPwd(e.target.value)}
            placeholder="新密码（至少8位，含字母和数字）" />
          <input style={inputStyle} type="password" value={pwdConfirm} onChange={e => setPwdConfirm(e.target.value)}
            placeholder="确认新密码" />
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <button type="submit" style={{ ...btnStyle, opacity: pwdLoading ? 0.6 : 1 }} disabled={pwdLoading}>
              保存密码
            </button>
          </div>
        </div>
        {pwdMsg && (
          <div style={{ marginTop: 8, fontSize: 12, color: pwdError ? 'var(--accent-red)' : 'var(--accent-green)' }}>
            {pwdMsg}
          </div>
        )}
      </form>

      {/* Change Email */}
      <form onSubmit={handleBindEmail} style={{ marginBottom: 24 }}>
        <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text-primary)', marginBottom: 10 }}>
          绑定邮箱
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <input style={{ ...inputStyle, flex: 1 }} type="email" value={newEmail}
            onChange={e => setNewEmail(e.target.value)} placeholder="your@email.com" />
          <button type="submit" style={{ ...btnStyle, opacity: emailLoading ? 0.6 : 1 }} disabled={emailLoading}>
            保存
          </button>
        </div>
        {emailMsg && (
          <div style={{ marginTop: 8, fontSize: 12, color: emailError ? 'var(--accent-red)' : 'var(--accent-green)' }}>
            {emailMsg}
          </div>
        )}
      </form>

      {/* Logout */}
      <button onClick={handleLogout} style={{
        width: '100%', height: 38, borderRadius: 8, border: '1px solid var(--border-color)',
        background: 'var(--bg-secondary)', color: 'var(--accent-red)', cursor: 'pointer',
        display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
        fontSize: 13, fontWeight: 500, fontFamily: 'inherit',
      }}>
        <LogOut size={15} />
        退出登录
      </button>
    </div>
  )
}
