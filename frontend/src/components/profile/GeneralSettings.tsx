import type { ThemeMode } from '../../hooks/useTheme'

interface Props {
  themeMode: ThemeMode
  onThemeChange: (mode: ThemeMode) => void
}

export default function GeneralSettings({ themeMode, onThemeChange }: Props) {
  const themes: { mode: ThemeMode; label: string; desc: string }[] = [
    { mode: 'light', label: '浅色', desc: '白色背景，适合明亮环境' },
    { mode: 'dark', label: '深色', desc: '黑色背景，减少眼部疲劳' },
    { mode: 'auto', label: '跟随系统', desc: '根据日出日落和系统设置自动切换' },
  ]

  return (
    <div>
      <div style={{
        fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 16,
      }}>
        主题设置
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {themes.map(t => (
          <label key={t.mode} style={{
            display: 'flex', alignItems: 'flex-start', gap: 12,
            padding: '12px 14px', borderRadius: 10, cursor: 'pointer',
            border: `1.5px solid ${themeMode === t.mode ? 'var(--accent-blue)' : 'var(--border-color)'}`,
            background: themeMode === t.mode ? 'var(--bg-hover)' : 'var(--bg-secondary)',
          }}>
            <input type="radio" name="theme" checked={themeMode === t.mode}
              onChange={() => onThemeChange(t.mode)}
              style={{ marginTop: 2, accentColor: 'var(--accent-blue)' }} />
            <div>
              <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text-primary)' }}>{t.label}</div>
              <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 2 }}>{t.desc}</div>
            </div>
          </label>
        ))}
      </div>
    </div>
  )
}
