import { useState, useEffect, useCallback } from 'react'

export type ThemeMode = 'light' | 'dark' | 'auto'
type ActiveTheme = 'light' | 'dark'

function getSystemPreference(): ActiveTheme {
  if (window.matchMedia?.('(prefers-color-scheme: dark)').matches) return 'dark'
  return 'light'
}

function getSunriseSunsetTheme(): ActiveTheme {
  const hours = new Date().getHours()
  return (hours >= 19 || hours < 6) ? 'dark' : 'light'
}

function computeActiveTheme(mode: ThemeMode): ActiveTheme {
  if (mode === 'auto') return getSunriseSunsetTheme()
  return mode
}

function applyTheme(t: ActiveTheme) {
  document.documentElement.setAttribute('data-theme', t)
  document.documentElement.style.colorScheme = t
}

function getInitialMode(): ThemeMode {
  const stored = localStorage.getItem('theme-mode')
  if (stored === 'light' || stored === 'dark' || stored === 'auto') return stored
  // Migrate old 'theme' key
  const old = localStorage.getItem('theme')
  if (old === 'light' || old === 'dark') return old
  return 'auto'
}

export function useTheme() {
  const [mode, setModeState] = useState<ThemeMode>(getInitialMode)
  const [active, setActive] = useState<ActiveTheme>(() => computeActiveTheme(getInitialMode()))

  const setMode = useCallback((m: ThemeMode) => {
    setModeState(m)
    localStorage.setItem('theme-mode', m)
    const resolved = computeActiveTheme(m)
    setActive(resolved)
    applyTheme(resolved)
  }, [])

  // Toggle: light → dark → auto → light
  const toggleTheme = useCallback(() => {
    setModeState(prev => {
      const next = prev === 'light' ? 'dark' : prev === 'dark' ? 'auto' : 'light'
      localStorage.setItem('theme-mode', next)
      const resolved = computeActiveTheme(next)
      setActive(resolved)
      applyTheme(resolved)
      return next
    })
  }, [])

  // Apply on mount
  useEffect(() => {
    applyTheme(active)
  }, [active])

  // Listen for system preference changes (only relevant in auto mode)
  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const handler = () => {
      if (mode === 'auto') {
        const t = getSunriseSunsetTheme()
        setActive(t)
        applyTheme(t)
      }
    }
    mq.addEventListener('change', handler)
    return () => mq.removeEventListener('change', handler)
  }, [mode])

  // Sunrise/sunset timer (only in auto mode)
  useEffect(() => {
    if (mode !== 'auto') return
    const interval = setInterval(() => {
      const t = getSunriseSunsetTheme()
      setActive(t)
      applyTheme(t)
    }, 60_000)
    return () => clearInterval(interval)
  }, [mode])

  const modeLabel = mode === 'light' ? '浅色' : mode === 'dark' ? '深色' : '自动'

  return { mode, active, setMode, toggleTheme, modeLabel }
}

// Re-export without React dependency for GeneralSettings
export { getSystemPreference, getSunriseSunsetTheme, computeActiveTheme }
