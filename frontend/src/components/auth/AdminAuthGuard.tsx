import { Navigate, Outlet } from 'react-router-dom'

export default function AdminAuthGuard() {
  const token = localStorage.getItem('admin-token')
  if (!token) return <Navigate to="/admin/login" replace />
  return <Outlet />
}
