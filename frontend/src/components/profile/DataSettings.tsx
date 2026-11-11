import { useState, useEffect } from 'react'
import { Trash2, AlertTriangle } from 'lucide-react'

export default function DataSettings() {
  const [chatCount, setChatCount] = useState<number | null>(null)
  const [loading, setLoading] = useState(false)
  const [msg, setMsg] = useState('')
  const [error, setError] = useState(false)
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)
  const [showAccountConfirm, setShowAccountConfirm] = useState(false)
  const [deletePwd, setDeletePwd] = useState('')

  useEffect(() => {
    fetch('/api/student/data-stats', {
      headers: { 'X-Student-Token': localStorage.getItem('student-token') || '' },
    }).then(r => r.json()).then(d => setChatCount(d.chatCount)).catch(() => {})
  }, [])

  const handleDeleteChats = async () => {
    setLoading(true)
    try {
      const resp = await fetch('/api/student/delete-chats', {
        method: 'DELETE',
        headers: { 'X-Student-Token': localStorage.getItem('student-token') || '' },
      })
      const data = await resp.json()
      setMsg(data.message || '已清空'); setError(false)
      setChatCount(0)
    } catch { setMsg('网络错误'); setError(true) }
    finally { setLoading(false); setShowDeleteConfirm(false) }
  }

  const handleDeleteAccount = async () => {
    if (!deletePwd) { setMsg('请输入密码确认'); setError(true); return }
    setLoading(true)
    try {
      const resp = await fetch('/api/student/delete-account', {
        method: 'DELETE',
        headers: {
          'Content-Type': 'application/json',
          'X-Student-Token': localStorage.getItem('student-token') || '',
        },
        body: JSON.stringify({ password: deletePwd }),
      })
      const data = await resp.json()
      if (!resp.ok) { setMsg(data.error || '删除失败'); setError(true); return }
      localStorage.removeItem('student-token')
      localStorage.removeItem('student-info')
      window.location.href = '/login'
    } catch { setMsg('网络错误'); setError(true) }
    finally { setLoading(false) }
  }

  return (
    <div>
      <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 16 }}>
        数据管理
      </div>

      <div style={{
        padding: 12, borderRadius: 8, background: 'var(--bg-tertiary)',
        marginBottom: 20, fontSize: 13, color: 'var(--text-secondary)',
      }}>
        聊天记录数：<span style={{ color: 'var(--text-primary)', fontWeight: 500 }}>
          {chatCount !== null ? chatCount : '...'}
        </span>
      </div>

      {msg && (
        <div style={{
          padding: '8px 12px', borderRadius: 6, marginBottom: 12, fontSize: 12,
          background: error ? 'var(--accent-red-light)' : 'var(--accent-green-light)',
          color: error ? 'var(--accent-red)' : 'var(--accent-green)',
        }}>
          {msg}
        </div>
      )}

      {/* Delete chats */}
      {showDeleteConfirm ? (
        <div style={{
          padding: 14, borderRadius: 10, background: 'var(--bg-tertiary)',
          border: '1.5px solid var(--accent-yellow, #f59e0b)', marginBottom: 14,
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
            <AlertTriangle size={16} style={{ color: 'var(--accent-yellow, #f59e0b)' }} />
            <span style={{ fontSize: 13, fontWeight: 500, color: 'var(--text-primary)' }}>
              确认清空所有聊天记录？
            </span>
          </div>
          <p style={{ fontSize: 12, color: 'var(--text-muted)', margin: '0 0 10px' }}>
            此操作不可撤销，所有对话历史将被永久删除。
          </p>
          <div style={{ display: 'flex', gap: 8 }}>
            <button onClick={handleDeleteChats} disabled={loading} style={{
              height: 32, padding: '0 16px', borderRadius: 6, border: 'none',
              background: 'var(--accent-yellow, #f59e0b)', color: '#fff',
              cursor: 'pointer', fontSize: 13, fontWeight: 500, fontFamily: 'inherit',
            }}>
              {loading ? '删除中...' : '确认清空'}
            </button>
            <button onClick={() => setShowDeleteConfirm(false)} style={{
              height: 32, padding: '0 16px', borderRadius: 6,
              border: '1px solid var(--border-color)', background: 'var(--bg-secondary)',
              color: 'var(--text-secondary)', cursor: 'pointer', fontSize: 13, fontFamily: 'inherit',
            }}>
              取消
            </button>
          </div>
        </div>
      ) : (
        <button onClick={() => { setMsg(''); setShowDeleteConfirm(true) }} style={{
          width: '100%', height: 38, borderRadius: 8, marginBottom: 14,
          border: '1px solid var(--border-color)', background: 'var(--bg-secondary)',
          color: 'var(--text-primary)', cursor: 'pointer', display: 'flex',
          alignItems: 'center', justifyContent: 'center', gap: 6,
          fontSize: 13, fontFamily: 'inherit',
        }}>
          <Trash2 size={14} />
          清空聊天记录
        </button>
      )}

      {/* Delete account */}
      {showAccountConfirm ? (
        <div style={{
          padding: 14, borderRadius: 10, background: 'var(--bg-tertiary)',
          border: '1.5px solid var(--accent-red)', marginBottom: 14,
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
            <AlertTriangle size={16} style={{ color: 'var(--accent-red)' }} />
            <span style={{ fontSize: 13, fontWeight: 500, color: 'var(--text-primary)' }}>
              确认删除账号？
            </span>
          </div>
          <p style={{ fontSize: 12, color: 'var(--text-muted)', margin: '0 0 10px' }}>
            账号及所有数据将永久删除，不可恢复。
          </p>
          <input style={{
            width: '100%', height: 34, padding: '0 10px', borderRadius: 6,
            border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)',
            color: 'var(--text-primary)', fontSize: 13, outline: 'none',
            fontFamily: 'inherit', boxSizing: 'border-box', marginBottom: 10,
          }} type="password" value={deletePwd} onChange={e => setDeletePwd(e.target.value)}
            placeholder="输入密码确认" />
          <div style={{ display: 'flex', gap: 8 }}>
            <button onClick={handleDeleteAccount} disabled={loading} style={{
              height: 32, padding: '0 16px', borderRadius: 6, border: 'none',
              background: 'var(--accent-red)', color: '#fff',
              cursor: 'pointer', fontSize: 13, fontWeight: 500, fontFamily: 'inherit',
            }}>
              {loading ? '删除中...' : '确认删除账号'}
            </button>
            <button onClick={() => { setShowAccountConfirm(false); setDeletePwd('') }} style={{
              height: 32, padding: '0 16px', borderRadius: 6,
              border: '1px solid var(--border-color)', background: 'var(--bg-secondary)',
              color: 'var(--text-secondary)', cursor: 'pointer', fontSize: 13, fontFamily: 'inherit',
            }}>
              取消
            </button>
          </div>
        </div>
      ) : (
        <button onClick={() => { setMsg(''); setShowAccountConfirm(true) }} style={{
          width: '100%', height: 38, borderRadius: 8,
          border: '1.5px solid var(--accent-red)', background: 'transparent',
          color: 'var(--accent-red)', cursor: 'pointer',
          fontSize: 13, fontWeight: 500, fontFamily: 'inherit',
        }}>
          删除账号
        </button>
      )}
    </div>
  )
}
