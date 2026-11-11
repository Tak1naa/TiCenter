import type { AIConfig } from '../types'
import { clearCookie, getLocalOrCookie, setLocalAndCookie } from './cookie'

const STORAGE_KEY = 'ai-config'
let runtimeApiKey = ''

function readPersisted(): Partial<AIConfig> {
  try {
    const raw = getLocalOrCookie(STORAGE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as Partial<AIConfig>

    // One-time migration: remove API keys written by older releases from both
    // localStorage and the long-lived JavaScript-readable cookie.
    if (typeof parsed.apiKey === 'string' && parsed.apiKey) {
      runtimeApiKey = parsed.apiKey
      delete parsed.apiKey
      localStorage.setItem(STORAGE_KEY, JSON.stringify(parsed))
    }
    clearCookie(STORAGE_KEY)
    return parsed
  } catch {
    localStorage.removeItem(STORAGE_KEY)
    clearCookie(STORAGE_KEY)
    return {}
  }
}

export function loadAIConfig(defaults?: AIConfig): AIConfig {
  const persisted = readPersisted()
  return {
    providerId: persisted.providerId ?? defaults?.providerId ?? 'custom',
    apiKey: runtimeApiKey,
    baseUrl: persisted.baseUrl ?? defaults?.baseUrl ?? '',
    model: persisted.model ?? defaults?.model ?? '',
    temperature: persisted.temperature ?? defaults?.temperature ?? 0.3,
    maxTokens: persisted.maxTokens ?? defaults?.maxTokens ?? 8192,
  }
}

export function saveAIConfig(config: AIConfig): void {
  runtimeApiKey = config.apiKey || ''
  const { apiKey: _secret, ...safeConfig } = config
  setLocalAndCookie(STORAGE_KEY, JSON.stringify(safeConfig))
  clearCookie(STORAGE_KEY)
}

export function getAIConfigForRequest(): AIConfig {
  return loadAIConfig()
}
