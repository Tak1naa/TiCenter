import { useState } from 'react'
import { Settings, User, Database, ScrollText, Bot } from 'lucide-react'
import { useAppContext } from '../../hooks/useAppContext'
import GeneralSettings from './GeneralSettings'
import AccountSettings from './AccountSettings'
import DataSettings from './DataSettings'
import TermsSettings from './TermsSettings'
import ModelSettings from './ModelSettings'

type ProfileTab = 'general' | 'account' | 'data' | 'terms' | 'model'

interface Props {}

const tabs: { key: ProfileTab; label: string; icon: React.ReactNode }[] = [
  { key: 'general', label: '通用设置', icon: <Settings size={15} /> },
  { key: 'account', label: '账号管理', icon: <User size={15} /> },
  { key: 'data', label: '数据管理', icon: <Database size={15} /> },
  { key: 'terms', label: '服务协议', icon: <ScrollText size={15} /> },
  { key: 'model', label: '模型选择', icon: <Bot size={15} /> },
]

export default function ProfilePanel(_props: Props) {
  const { themeMode, setThemeMode } = useAppContext()
  const [activeTab, setActiveTab] = useState<ProfileTab>('general')

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      <div style={{ padding: '16px 16px 12px', borderBottom: '1px solid var(--border-color)' }}>
        <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text-primary)' }}>
          个人中心
        </div>
      </div>

      {/* Tab navigation */}
      <div style={{ padding: '4px 8px', borderBottom: '1px solid var(--border-color)' }}>
        {tabs.map(t => (
          <button key={t.key} onClick={() => setActiveTab(t.key)} style={{
            width: '100%', display: 'flex', alignItems: 'center', gap: 10,
            height: 36, padding: '0 10px', borderRadius: 8,
            border: 'none', cursor: 'pointer', fontSize: 13,
            fontFamily: 'inherit',
            background: activeTab === t.key ? 'var(--bg-hover)' : 'transparent',
            color: activeTab === t.key ? 'var(--text-primary)' : 'var(--text-secondary)',
            marginBottom: 2,
          }}>
            {t.icon}
            {t.label}
          </button>
        ))}
      </div>

      {/* Content */}
      <div style={{ flex: 1, overflow: 'auto', padding: '12px 14px' }}>
        {activeTab === 'general' && <GeneralSettings themeMode={themeMode} onThemeChange={setThemeMode} />}
        {activeTab === 'account' && <AccountSettings />}
        {activeTab === 'data' && <DataSettings />}
        {activeTab === 'terms' && <TermsSettings />}
        {activeTab === 'model' && <ModelSettings />}
      </div>
    </div>
  )
}
