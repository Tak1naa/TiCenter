import { useState, useCallback, useEffect } from 'react'
import type { QuizQuestion, QuizGradeResult } from '../../types'
import { useAppContext } from '../../hooks/useAppContext'
import { getAIConfigForRequest } from '../../lib/aiConfig'
import { buildAuthHeaders } from '../../lib/security'
import { Sparkles, RefreshCw, CheckCircle2, XCircle, Lightbulb, BookOpen } from 'lucide-react'

const API_BASE = '/api'

type Tab = 'all' | 'mistakes'
type TypeFlag = 'choice' | 'judge' | 'short'

const DIFFICULTIES = ['easy', 'medium', 'hard'] as const
const DIFFICULTY_LABEL: Record<string, string> = { easy: '基础', medium: '中等', hard: '较难' }
const TYPE_LABEL: Record<TypeFlag, string> = { choice: '单选', judge: '判断', short: '简答' }

/** 学生端刷题面板：AI 按左侧勾选的题库文件出题 → 作答判分 → 错题本 → AI 讲解。 */
export default function QuizPanel() {
  const { selectedFileIds } = useAppContext()
  const [tab, setTab] = useState<Tab>('all')
  const [questions, setQuestions] = useState<QuizQuestion[]>([])
  const [count, setCount] = useState(5)
  const [types, setTypes] = useState<Set<TypeFlag>>(new Set(['choice']))
  const [difficulty, setDifficulty] = useState<string>('medium')
  const [topic, setTopic] = useState('')
  const [generating, setGenerating] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [answers, setAnswers] = useState<Record<number, string>>({})
  const [results, setResults] = useState<Record<number, QuizGradeResult>>({})
  const [explains, setExplains] = useState<Record<number, string>>({})
  const [explaining, setExplaining] = useState<Record<number, boolean>>({})

  const loadQuestions = useCallback(async (which: Tab) => {
    setLoading(true)
    setError('')
    try {
      const resp = await fetch(`${API_BASE}/quiz/${which === 'mistakes' ? 'mistakes' : 'questions'}`, {
        headers: authHeaders(),
      })
      if (!resp.ok) throw new Error(`加载失败 (${resp.status})`)
      const data = await resp.json()
      setQuestions(Array.isArray(data) ? data : [])
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { loadQuestions(tab) }, [tab, loadQuestions])

  const toggleType = (t: TypeFlag) => {
    setTypes(prev => {
      const next = new Set(prev)
      if (next.has(t)) { if (next.size > 1) next.delete(t) }
      else next.add(t)
      return next
    })
  }

  const generate = async () => {
    setGenerating(true)
    setError('')
    try {
      const aiConfig = getAIConfigForRequest()
      const resp = await fetch(`${API_BASE}/quiz/generate`, {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({
          selectedFileIds: Array.from(selectedFileIds),
          topic: topic.trim() || undefined,
          count,
          types: Array.from(types),
          difficulty,
          provider: aiConfig.providerId || undefined,
          model: aiConfig.model || undefined,
          apiKey: /^zhipu(_en)?$/.test(aiConfig.providerId || '') ? undefined : (aiConfig.apiKey || undefined),
          baseUrl: aiConfig.baseUrl || undefined,
          maxTokens: aiConfig.maxTokens || 8192,
        }),
      })
      const data = await resp.json()
      if (!resp.ok) throw new Error(data.error || `出题失败 (${resp.status})`)
      setQuestions(Array.isArray(data) ? data : [])
      setAnswers({})
      setResults({})
      setExplains({})
      setTab('all')
    } catch (e) {
      setError(e instanceof Error ? e.message : '出题失败')
    } finally {
      setGenerating(false)
    }
  }

  const submit = async (questionId: number) => {
    const answer = answers[questionId]?.trim()
    if (!answer) return
    try {
      const resp = await fetch(`${API_BASE}/quiz/attempts`, {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ questionId, answer }),
      })
      const data = await resp.json()
      if (!resp.ok) throw new Error(data.error || '判分失败')
      setResults(prev => ({ ...prev, [questionId]: data as QuizGradeResult }))
    } catch (e) {
      setError(e instanceof Error ? e.message : '判分失败')
    }
  }

  const explain = async (questionId: number) => {
    setExplaining(prev => ({ ...prev, [questionId]: true }))
    try {
      const aiConfig = getAIConfigForRequest()
      const resp = await fetch(`${API_BASE}/quiz/explain`, {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({
          questionId,
          provider: aiConfig.providerId || undefined,
          model: aiConfig.model || undefined,
          apiKey: aiConfig.apiKey || undefined,
          baseUrl: aiConfig.baseUrl || undefined,
        }),
      })
      const data = await resp.json()
      if (!resp.ok) throw new Error(data.error || '讲解失败')
      setExplains(prev => ({ ...prev, [questionId]: data.explanation || '' }))
    } catch (e) {
      setError(e instanceof Error ? e.message : '讲解失败')
    } finally {
      setExplaining(prev => ({ ...prev, [questionId]: false }))
    }
  }

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0, height: '100%' }}>
      {/* 页签：全部题目 / 错题本 */}
      <div style={{ display: 'flex', gap: 4, padding: '8px 16px 0', background: 'var(--bg-primary)' }}>
        <button onClick={() => setTab('all')} style={tabStyle(tab === 'all')}>全部题目</button>
        <button onClick={() => setTab('mistakes')} style={tabStyle(tab === 'mistakes')}>错题本</button>
      </div>

      {/* 出题控制区 */}
      <div style={{ padding: '10px 16px 12px', borderBottom: '1px solid var(--border-color)', background: 'var(--bg-primary)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
            出题范围：{selectedFileIds.size > 0 ? `已勾选 ${selectedFileIds.size} 个文件` : '未勾选（请在左侧选择）'}
          </span>
          <div style={{ flex: 1 }} />
          <select value={count} onChange={e => setCount(Number(e.target.value))} style={selectStyle}>
            {[3, 5, 10, 15].map(n => <option key={n} value={n}>{n} 题</option>)}
          </select>
          <select value={difficulty} onChange={e => setDifficulty(e.target.value)} style={selectStyle}>
            {DIFFICULTIES.map(d => <option key={d} value={d}>{DIFFICULTY_LABEL[d]}</option>)}
          </select>
          <button onClick={generate} disabled={generating} style={generateBtnStyle}>
            {generating ? <RefreshCw size={14} className="spin" /> : <Sparkles size={14} />}
            {generating ? 'AI 出题中…' : 'AI 出题'}
          </button>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginTop: 8 }}>
          {(Object.keys(TYPE_LABEL) as TypeFlag[]).map(t => (
            <button key={t} onClick={() => toggleType(t)} style={typeBtnStyle(types.has(t))}>
              {TYPE_LABEL[t]}
            </button>
          ))}
          <input
            value={topic}
            onChange={e => setTopic(e.target.value)}
            placeholder="聚焦主题（可选，如：中断处理）"
            style={{ flex: 1, minWidth: 160, ...inputStyle }}
          />
        </div>
      </div>

      {/* 题目区 */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '12px 16px' }}>
        {error && <div style={errorStyle}>{error}</div>}
        {loading && <div style={emptyStyle}>加载中…</div>}
        {!loading && questions.length === 0 && (
          <div style={emptyStyle}>
            <BookOpen size={28} style={{ opacity: 0.4, marginBottom: 8 }} />
            {tab === 'mistakes' ? '错题本是空的，答错的题会自动出现在这里' : '还没有题目，勾选左侧文件后点击「AI 出题」'}
          </div>
        )}
        {questions.map((q, i) => (
          <QuestionCard
            key={q.id}
            index={i + 1}
            question={q}
            answer={answers[q.id] || ''}
            result={results[q.id]}
            explainText={explains[q.id]}
            explaining={!!explaining[q.id]}
            onAnswer={v => setAnswers(prev => ({ ...prev, [q.id]: v }))}
            onSubmit={() => submit(q.id)}
            onExplain={() => explain(q.id)}
          />
        ))}
      </div>
    </div>
  )
}

