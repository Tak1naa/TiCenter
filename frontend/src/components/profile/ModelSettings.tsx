import { useState, useEffect } from 'react'
import type { AIProvider, AIConfig } from '../../types'
import { Check, X, Loader } from 'lucide-react'
import { loadAIConfig, saveAIConfig } from '../../lib/aiConfig'

const DEFAULT_CONFIG: AIConfig = {
  providerId: 'custom',
  apiKey: '',
  baseUrl: '',
  model: '',
  temperature: 0.3,
  maxTokens: 8192,
}

function loadConfig(): AIConfig {
  return loadAIConfig(DEFAULT_CONFIG)
}

export default function ModelSettings() {
  const [providers, setProviders] = useState<AIProvider[]>([])
  const [config, setConfig] = useState<AIConfig>(loadConfig)
  const [showAdvanced, setShowAdvanced] = useState(false)
  const [testStatus, setTestStatus] = useState<'idle' | 'testing' | 'ok' | 'fail'>('idle')
  const [testLatency, setTestLatency] = useState(0)
  const [testError, setTestError] = useState('')
  const [search, setSearch] = useState('')

  useEffect(() => {
    fetch('/api/llm/providers').then(r => r.json()).then(data => {
      // API returns snake_case; normalize to camelCase
      const list: AIProvider[] = (Array.isArray(data) ? data : Object.values(data)).map((p: any) => ({
        id: p.id,
        name: p.name || p.id,
        apiType: p.api_type || p.apiType || 'openai',
        defaultBaseUrl: p.default_base_url || p.defaultBaseUrl || '',
        defaultModel: p.default_model || p.defaultModel || '',
        knownModels: p.known_models || p.knownModels || [],
        reasoningModels: p.reasoning_models || p.reasoningModels || [],
        multimodalModels: p.multimodal_models || p.multimodalModels || [],
      }))
      setProviders(list)
    }).catch(() => {})
  }, [])

  const currentProvider = providers.find(p => p.id === config.providerId)
  const serverManagedKey = /^zhipu(_en)?$/.test(config.providerId || '')

  // 判断当前模型是否为思考型：命中 preset 的 reasoning_models，或按模型名启发式
  const isReasoningModel = () => {
    const model = (config.model || '').toLowerCase()
    if (!model) return false
    if (currentProvider?.reasoningModels?.length) {
      const hit = currentProvider.reasoningModels.some(m => model.includes(m.toLowerCase()))
      if (hit) return true
    }
    return /(thinking|reasoning|r1|flash)/.test(model)
  }

  // 判断当前模型是否多模态（能看图）：命中 preset 的 multimodal_models，或按模型名启发式
  const isMultimodalModel = () => {
    const model = (config.model || '').toLowerCase()
    if (!model) return false
    if (currentProvider?.multimodalModels?.length) {
      const hit = currentProvider.multimodalModels.some(m => model.includes(m.toLowerCase()))
      if (hit) return true
    }
    return /(gemini|gpt-4o|vision|glm-[\d.]+v|vl|qwen2.*vl|llama-4)/.test(model)
  }

  const recommendedMaxTokens = isReasoningModel() ? 16384 : 8192

  const selectProvider = (id: string) => {
    if (id === 'custom') {
      setConfig(c => ({ ...c, providerId: id }))
      return
    }
    const p = providers.find(pp => pp.id === id)
    if (!p) return
    setConfig(c => ({
      ...c, providerId: id,
      baseUrl: p.defaultBaseUrl,
      model: p.defaultModel,
    }))
    setTestStatus('idle')
  }

  const save = () => {
    saveAIConfig(config)
  }

  const testConnection = async () => {
    setTestStatus('testing')
    setTestError('')
    const start = Date.now()
    try {
      const resp = await fetch('/api/llm/test-connection', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          provider: config.providerId === 'custom' ? 'custom' : config.providerId,
          apiKey: serverManagedKey ? '' : config.apiKey,
          baseUrl: config.baseUrl || undefined,
          model: config.model,
        }),
      })
      const data = await resp.json()
      if (data.ok) {
        setTestStatus('ok')
        setTestLatency(Date.now() - start)
      } else {
        setTestStatus('fail')
        setTestError(data.error || '连接失败')
      }
    } catch {
      setTestStatus('fail')
      setTestError('无法连接到 LLM Gateway')
    }
  }

  const filteredProviders = search
    ? providers.filter(p => p.name.toLowerCase().includes(search.toLowerCase()) || p.id.includes(search.toLowerCase()))
    : providers

  const inputStyle: React.CSSProperties = {
    width: '100%', height: 34, padding: '0 10px',
    borderRadius: 6, border: '1px solid var(--border-color)',
    background: 'var(--bg-tertiary)', color: 'var(--text-primary)',
    fontSize: 13, outline: 'none', fontFamily: 'inherit', boxSizing: 'border-box',
  }

  return (
    <div>
      <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 4 }}>
        模型选择
      </div>
      <div style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 16 }}>
        选择 AI 供应商并配置 API 密钥（密钥仅保存在当前页面内存，刷新后需重新输入）
      </div>

      {/* Provider selector */}
      <div style={{ marginBottom: 14 }}>
        <label style={{ fontSize: 12, color: 'var(--text-secondary)', display: 'block', marginBottom: 6 }}>供应商</label>
        <input style={{ ...inputStyle, marginBottom: 6 }} value={search}
          onChange={e => setSearch(e.target.value)} placeholder="搜索供应商..." />
        <div style={{
          maxHeight: 180, overflow: 'auto', borderRadius: 8,
          border: '1px solid var(--border-color)',
          background: 'var(--bg-tertiary)',
        }}>
          <button onClick={() => selectProvider('custom')} style={{
            width: '100%', display: 'flex', alignItems: 'center', gap: 8,
            padding: '8px 10px', border: 'none', borderBottom: '1px solid var(--border-color)',
            background: config.providerId === 'custom' ? 'var(--bg-hover)' : 'transparent',
            color: 'var(--text-primary)', cursor: 'pointer', fontSize: 12.5, fontFamily: 'inherit', textAlign: 'left',
          }}>
            <span style={{ fontSize: 13, fontWeight: 500 }}>自定义</span>
            <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>手动配置 API</span>
          </button>
          {filteredProviders.map(p => (
            <button key={p.id} onClick={() => selectProvider(p.id)} style={{
              width: '100%', display: 'flex', alignItems: 'center', gap: 8,
              padding: '8px 10px', border: 'none', borderBottom: '1px solid var(--border-color)',
              background: config.providerId === p.id ? 'var(--bg-hover)' : 'transparent',
              color: 'var(--text-primary)', cursor: 'pointer', fontSize: 12.5, fontFamily: 'inherit', textAlign: 'left',
            }}>
              <span style={{ fontSize: 13, fontWeight: 500 }}>{p.name}</span>
              {p.apiType !== 'openai' && (
                <span style={{ fontSize: 9, padding: '1px 4px', borderRadius: 3, background: 'var(--bg-hover)', color: 'var(--text-muted)', letterSpacing: '0.02em' }}>
                  {p.apiType}
                </span>
              )}
            </button>
          ))}
        </div>
      </div>

      {/* API Key */}
      <div style={{ marginBottom: 14 }}>
        <label style={{ fontSize: 12, color: 'var(--text-secondary)', display: 'block', marginBottom: 6 }}>API Key</label>
        <input style={{ ...inputStyle, fontFamily: 'monospace' }} type="password"
          value={serverManagedKey ? '' : config.apiKey}
          disabled={serverManagedKey}
          placeholder={serverManagedKey ? '由服务端 ZHIPU_API_KEY 管理' : 'API Key（仅保存在当前页面内存）'}
          onChange={e => setConfig(c => ({ ...c, apiKey: e.target.value }))} />
      </div>

      {/* Model */}
      <div style={{ marginBottom: 14 }}>
        <label style={{ fontSize: 12, color: 'var(--text-secondary)', display: 'block', marginBottom: 6 }}>模型</label>
        {currentProvider && currentProvider.knownModels.length > 0 ? (
          <select style={{ ...inputStyle, cursor: 'pointer' }}
            value={config.model} onChange={e => setConfig(c => ({ ...c, model: e.target.value }))}>
            {currentProvider.knownModels.map(m => (
              <option key={m} value={m}>{m}</option>
            ))}
          </select>
        ) : (
          <input style={inputStyle} value={config.model}
            onChange={e => setConfig(c => ({ ...c, model: e.target.value }))}
            placeholder="输入模型名称" />
        )}
      </div>

      {/* Base URL */}
      <div style={{ marginBottom: 14 }}>
        <label style={{ fontSize: 12, color: 'var(--text-secondary)', display: 'block', marginBottom: 6 }}>Base URL</label>
        <input style={{ ...inputStyle, fontFamily: 'monospace' }} value={config.baseUrl || ''}
          onChange={e => setConfig(c => ({ ...c, baseUrl: e.target.value }))}
          placeholder={currentProvider?.defaultBaseUrl || "https://api.example.com/v1"} />
      </div>

      {/* Advanced */}
      <button onClick={() => setShowAdvanced(v => !v)} style={{
        background: 'none', border: 'none', color: 'var(--accent-blue)',
        cursor: 'pointer', fontSize: 12, padding: 0, fontFamily: 'inherit', marginBottom: 14,
      }}>
        {showAdvanced ? '收起' : '展开'}高级设置
      </button>

      {showAdvanced && (
        <div style={{ marginBottom: 14 }}>
          <div style={{ marginBottom: 10 }}>
            <label style={{ fontSize: 12, color: 'var(--text-secondary)', display: 'block', marginBottom: 4 }}>
              Temperature: {config.temperature}
            </label>
            <input type="range" min="0" max="2" step="0.1"
              value={config.temperature}
              onChange={e => setConfig(c => ({ ...c, temperature: parseFloat(e.target.value) }))}
              style={{ width: '100%', accentColor: 'var(--accent-blue)' }} />
          </div>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
              <label style={{ fontSize: 12, color: 'var(--text-secondary)', display: 'block' }}>
                Max Tokens
              </label>
              {isReasoningModel() && (
                <button onClick={() => setConfig(c => ({ ...c, maxTokens: 16384 }))}
                  style={{ fontSize: 11, padding: '1px 8px', borderRadius: 10, border: '1px solid var(--accent-blue)', background: 'rgba(59,130,246,0.12)', color: 'var(--accent-blue)', cursor: 'pointer', fontFamily: 'inherit' }}>
                  思考型模型 · 推荐 16384 · 点击应用
                </button>
              )}
            </div>
            <input style={inputStyle} type="number" min={64} max={32768} step={64}
              value={config.maxTokens}
              placeholder={`推荐 ${recommendedMaxTokens}`}
              onChange={e => setConfig(c => ({ ...c, maxTokens: parseInt(e.target.value) || 8192 }))} />
            {!isReasoningModel() && (
              <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 4 }}>
                非思考型模型推荐 8192；思考型模型（deepseek-v4-flash 等）推荐 16384
              </div>
            )}
            {isMultimodalModel() && (
              <div style={{ fontSize: 11, color: 'var(--accent-green)', marginTop: 4 }}>
                ✓ 多模态模型：图片/PDF 直接给 AI 看图，不走 OCR
              </div>
            )}
          </div>
        </div>
      )}

      {/* Test connection */}
      <div style={{ marginBottom: 14 }}>
        <button onClick={testConnection} disabled={testStatus === 'testing' || (!serverManagedKey && !config.apiKey)} style={{
          width: '100%', height: 34, borderRadius: 6, border: '1px solid var(--border-color)',
          background: 'var(--bg-secondary)', color: 'var(--text-primary)',
          cursor: (testStatus === 'testing' || (!serverManagedKey && !config.apiKey)) ? 'default' : 'pointer',
          display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
          fontSize: 13, fontFamily: 'inherit',
          opacity: testStatus === 'testing' || (!serverManagedKey && !config.apiKey) ? 0.5 : 1,
        }}>
          {testStatus === 'testing' ? <Loader size={14} style={{ animation: 'spin 1s linear infinite' }} /> : null}
          {testStatus === 'ok' ? <Check size={14} style={{ color: 'var(--accent-green)' }} /> : null}
          {testStatus === 'fail' ? <X size={14} style={{ color: 'var(--accent-red)' }} /> : null}
          {testStatus === 'testing' ? '测试中...'
            : testStatus === 'ok' ? `连接成功 (${testLatency}ms)`
            : testStatus === 'fail' ? (testError || '连接失败')
            : '测试连接'}
        </button>
      </div>

      {/* Save */}
      <button onClick={save} style={{
        width: '100%', height: 38, borderRadius: 8, border: 'none',
        background: 'var(--accent-blue)', color: '#fff',
        cursor: 'pointer', fontSize: 14, fontWeight: 500, fontFamily: 'inherit',
      }}>
        保存配置
      </button>
    </div>
  )
}
