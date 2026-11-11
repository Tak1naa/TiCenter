import { useState, useEffect } from 'react'
import { Save } from 'lucide-react'

export default function AdminSettings() {
  const [configs, setConfigs] = useState<Record<string, string>>({})
  const [msg, setMsg] = useState('')
  const [error, setError] = useState(false)
  const token = localStorage.getItem('admin-token') || ''

  useEffect(() => {
    fetch('/api/admin/config', { headers: { 'X-Admin-Token': token } })
      .then(r => r.json()).then(d => {
        const map: Record<string, string> = {}
        ;(d.configs || []).forEach((c: any) => { map[c.key] = c.value })
        setConfigs(map)
      }).catch(() => {})
  }, [token])

  const saveConfig = async (key: string, value: string) => {
    setMsg('')
    try {
      const resp = await fetch(`/api/admin/config/${key}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', 'X-Admin-Token': token },
        body: JSON.stringify({ value }),
      })
      const data = await resp.json()
      if (!resp.ok) { setMsg(data.error || '保存失败'); setError(true); return }
      setMsg('已保存'); setError(false)
    } catch { setMsg('网络错误'); setError(true) }
  }

  const inputStyle: React.CSSProperties = {
    width: '100%', height: 34, padding: '0 10px',
    borderRadius: 6, border: '1px solid var(--border-color)',
    background: 'var(--bg-tertiary)', color: 'var(--text-primary)',
    fontSize: 13, outline: 'none', fontFamily: 'inherit', boxSizing: 'border-box',
  }

  const settings = [
    { key: 'site_name', label: '网站名称', defaultValue: 'AI 智能题库', desc: '管理后台和首页显示的标题' },
    { key: 'exam_bank_root', label: '题库存储路径', defaultValue: '/home/takina/AiQuestionBank/data/exam-bank', desc: '文档扫描和存储的根目录' },
    { key: 'max_upload_size_mb', label: '最大上传大小 (MB)', defaultValue: '100', desc: '单次上传文件的大小上限' },
    { key: 'smtp_host', label: 'SMTP 服务器（告警）', defaultValue: '', desc: '告警邮件服务器地址（如 smtp.qq.com）' },
    { key: 'smtp_port', label: 'SMTP 端口（告警）', defaultValue: '465', desc: 'SSL 端口（QQ 邮箱 465）' },
    { key: 'smtp_user', label: 'SMTP 账号（告警）', defaultValue: '', desc: '告警发件邮箱账号' },
    { key: 'smtp_pass', label: 'SMTP 授权码（告警）', defaultValue: '', desc: '邮箱授权码（非登录密码）' },
    { key: 'student_smtp_host', label: 'SMTP 服务器（学生邮件）', defaultValue: '', desc: '找回密码/注册验证邮件服务器' },
    { key: 'student_smtp_port', label: 'SMTP 端口（学生邮件）', defaultValue: '465', desc: 'SSL 端口' },
    { key: 'student_smtp_user', label: 'SMTP 账号（学生邮件）', defaultValue: '', desc: '学生邮件发件账号' },
    { key: 'student_smtp_pass', label: 'SMTP 授权码（学生邮件）', defaultValue: '', desc: '邮箱授权码（非登录密码）' },
    { key: 'alarm_emails', label: '告警收件人', defaultValue: '', desc: '监控告警接收邮箱（逗号分隔多个）' },
    { key: 'alarm_enabled', label: '邮件告警开关', defaultValue: 'false', type: 'boolean', desc: '开启后服务异常通过邮件告警；关闭则仅写日志' },
  ]

  return (
    <div style={{ padding: '28px 32px', maxWidth: 700 }}>
      <div style={{ marginBottom: 24 }}>
        <h1 style={{ fontSize: 20, fontWeight: 600, color: 'var(--text-primary)', margin: '0 0 4px' }}>
          系统设置
        </h1>
        <p style={{ fontSize: 12, color: 'var(--text-muted)', margin: 0 }}>
          管理 AI 智能题库的系统配置参数
        </p>
      </div>

      {msg && (
        <div style={{
          padding: '8px 14px', borderRadius: 8, marginBottom: 16, fontSize: 12,
          background: error ? 'var(--accent-red-light, #fef2f2)' : 'var(--accent-green-light, #f0fdf4)',
          color: error ? 'var(--accent-red)' : 'var(--accent-green)',
        }}>
          {msg}
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        {settings.map(s => (
          <div key={s.key} style={{
            padding: 14, borderRadius: 10, background: 'var(--bg-secondary)',
            border: '1px solid var(--border-color)',
          }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 8 }}>
              <div>
                <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text-primary)' }}>
                  {s.label}
                </div>
                <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 2 }}>
                  {s.desc}
                </div>
              </div>
            </div>
            <div style={{ display: 'flex', gap: 8 }}>
              {s.type === 'boolean' ? (
                <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={(configs[s.key] || s.defaultValue) === 'true'}
                    onChange={e => saveConfig(s.key, String(e.target.checked))}
                    style={{ width: 18, height: 18, accentColor: 'var(--accent-blue)', cursor: 'pointer' }}
                  />
                  <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
                    {(configs[s.key] || s.defaultValue) === 'true' ? '已开启' : '已关闭'}
                  </span>
                </label>
              ) : (
                <>
                  <input style={inputStyle}
                    defaultValue={configs[s.key] || s.defaultValue}
                    onBlur={e => { if (e.target.value !== '' && e.target.value !== (configs[s.key] || '')) saveConfig(s.key, e.target.value) }}
                    onKeyDown={e => { if (e.key === 'Enter' && (e.target as HTMLInputElement).value !== '') saveConfig(s.key, (e.target as HTMLInputElement).value) }}
                  />
                  <button onClick={(e) => {
                    const input = (e.currentTarget.parentElement?.querySelector('input') as HTMLInputElement)
                    if (input && input.value !== '') saveConfig(s.key, input.value)
                  }}
                    style={{
                      height: 34, width: 34, borderRadius: 6, border: '1px solid var(--border-color)',
                      background: 'var(--bg-tertiary)', color: 'var(--text-primary)',
                      cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center',
                      flexShrink: 0,
                    }}>
                    <Save size={14} />
                  </button>
                </>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