function QuestionCard({ index, question, answer, result, explainText, explaining,
                         onAnswer, onSubmit, onExplain }: {
  index: number
  question: QuizQuestion
  answer: string
  result?: QuizGradeResult
  explainText?: string
  explaining: boolean
  onAnswer: (v: string) => void
  onSubmit: () => void
  onExplain: () => void
}) {
  const attempted = result !== undefined || question.lastAttempt != null || question.answer != null
  const correct = result ? result.correct : question.lastAttempt?.correct

  return (
    <div style={cardStyle}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'baseline' }}>
        <span style={{ fontSize: 12, color: 'var(--text-secondary)', flexShrink: 0 }}>
          {index}. [{TYPE_LABEL[question.questionType]}]
        </span>
        <div style={{ fontSize: 14, color: 'var(--text-primary)', lineHeight: 1.6, whiteSpace: 'pre-wrap' }}>
          {question.stem}
        </div>
      </div>
      {question.knowledgePoint && (
        <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
          知识点：{question.knowledgePoint}
          {question.sourceFile && ` · 来源：${question.sourceFile}${question.sourcePage ? ` 第${question.sourcePage}页` : ''}`}
        </div>
      )}

      {/* 作答区 */}
      {question.questionType === 'choice' && question.options && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 10 }}>
          {question.options.map((opt, j) => (
            <label key={j} style={{ ...optionStyle, ...(attempted ? { opacity: 0.85 } : {}) }}>
              <input
                type="radio"
                name={`q-${question.id}`}
                checked={answer !== '' && answer === letterOf(opt, j)}
                disabled={attempted}
                onChange={() => onAnswer(letterOf(opt, j))}
              />
              <span>{opt}</span>
            </label>
          ))}
        </div>
      )}
      {question.questionType === 'judge' && (
        <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
          {['对', '错'].map(v => (
            <button key={v} onClick={() => onAnswer(v)} disabled={attempted}
              style={typeBtnStyle(answer === v)}>{v}</button>
          ))}
        </div>
      )}
      {question.questionType === 'short' && (
        <textarea
          value={answer}
          onChange={e => onAnswer(e.target.value)}
          disabled={attempted}
          placeholder="输入你的答案…"
          style={{ ...inputStyle, width: '100%', minHeight: 72, marginTop: 10, display: 'block' }}
        />
      )}

      {/* 提交 / 结果 */}
      {!attempted ? (
        <div style={{ marginTop: 10, textAlign: 'right' }}>
          <button onClick={onSubmit} disabled={!answer.trim()} style={submitBtnStyle}>提交</button>
        </div>
      ) : (
        <div style={{ marginTop: 10, borderTop: '1px dashed var(--border-color)', paddingTop: 10 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13 }}>
            {correct
              ? <><CheckCircle2 size={15} color="var(--accent-green, #16a34a)" /> 回答正确</>
              : <><XCircle size={15} color="var(--accent-red, #dc2626)" /> 回答错误</>}
            <div style={{ flex: 1 }} />
            {!correct && !explainText && (
              <button onClick={onExplain} disabled={explaining} style={explainBtnStyle}>
                <Lightbulb size={13} /> {explaining ? '讲解中…' : 'AI 讲解'}
              </button>
            )}
          </div>
          <div style={{ fontSize: 13, color: 'var(--text-primary)', marginTop: 6 }}>
            正确答案：<b>{result ? result.correctAnswer : question.answer}</b>
          </div>
          {(result ? result.explanation : question.explanation) && (
            <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 4, whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>
              解析：{result ? result.explanation : question.explanation}
            </div>
          )}
          {explainText && (
            <div style={{ ...explainBoxStyle, whiteSpace: 'pre-wrap' }}>{explainText}</div>
          )}
        </div>
      )}
    </div>
  )
}

