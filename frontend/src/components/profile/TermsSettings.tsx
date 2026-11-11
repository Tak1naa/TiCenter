export default function TermsSettings() {
  return (
    <div>
      <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 16 }}>
        服务协议
      </div>
      <div style={{
        padding: 16, borderRadius: 10, background: 'var(--bg-tertiary)',
        fontSize: 13, lineHeight: 1.8, color: 'var(--text-secondary)',
      }}>
        <p style={{ marginTop: 0 }}>
          AI 智能题库是一个基于人工智能的备考辅助平台。
        </p>
        <p>
          本站提供的学习建议和测验内容均由 AI 模型生成，仅供参考学习使用，
          不构成任何考试的原题或标准答案。
        </p>
        <p>
          用户上传的题库资料仅用于个人学习用途，请勿上传含有侵权、违法或敏感内容的文件。
        </p>
        <p>
          我们重视用户隐私保护，所有对话记录和上传文件仅存储于本地，
          不会与第三方共享。
        </p>
        <p style={{ color: 'var(--text-muted)', fontSize: 12 }}>
          详细服务条款和隐私政策待补充。
        </p>
      </div>
    </div>
  )
}
