import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { lazy, Suspense, useEffect } from 'react'
import AuthGuard from './components/auth/AuthGuard'
import AdminAuthGuard from './components/auth/AdminAuthGuard'

const NotebookPage = lazy(() => import('./pages/NotebookPage'))
const StudentLogin = lazy(() => import('./pages/StudentLogin'))
const ResetPassword = lazy(() => import('./pages/ResetPassword'))
const AdminLogin = lazy(() => import('./pages/admin/AdminLogin'))
const AdminLayout = lazy(() => import('./pages/admin/AdminLayout'))
const AdminDashboard = lazy(() => import('./pages/admin/AdminDashboard'))
const AdminMonitor = lazy(() => import('./pages/admin/AdminMonitor'))
const AdminUsers = lazy(() => import('./pages/admin/AdminUsers'))
const AdminChats = lazy(() => import('./pages/admin/AdminChats'))
const AdminFiles = lazy(() => import('./pages/admin/AdminFiles'))
const AdminLogs = lazy(() => import('./pages/admin/AdminLogs'))
const AdminSettings = lazy(() => import('./pages/admin/AdminSettings'))

function App() {
  // 刷新/关闭提示：未保存的输入可能丢失
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      const input = (document.querySelector('input[type="text"], textarea') as HTMLInputElement | null)
      if (input && input.value.trim()) {
        e.preventDefault()
        e.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [])

  return (
    <BrowserRouter>
      <Suspense fallback={<div style={{ padding: 24, color: 'var(--text-secondary)' }}>正在加载…</div>}>
      <Routes>
        <Route path="/login" element={<StudentLogin />} />
        <Route path="/reset-password" element={<ResetPassword />} />
        <Route element={<AuthGuard />}>
          <Route path="/" element={<NotebookPage />} />
        </Route>
        <Route path="/admin/login" element={<AdminLogin />} />
        <Route element={<AdminAuthGuard />}>
          <Route element={<AdminLayout />}>
            <Route path="/admin" element={<Navigate to="/admin/dashboard" replace />} />
            <Route path="/admin/dashboard" element={<AdminDashboard />} />
            <Route path="/admin/monitor" element={<AdminMonitor />} />
            <Route path="/admin/users" element={<AdminUsers />} />
            <Route path="/admin/chats" element={<AdminChats />} />
            <Route path="/admin/files" element={<AdminFiles />} />
            <Route path="/admin/logs" element={<AdminLogs />} />
            <Route path="/admin/settings" element={<AdminSettings />} />
          </Route>
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
      </Suspense>
    </BrowserRouter>
  )
}

export default App