/** 选项文本取字母（"A. xxx" → A；无字母则按序号 A-D）。 */
function letterOf(option: string, index: number): string {
  const m = option.match(/^\s*([A-D])[.、．\s]/i)
  return m ? m[1].toUpperCase() : String.fromCharCode(65 + index)
}

function authHeaders(): Record<string, string> {
  const headers = buildAuthHeaders()
  const studentToken = localStorage.getItem('student-token')
  if (studentToken) headers['X-Student-Token'] = studentToken
  return headers
}

// ── 样式（与现有面板同一套 CSS 变量）──

const tabStyle = (active: boolean): React.CSSProperties => ({
  height: 32, padding: '0 14px', border: 'none', borderBottom: active
    ? '2px solid var(--accent-blue)' : '2px solid transparent',
  background: 'none', color: active ? 'var(--text-primary)' : 'var(--text-secondary)',
  fontSize: 13, cursor: 'pointer',
})

const selectStyle: React.CSSProperties = {
  height: 30, padding: '0 8px', borderRadius: 6,
  border: '1px solid var(--border-color)', background: 'var(--bg-secondary)',
  color: 'var(--text-primary)', fontSize: 13,
}

const inputStyle: React.CSSProperties = {
  height: 30, padding: '0 10px', borderRadius: 6,
  border: '1px solid var(--border-color)', background: 'var(--bg-secondary)',
  color: 'var(--text-primary)', fontSize: 13, outline: 'none',
}

