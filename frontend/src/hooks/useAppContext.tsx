import { createContext, useContext, useState, type ReactNode } from 'react'
import type { ThemeMode } from '../hooks/useTheme'
import type { ContextAttachment } from '../types'

interface AppContextType {
  selectedFileIds: Set<number>
  toggleFile: (fileId: number) => void
  setSelectedFileIds: (files: Set<number>) => void
  themeMode: ThemeMode
  setThemeMode: (mode: ThemeMode) => void
  pendingContexts: ContextAttachment[]
  attachContext: (context: ContextAttachment, focus?: boolean) => void
  removeContext: (index: number) => void
  clearContexts: () => void
  chatFocusRequest: number
}

const AppContext = createContext<AppContextType>(null!)

export function AppProvider({ children, themeMode, setThemeMode }: {
  children: ReactNode
  themeMode: ThemeMode
  setThemeMode: (mode: ThemeMode) => void
}) {
  const [selectedFileIds, setSelectedFileIds] = useState<Set<number>>(new Set())
  const [pendingContexts, setPendingContexts] = useState<ContextAttachment[]>([])
  const [chatFocusRequest, setChatFocusRequest] = useState(0)
  const attachContext = (context: ContextAttachment, focus = false) => {
    setPendingContexts(prev => prev.some(c => JSON.stringify(c) === JSON.stringify(context)) ? prev : [...prev, context].slice(-8))
    if (focus) setChatFocusRequest(n => n + 1)
  }

  const toggleFile = (fileId: number) => {
    setSelectedFileIds(prev => {
      const next = new Set(prev)
      next.has(fileId) ? next.delete(fileId) : next.add(fileId)
      return next
    })
  }

  return (
    <AppContext.Provider value={{ selectedFileIds, toggleFile, setSelectedFileIds, themeMode, setThemeMode,
      pendingContexts, attachContext, removeContext: index => setPendingContexts(p => p.filter((_, i) => i !== index)),
      clearContexts: () => setPendingContexts([]), chatFocusRequest }}>
      {children}
    </AppContext.Provider>
  )
}

export function useAppContext() {
  return useContext(AppContext)
}
