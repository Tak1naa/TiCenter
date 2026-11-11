import { lazy, Suspense, useEffect, useState } from 'react'
import SourcesPanel from '../components/exam-bank/SourcesPanel'
import ChatPanel from '../components/chat/ChatPanel'
import DocumentWorkspace from '../components/exam-bank/DocumentWorkspace'
import { AppProvider, useAppContext } from '../hooks/useAppContext'
import { useTheme } from '../hooks/useTheme'
import type { Reference, WorkspaceDocument } from '../types'
import { BookOpen, FileText, MessageSquare, PanelLeft, Settings, SunMoon, X, NotebookPen, ClipboardList } from 'lucide-react'
import '../workspace.css'

const ProfilePanel = lazy(() => import('../components/profile/ProfilePanel'))
const QuizPanel = lazy(() => import('../components/quiz/QuizPanel'))

export default function NotebookPage() {
  const theme = useTheme()
  return <AppProvider themeMode={theme.mode} setThemeMode={theme.setMode}><Workspace toggleTheme={theme.toggleTheme} /></AppProvider>
}

function Workspace({ toggleTheme }: { toggleTheme: () => void }) {
  const { attachContext, chatFocusRequest } = useAppContext()
  const [tabs, setTabs] = useState<WorkspaceDocument[]>([])
  const [active, setActive] = useState<number>()
  const [target, setTarget] = useState<Reference>()
  const [leftOpen, setLeftOpen] = useState(true)
  const [chatOpen, setChatOpen] = useState(() => window.innerWidth > 820)
  const [view, setView] = useState<'files' | 'quiz' | 'notes' | 'settings'>('files')
  const [items, setItems] = useState<{ id: number; title: string; content: string; kind: string; referencesJson: string }[]>([])
  const [notesError, setNotesError] = useState('')
  const open = (doc: WorkspaceDocument, ref?: Reference) => {
    setTabs(prev => prev.some(t => t.fileId === doc.fileId) ? prev : [...prev, doc])
    setActive(doc.fileId); setTarget(ref); setView('files')
    if (window.innerWidth <= 820) { setLeftOpen(false); setChatOpen(false) }
  }
  useEffect(() => {
    const listener = (event: Event) => open((event as CustomEvent<WorkspaceDocument>).detail)
    window.addEventListener('workspace:open-current', listener)
    return () => window.removeEventListener('workspace:open-current', listener)
  }, [])
  useEffect(() => { if (chatFocusRequest) setChatOpen(true) }, [chatFocusRequest])
  useEffect(() => {
    if (view !== 'notes') return
    const abort = new AbortController()
    fetch('/api/workspace/items', { signal: abort.signal })
      .then(async r => { if (!r.ok) throw new Error('笔记加载失败，请稍后重试'); return r.json() })
      .then(data => { setItems(data.content || []); setNotesError('') })
      .catch(e => { if (e.name !== 'AbortError') setNotesError(e.message) })
    return () => abort.abort()
  }, [view])
  const doc = tabs.find(t => t.fileId === active)
  return <div className={`workspace-app ${leftOpen ? '' : 'library-hidden'} ${chatOpen ? '' : 'chat-hidden'}`}>
    <header className="workspace-header">
      <button aria-label="切换文件库" onClick={() => setLeftOpen(v => !v)}><PanelLeft size={18} /></button>
      <BookOpen size={18} /><span className="workspace-brand">题库工作台</span>
      <nav aria-label="工作区导航">
        <button aria-current={view === 'files' ? 'page' : undefined} onClick={() => setView('files')}><FileText size={15} />文件</button>
        <button aria-current={view === 'notes' ? 'page' : undefined} onClick={() => setView('notes')}><NotebookPen size={15} />我的整理</button>
        <button aria-current={view === 'quiz' ? 'page' : undefined} onClick={() => setView('quiz')}><ClipboardList size={15} />练习</button>
      </nav>
      <div className="workspace-header-actions">
        <button aria-label="切换主题" onClick={toggleTheme}><SunMoon size={17} /></button>
        <button aria-label="个人设置" aria-pressed={view === 'settings'} onClick={() => setView(v => v === 'settings' ? 'files' : 'settings')}><Settings size={17} /></button>
        <button aria-label="切换 AI 侧边栏" aria-pressed={chatOpen} onClick={() => setChatOpen(v => !v)}><MessageSquare size={17} /></button>
      </div>
    </header>
    <aside className="workspace-library" aria-label="文件库"><SourcesPanel onFileSelect={(fileId, fileName) => open({ fileId, fileName })} /></aside>
    <main className="workspace-main">
      <div className="document-tabs" role="tablist" aria-label="打开的文件">
        {tabs.map(tab => <div key={tab.fileId} className={`document-tab ${active === tab.fileId && view === 'files' ? 'active' : ''}`}>
          <button role="tab" aria-selected={active === tab.fileId && view === 'files'} onClick={() => open(tab)} title={tab.fileName}><FileText size={14} /><span>{tab.fileName}</span></button>
          <button aria-label={`关闭 ${tab.fileName}`} onClick={() => { setTabs(p => p.filter(t => t.fileId !== tab.fileId)); if (active === tab.fileId) { setActive(tabs.find(t => t.fileId !== tab.fileId)?.fileId); setTarget(undefined) } }}><X size={12} /></button>
        </div>)}
        {!tabs.length && <span className="tab-placeholder">文档工作区</span>}
      </div>
      {view === 'files' ? doc ? <DocumentWorkspace key={doc.fileId} document={doc} target={target} onAttach={(ref, focus) => { attachContext(ref, focus); setChatOpen(true) }} />
        : <div className="workspace-empty"><BookOpen size={36} strokeWidth={1.25} /><h1>从一道题开始</h1><p>打开左侧文件，选中文字或框选题目，<br />在右侧与 AI 一起理解、查证和整理。</p><span>添加到对话后，问题由你决定。</span></div>
        : <Suspense fallback={<p className="workspace-status">正在加载…</p>}>
          {view === 'settings' ? <ProfilePanel /> : view === 'quiz' ? <QuizPanel /> : <section className="workspace-notes">
            <h1>我的整理</h1><p>你确认保存的笔记与题目草稿，不改变原文件。</p>
            {notesError && <p role="alert">{notesError}</p>}
            {!notesError && !items.length && <p>还没有整理内容。可以在 AI 回复下方选择“保存整理”。</p>}
            {items.map(item => <article key={item.id}><span>{item.kind === 'question' ? '题目草稿' : '笔记'}</span><h2>{item.title}</h2><pre>{item.content}</pre>
              {(() => { try { return (JSON.parse(item.referencesJson) as Reference[]).map((ref, i) => <button key={i} onClick={() => ref.fileId && open({ fileId: ref.fileId, fileName: ref.fileName }, ref)}>{ref.fileName} · 第 {ref.page} 页</button>) } catch { return null } })()}
            </article>)}
          </section>}
        </Suspense>}
    </main>
    <aside className="workspace-chat" aria-label="AI 侧边对话">
      <div className="workspace-chat-heading"><MessageSquare size={16} /><strong>AI 助手</strong><span>围绕当前资料</span><button aria-label="关闭 AI 侧边栏" onClick={() => setChatOpen(false)}><X size={15} /></button></div>
      <ChatPanel onOpenFile={ref => ref.fileId && open({ fileId: ref.fileId, fileName: ref.fileName }, ref)} />
    </aside>
  </div>
}
