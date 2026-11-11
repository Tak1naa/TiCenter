import { useState, useEffect, useCallback } from 'react'
import { Activity, Server, AlertTriangle, RefreshCw } from 'lucide-react'

interface ServiceStatus { name: string; url: string; up: boolean; latencyMs: number }
interface MonitorStats { chatFailures: number; parseFailures: number; loginFailures: number }
interface MonitorEvent { type: string; message: string; time: string }

const EVENT_LABELS: Record<string, string> = { chat: '聊天', login: '登录', svc: '服务', parse: '解析' }

export default function AdminMonitor() {
  const token = localStorage.getItem('admin-token') || ''
  const [services, setServices] = useState<ServiceStatus[]>([])
  const [stats, setStats] = useState<MonitorStats>({ chatFailures: 0, parseFailures: 0, loginFailures: 0 })
  const [events, setEvents] = useState<MonitorEvent[]>([])
  const [lastUpdate, setLastUpdate] = useState('')
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    try {
      const r = await fetch('/api/admin/monitor/status', { headers: { 'X-Admin-Token': token } })
      if (!r.ok) throw new Error(`HTTP ${r.status}`)
      const d = await r.json()
      setServices(d.services || [])
      setStats(d.stats || { chatFailures: 0, parseFailures: 0, loginFailures: 0 })
      setEvents(d.recentEvents || [])
      setLastUpdate(new Date().toLocaleTimeString())
      setError('')
    } catch (e: any) {
      setError(e?.message || '加载失败')
    }
  }, [token])

  useEffect(() => {
    load()
    const timer = setInterval(load, 15000) // 15s 轮询
    return () => clearInterval(timer)
  }, [load])

  const downCount = services.filter(s => !s.up).length
  const statCards = [
    { label: '聊天失败', value: stats.chatFailures, color: stats.chatFailures > 0 ? 'var(--accent-red)' : 'var(--text-secondary)' },
    { label: '解析失败', value: stats.parseFailures, color: stats.parseFailures > 0 ? 'var(--accent-red)' : 'var(--text-secondary)' },
    { label: '登录失败', value: stats.loginFailures, color: stats.loginFailures > 0 ? '#f9ab00' : 'var(--text-secondary)' },
    { label: '服务离线', value: downCount, color: downCount > 0 ? 'var(--accent-red)' : 'var(--text-secondary)' },
  ]

  return (
    <div style={{ padding: 24 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 20 }}>
        <Activity size={20} style={{ color: 'var(--accent-blue)' }} />
        <h2 style={{ fontSize: 18, fontWeight: 600, color: 'var(--text-primary)', margin: 0 }}>监控中心</h2>
        <div style={{ flex: 1 }} />
        <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>{lastUpdate && `更新于 ${lastUpdate}`}</span>
        <button onClick={load} style={iconBtn} title="刷新"><RefreshCw size={15} /></button>
      </div>

      {error && (
        <div style={{ padding: 12, borderRadius: 8, background: 'rgba(239,68,68,0.12)', color: 'var(--accent-red)', fontSize: 13, marginBottom: 16 }}>
          {error}
        </div>
      )}

      {/* 统计卡 */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12, marginBottom: 24 }}>
        {statCards.map(c => (
          <div key={c.label} style={{
            padding: '16px 20px', borderRadius: 10, background: 'var(--bg-secondary)',
            border: '1px solid var(--border-color)',
          }}>
            <div style={{ fontSize: 24, fontWeight: 700, color: c.color }}>{c.value}</div>
            <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>{c.label}</div>
          </div>
        ))}
      </div>

      {/* 服务状态网格 */}
      <h3 style={{ fontSize: 14, fontWeight: 600, color: 'var(--text-primary)', margin: '0 0 12px' }}>服务状态</h3>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 12, marginBottom: 24 }}>
        {services.map(s => (
          <div key={s.name} style={{
            padding: '14px 16px', borderRadius: 10,
            background: 'var(--bg-secondary)', border: '1px solid var(--border-color)',
            display: 'flex', alignItems: 'center', gap: 10,
          }}>
            <span style={{
              width: 10, height: 10, borderRadius: 5, flexShrink: 0,
              background: s.up ? '#34a853' : 'var(--accent-red)',
            }} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text-primary)' }}>{s.name}</div>
              <div style={{ fontSize: 11, color: 'var(--text-muted)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {s.up ? `正常 · ${s.latencyMs}ms` : '离线'}
              </div>
            </div>
            <Server size={14} style={{ color: s.up ? '#34a853' : 'var(--accent-red)', flexShrink: 0 }} />
          </div>
        ))}
      </div>

      {/* 最近事件 */}
      <h3 style={{ fontSize: 14, fontWeight: 600, color: 'var(--text-primary)', margin: '0 0 12px' }}>
        最近事件 <span style={{ fontSize: 11, color: 'var(--text-muted)', fontWeight: 400 }}>（最多 200 条，重启清零）</span>
      </h3>
      <div style={{
        borderRadius: 10, border: '1px solid var(--border-color)',
        overflow: 'hidden', background: 'var(--bg-secondary)',
      }}>
        {events.length === 0 ? (
          <div style={{ padding: 20, textAlign: 'center', fontSize: 13, color: 'var(--text-muted)' }}>
            <AlertTriangle size={18} style={{ marginBottom: 6, opacity: 0.5 }} />
            <div>暂无事件</div>
          </div>
        ) : (
          <div style={{ maxHeight: 300, overflow: 'auto' }}>
            {events.slice().reverse().map((e, i) => (
              <div key={i} style={{
                display: 'flex', alignItems: 'center', gap: 10,
                padding: '8px 14px', fontSize: 12.5,
                borderBottom: i < events.length - 1 ? '1px solid var(--border-color)' : 'none',
              }}>
                <span style={{
                  padding: '1px 8px', borderRadius: 8, flexShrink: 0, fontSize: 11,
                  background: 'rgba(239,68,68,0.12)', color: 'var(--accent-red)',
                }}>
                  {EVENT_LABELS[e.type] || e.type}
                </span>
                <span style={{ flex: 1, color: 'var(--text-primary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {e.message}
                </span>
                <span style={{ fontSize: 11, color: 'var(--text-muted)', flexShrink: 0 }}>
                  {new Date(e.time).toLocaleTimeString()}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

const iconBtn: React.CSSProperties = {
  width: 30, height: 30, borderRadius: 15, border: '1px solid var(--border-color)',
  background: 'var(--bg-secondary)', color: 'var(--text-secondary)', cursor: 'pointer',
  display: 'flex', alignItems: 'center', justifyContent: 'center',
}
