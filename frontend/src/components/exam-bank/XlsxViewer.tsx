import { useEffect, useState } from 'react'
import { Loader } from 'lucide-react'

interface Props {
  url: string
  fileName: string
}

const XlsxViewer = ({ url, fileName }: Props) => {
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [sheets, setSheets] = useState<{ name: string; rows: string[][] }[]>([])
  const [activeSheet, setActiveSheet] = useState(0)

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      try {
        const ExcelJS = (await import('exceljs')).default
        if (cancelled) return
        const resp = await fetch(url)
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
        const buffer = await resp.arrayBuffer()
        if (cancelled) return
        const wb = new ExcelJS.Workbook()
        await wb.xlsx.load(buffer)
        if (cancelled) return
        const result: { name: string; rows: string[][] }[] = []
        wb.eachSheet(sheet => {
          const rows: string[][] = []
          sheet.eachRow((row, rowNumber) => {
            if (rowNumber > 200) return // 防大表卡死
            // exceljs 的 values 可能是数组或稀疏对象；CellValue 含公式函数类型，统一转字符串
            const raw = Array.isArray(row.values) ? row.values : Object.values(row.values ?? {})
            rows.push(raw.slice(1).map(v => (v == null ? '' : String(v))))
          })
          result.push({ name: sheet.name, rows })
        })
        if (cancelled) return
        setSheets(result)
        setActiveSheet(0)
        setLoading(false)
      } catch (err: any) {
        if (!cancelled) { setError(err?.message || 'Excel 渲染失败'); setLoading(false) }
      }
    })()
    return () => { cancelled = true }
  }, [url])

  const active = sheets[activeSheet]

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', background: '#fff' }}>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8, padding: '4px 10px',
        background: 'var(--bg-secondary)', borderBottom: '1px solid var(--border-color)', flexShrink: 0, fontSize: 12,
      }}>
        <span style={{ fontSize: 11, color: 'var(--text-muted)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 200 }}>{fileName}</span>
        <span style={{ flex: 1 }} />
        {sheets.length > 1 && sheets.map((s, i) => (
          <button key={s.name} onClick={() => setActiveSheet(i)}
            style={{
              fontSize: 11, padding: '2px 10px', borderRadius: 10, border: '1px solid var(--border-color)',
              background: i === activeSheet ? 'var(--accent-blue)' : 'var(--bg-tertiary)',
              color: i === activeSheet ? '#fff' : 'var(--text-secondary)', cursor: 'pointer',
            }}>
            {s.name}
          </button>
        ))}
      </div>
      <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
        {loading && (
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%', color: '#9aa0a6', gap: 12 }}>
            <Loader size={24} className="animate-spin" />
            <span style={{ fontSize: 13 }}>读取表格中...</span>
          </div>
        )}
        {error && (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', padding: 20 }}>
            <div style={{ padding: 16, borderRadius: 8, background: 'rgba(239,68,68,0.15)', color: '#f28b82', fontSize: 13, maxWidth: 380, textAlign: 'center' }}>{error}</div>
          </div>
        )}
        {active && (
          <table style={{ borderCollapse: 'collapse', fontSize: 12, minWidth: '100%' }}>
            <tbody>
              {active.rows.map((row, ri) => (
                <tr key={ri} style={{ background: ri === 0 ? 'var(--bg-tertiary)' : 'transparent' }}>
                  {row.map((cell, ci) => (
                    <td key={ci} style={{
                      border: '1px solid var(--border-color)', padding: '4px 10px',
                      whiteSpace: 'nowrap', maxWidth: 300, overflow: 'hidden', textOverflow: 'ellipsis',
                    }}>
                      {cell}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}

export default XlsxViewer
