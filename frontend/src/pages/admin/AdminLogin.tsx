import { useState } from 'react'
import { useNavigate } from 'react-router-dom'

export default function AdminLogin() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()

  const handleLogin = async () => {
    if (!username || !password) return
    setLoading(true); setError('')
    try {
      const r = await fetch('/api/admin/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      })
      const d = await r.json()
      if (!r.ok) { setError(d.error || '登录失败'); return }
      // HttpOnly cookie carries the real session; localStorage stores only a non-secret UI marker.
      localStorage.setItem('admin-token', 'cookie')
      navigate('/admin/dashboard')
    } catch { setError('网络错误') }
    finally { setLoading(false) }
  }

  return (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      height: '100vh', background: 'var(--bg-primary)',
    }}>
      <div style={{
        width: 360, padding: 32, borderRadius: 12,
        background: 'var(--bg-secondary)',
        border: '1px solid var(--border-color)',
        boxShadow: 'var(--shadow-md)',
      }}>
        <h2 style={{ fontSize: 20, fontWeight: 500, color: 'var(--text-primary)', marginBottom: 4, textAlign: 'center' }}>
          Exam Bank Admin
        </h2>
        <p style={{ fontSize: 12, color: 'var(--text-muted)', textAlign: 'center', marginBottom: 24 }}>
          管理控制台
        </p>

        {error && (
          <div style={{ padding: '8px 12px', borderRadius: 6, background: 'var(--accent-red-light)', color: 'var(--accent-red)', fontSize: 12, marginBottom: 16 }}>
            {error}
          </div>
        )}

        <input value={username} onChange={e => setUsername(e.target.value)}
          onKeyDown={e => e.key === 'Enter' && handleLogin()}
          placeholder="用户名"
          style={inputStyle} />

        <input type="password" value={password} onChange={e => setPassword(e.target.value)}
          onKeyDown={e => e.key === 'Enter' && handleLogin()}
          placeholder="密码"
          style={inputStyle} />

        <button onClick={handleLogin} disabled={loading || !username || !password}
          style={{
            width: '100%', height: 40, border: 'none', borderRadius: 8,
            background: (username && password) ? 'var(--accent-blue)' : 'var(--border-color)',
            color: (username && password) ? '#fff' : 'var(--text-muted)',
            cursor: (username && password) ? 'pointer' : 'default',
            fontSize: 14, fontWeight: 500, marginTop: 8,
            fontFamily: 'inherit',
          }}>
          {loading ? '登录中...' : '登录'}
        </button>
      </div>
    </div>
  )
}

const inputStyle: React.CSSProperties = {
  width: '100%', height: 40, padding: '0 14px', borderRadius: 8,
  background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)',
  color: 'var(--text-primary)', fontSize: 14, outline: 'none',
  marginBottom: 12, fontFamily: 'inherit',
}