const generateBtnStyle: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 6, height: 30, padding: '0 12px',
  borderRadius: 15, border: 'none', background: 'var(--accent-blue)', color: '#fff',
  fontSize: 13, cursor: 'pointer',
}

const submitBtnStyle: React.CSSProperties = {
  height: 28, padding: '0 14px', borderRadius: 14, border: 'none',
  background: 'var(--accent-blue)', color: '#fff', fontSize: 13, cursor: 'pointer',
}

const explainBtnStyle: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 4, height: 26, padding: '0 10px',
  borderRadius: 13, border: '1px solid var(--border-color)',
  background: 'var(--bg-secondary)', color: 'var(--text-secondary)',
  fontSize: 12, cursor: 'pointer',
}

const typeBtnStyle = (active: boolean): React.CSSProperties => ({
  height: 26, padding: '0 12px', borderRadius: 13,
  border: active ? '1px solid var(--accent-blue)' : '1px solid var(--border-color)',
  background: active ? 'var(--bg-hover)' : 'var(--bg-secondary)',
  color: active ? 'var(--text-primary)' : 'var(--text-secondary)',
  fontSize: 12, cursor: 'pointer',
})

const cardStyle: React.CSSProperties = {
  border: '1px solid var(--border-color)', borderRadius: 10,
  padding: '12px 14px', marginBottom: 12, background: 'var(--bg-primary)',
}

const optionStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 8, fontSize: 13,
  color: 'var(--text-primary)', cursor: 'pointer',
  padding: '6px 8px', borderRadius: 6,
}

const errorStyle: React.CSSProperties = {
  padding: '8px 12px', marginBottom: 12, borderRadius: 8,
  background: 'rgba(220,38,38,0.08)', color: 'var(--accent-red, #dc2626)', fontSize: 13,
}

const emptyStyle: React.CSSProperties = {
  display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
  height: 200, color: 'var(--text-secondary)', fontSize: 13,
}

const explainBoxStyle: React.CSSProperties = {
  marginTop: 8, padding: '10px 12px', borderRadius: 8,
  background: 'var(--bg-hover)', color: 'var(--text-primary)',
  fontSize: 13, lineHeight: 1.7,
}
