import { useState, useEffect } from 'react'
import { useSearchParams, useNavigate, Link } from 'react-router-dom'

/** 邮件重置链接落地页：从 ?token= 读取重置令牌，输入新密码完成重置。 */
export default function ResetPassword() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const token = params.get('token') || ''
  const [pwd, setPwd] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!token) setError('链接缺少重置令牌，请重新申请')
  }, [token])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    if (!token) { setError('链接缺少重置令牌'); return }
    if (!/^(?=.*[A-Za-z])(?=.*\d).{8,}$/.test(pwd)) { setError('密码至少8位，且必须包含字母和数字'); return }
    if (pwd !== confirm) { setError('两次输入的密码不一致'); return }
    setLoading(true)
    try {
      const resp = await fetch('/api/student/reset-password', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, newPassword: pwd }),
      })
      const data = await resp.json()
      if (!resp.ok) { setError(data.error || '重置失败'); return }
      setMessage('密码重置成功，请使用新密码登录')
      setTimeout(() => navigate('/login'), 2000)
    } catch { setError('网络错误') }
    finally { setLoading(false) }
  }

  return (
    <div style={{
      minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center',
      background: 'var(--bg-primary)', padding: 20,
    }}>
      <div style={{
        width: 400, padding: 32, borderRadius: 16,
        background: 'var(--bg-secondary)', border: '1px solid var(--border-color)',
        boxShadow: 'var(--shadow-sm)',
      }}>
        <h1 style={{ fontSize: 20, fontWeight: 600, color: 'var(--text-primary)', margin: '0 0 6px' }}>
          重置密码
        </h1>
        <p style={{ fontSize: 12, color: 'var(--text-muted)', margin: '0 0 24px' }}>
          请设置新密码（至少 8 位，含字母和数字）
        </p>

        {error && (
          <div style={{ padding: '10px 14px', borderRadius: 8, marginBottom: 16, fontSize: 13,
            background: 'rgba(239,68,68,0.12)', color: 'var(--accent-red)' }}>
            {error}
          </div>
        )}
        {message && (
          <div style={{ padding: '10px 14px', borderRadius: 8, marginBottom: 16, fontSize: 13,
            background: 'rgba(52,168,83,0.12)', color: 'var(--accent-green)' }}>
            {message}
          </div>
        )}

        {token && !message && (
          <form onSubmit={handleSubmit}>
            <input type="password" placeholder="新密码" value={pwd}
              onChange={e => setPwd(e.target.value)}
              style={inputStyle} />
            <input type="password" placeholder="确认新密码" value={confirm}
              onChange={e => setConfirm(e.target.value)}
              style={{ ...inputStyle, marginTop: 12 }} />
            <button type="submit" disabled={loading}
              style={{
                width: '100%', height: 44, borderRadius: 10, marginTop: 20,
                border: 'none', cursor: loading ? 'default' : 'pointer', fontSize: 15, fontWeight: 500,
                fontFamily: 'inherit',
                background: loading ? 'var(--border-color)' : 'var(--text-primary)',
                color: loading ? 'var(--text-muted)' : 'var(--bg-primary)',
              }}>
              {loading ? '提交中...' : '重置密码'}
            </button>
          </form>
        )}

        <div style={{ textAlign: 'center', marginTop: 20, fontSize: 13 }}>
          <Link to="/login" style={{ color: 'var(--accent-blue)', textDecoration: 'none' }}>
            返回登录
          </Link>
        </div>
      </div>
    </div>
  )
}

const inputStyle: React.CSSProperties = {
  width: '100%', height: 42, padding: '0 14px', boxSizing: 'border-box',
  borderRadius: 10, border: '1px solid var(--border-color)',
  background: 'var(--bg-tertiary)', color: 'var(--text-primary)',
  fontSize: 14, outline: 'none', fontFamily: 'inherit',
}
