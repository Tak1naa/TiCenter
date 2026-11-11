import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'

type Tab = 'login' | 'register' | 'forgot'

export default function StudentLogin() {
  const navigate = useNavigate()
  const [tab, setTab] = useState<Tab>('login')
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(false)

  // Login fields
  const [loginNo, setLoginNo] = useState('')
  const [loginPwd, setLoginPwd] = useState('')

  // Register fields
  const [regNo, setRegNo] = useState('')
  const [regPwd, setRegPwd] = useState('')
  const [regEmail, setRegEmail] = useState('')

  // Forgot fields
  const [forgotEmail, setForgotEmail] = useState('')
  const [resetToken, setResetToken] = useState('')
  const [resetPwd, setResetPwd] = useState('')
  const [resetStep, setResetStep] = useState<'email' | 'reset'>('email')

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const resp = await fetch('/api/student/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: loginNo, password: loginPwd }),
      })
      const data = await resp.json()
      if (!resp.ok) { setError(data.error || '登录失败'); return }
      // HttpOnly cookie carries the real session; localStorage stores only a non-secret UI marker.
      localStorage.setItem('student-token', 'cookie')
      localStorage.setItem('student-info', JSON.stringify({
        studentNo: data.studentNo,
        email: data.email,
        emailVerified: data.emailVerified,
      }))
      navigate('/', { replace: true })
    } catch { setError('网络错误') }
    finally { setLoading(false) }
  }

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    if (!/^\d{8}$/.test(regNo)) { setError('学号必须为8位数字'); return }
    if (!/^(?=.*[A-Za-z])(?=.*\d).{8,}$/.test(regPwd)) { setError('密码至少8位，且必须包含字母和数字'); return }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(regEmail)) { setError('邮箱格式不正确'); return }
    setLoading(true)
    try {
      const resp = await fetch('/api/student/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ studentNo: regNo, password: regPwd, email: regEmail }),
      })
      const data = await resp.json()
      if (!resp.ok) { setError(data.error || '注册失败'); return }
      setMessage('注册成功，请登录')
      setTab('login')
    } catch { setError('网络错误') }
    finally { setLoading(false) }
  }

  const handleForgotEmail = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const resp = await fetch('/api/student/forgot-password', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: forgotEmail }),
      })
      const data = await resp.json()
      setMessage(data.message || '如果该邮箱已注册，重置链接已发送')
      setResetStep('reset')
    } catch { setError('网络错误') }
    finally { setLoading(false) }
  }

  const handleResetPassword = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    if (!resetToken.trim()) { setError('请输入重置令牌'); return }
    if (!/^(?=.*[A-Za-z])(?=.*\d).{8,}$/.test(resetPwd)) { setError('密码至少8位，且必须包含字母和数字'); return }
    setLoading(true)
    try {
      const resp = await fetch('/api/student/reset-password', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token: resetToken.trim(), newPassword: resetPwd }),
      })
      const data = await resp.json()
      if (!resp.ok) { setError(data.error || '重置失败'); return }
      setMessage('密码重置成功，请登录')
      setTab('login')
      setResetStep('email')
    } catch { setError('网络错误') }
    finally { setLoading(false) }
  }

  const inputStyle: React.CSSProperties = {
    width: '100%', height: 42, padding: '0 14px',
    borderRadius: 10, border: '1px solid var(--border-color)',
    background: 'var(--bg-tertiary)', color: 'var(--text-primary)',
    fontSize: 14, outline: 'none', fontFamily: 'inherit',
    boxSizing: 'border-box',
  }

  const btnStyle: React.CSSProperties = {
    width: '100%', height: 44, borderRadius: 10,
    border: 'none', cursor: 'pointer', fontSize: 15, fontWeight: 500,
    fontFamily: 'inherit',
    background: loading ? 'var(--border-color)' : 'var(--text-primary)',
    color: loading ? 'var(--text-muted)' : 'var(--bg-primary)',
  }

  const tabs: { key: Tab; label: string }[] = [
    { key: 'login', label: '登录' },
    { key: 'register', label: '注册' },
    { key: 'forgot', label: '找回密码' },
  ]

  return (
    <div style={{
      minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center',
      background: 'var(--bg-primary)', padding: 20,
    }}>
      <div style={{ width: 380 }}>
        <div style={{ marginBottom: 32, textAlign: 'center' }}>
          <h1 style={{ fontSize: 24, fontWeight: 600, color: 'var(--text-primary)', letterSpacing: '-0.02em', marginBottom: 4 }}>
            AI 智能题库
          </h1>
          <p style={{ fontSize: 13, color: 'var(--text-muted)' }}>
            基于 RAG 的智能备考平台
          </p>
        </div>

        <div style={{
          display: 'flex', marginBottom: 24,
          background: 'var(--bg-tertiary)', borderRadius: 12, padding: 3,
        }}>
          {tabs.map(t => (
            <button key={t.key} onClick={() => { setTab(t.key); setError(''); setMessage('') }}
              style={{
                flex: 1, height: 36, borderRadius: 10, border: 'none', cursor: 'pointer',
                fontSize: 13, fontWeight: 500, fontFamily: 'inherit',
                background: tab === t.key ? 'var(--bg-primary)' : 'transparent',
                color: tab === t.key ? 'var(--text-primary)' : 'var(--text-muted)',
                boxShadow: tab === t.key ? 'var(--shadow-sm)' : 'none',
                transition: 'background 150ms, color 150ms',
              }}>
              {t.label}
            </button>
          ))}
        </div>

        {error && (
          <div style={{
            padding: '10px 14px', borderRadius: 8, marginBottom: 16,
            background: 'var(--accent-red-light, #fef2f2)',
            color: 'var(--accent-red, #dc2626)', fontSize: 13,
          }}>
            {error}
          </div>
        )}

        {message && (
          <div style={{
            padding: '10px 14px', borderRadius: 8, marginBottom: 16,
            background: 'var(--accent-green-light, #f0fdf4)',
            color: 'var(--accent-green, #16a34a)', fontSize: 13,
          }}>
            {message}
          </div>
        )}

        {tab === 'login' && (
          <form onSubmit={handleLogin}>
            <div style={{ marginBottom: 14 }}>
              <label style={{ fontSize: 12, color: 'var(--text-secondary)', display: 'block', marginBottom: 6 }}>学号</label>
              <input style={inputStyle} value={loginNo} onChange={e => setLoginNo(e.target.value)}
                placeholder="8位学号" maxLength={8} autoFocus />
            </div>
            <div style={{ marginBottom: 20 }}>
              <label style={{ fontSize: 12, color: 'var(--text-secondary)', display: 'block', marginBottom: 6 }}>密码</label>
              <input style={inputStyle} type="password" value={loginPwd} onChange={e => setLoginPwd(e.target.value)}
                placeholder="输入密码" />
            </div>
            <button type="submit" style={btnStyle} disabled={loading}>
              {loading ? '登录中...' : '登录'}
            </button>
          </form>
        )}

        {tab === 'register' && (
          <form onSubmit={handleRegister}>
            <div style={{ marginBottom: 14 }}>
              <label style={{ fontSize: 12, color: 'var(--text-secondary)', display: 'block', marginBottom: 6 }}>学号</label>
              <input style={inputStyle} value={regNo} onChange={e => setRegNo(e.target.value)}
                placeholder="8位数字学号" maxLength={8} autoFocus />
            </div>
            <div style={{ marginBottom: 14 }}>
              <label style={{ fontSize: 12, color: 'var(--text-secondary)', display: 'block', marginBottom: 6 }}>密码</label>
              <input style={inputStyle} type="password" value={regPwd} onChange={e => setRegPwd(e.target.value)}
                placeholder="至少8位，含字母和数字" />
            </div>
            <div style={{ marginBottom: 20 }}>
              <label style={{ fontSize: 12, color: 'var(--text-secondary)', display: 'block', marginBottom: 6 }}>邮箱</label>
              <input style={inputStyle} type="email" value={regEmail} onChange={e => setRegEmail(e.target.value)}
                placeholder="用于找回密码" />
            </div>
            <button type="submit" style={btnStyle} disabled={loading}>
              {loading ? '注册中...' : '注册'}
            </button>
          </form>
        )}

        {tab === 'forgot' && (
          <>
            {resetStep === 'email' ? (
              <form onSubmit={handleForgotEmail}>
                <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 14, lineHeight: 1.6 }}>
                  输入注册时绑定的邮箱，我们将发送密码重置链接。
                </p>
                <div style={{ marginBottom: 20 }}>
                  <label style={{ fontSize: 12, color: 'var(--text-secondary)', display: 'block', marginBottom: 6 }}>邮箱</label>
                  <input style={inputStyle} type="email" value={forgotEmail} onChange={e => setForgotEmail(e.target.value)}
                    placeholder="注册时填写的邮箱" autoFocus />
                </div>
                <button type="submit" style={btnStyle} disabled={loading}>
                  {loading ? '发送中...' : '发送重置链接'}
                </button>
                <button type="button" onClick={() => setResetStep('reset')}
                  style={{ ...btnStyle, background: 'transparent', color: 'var(--accent-blue)', marginTop: 10, fontWeight: 400, fontSize: 13 }}>
                  已有重置令牌？直接重置
                </button>
              </form>
            ) : (
              <form onSubmit={handleResetPassword}>
                <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 14, lineHeight: 1.6 }}>
                  输入从邮箱收到的重置令牌和新密码。
                </p>
                <div style={{ marginBottom: 14 }}>
                  <label style={{ fontSize: 12, color: 'var(--text-secondary)', display: 'block', marginBottom: 6 }}>重置令牌</label>
                  <input style={inputStyle} value={resetToken} onChange={e => setResetToken(e.target.value)}
                    placeholder="粘贴重置令牌" autoFocus />
                </div>
                <div style={{ marginBottom: 20 }}>
                  <label style={{ fontSize: 12, color: 'var(--text-secondary)', display: 'block', marginBottom: 6 }}>新密码</label>
                  <input style={inputStyle} type="password" value={resetPwd} onChange={e => setResetPwd(e.target.value)}
                    placeholder="至少8位，含字母和数字" />
                </div>
                <button type="submit" style={btnStyle} disabled={loading}>
                  {loading ? '重置中...' : '重置密码'}
                </button>
                <button type="button" onClick={() => setResetStep('email')}
                  style={{ ...btnStyle, background: 'transparent', color: 'var(--accent-blue)', marginTop: 10, fontWeight: 400, fontSize: 13 }}>
                  <ArrowLeft size={14} style={{ marginRight: 4, verticalAlign: 'middle' }} />
                  返回邮箱找回
                </button>
              </form>
            )}
          </>
        )}
      </div>
    </div>
  )
}
