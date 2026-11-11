export interface FileStatus {
  path: string
  status: string
  parseMethod?: string
  totalPages?: number
  parsedPages?: number
  parseTime?: string
  errorMessage?: string
  fileSize?: number
  fileType?: string
}

export interface FileTreeNode {
  name: string
  path: string
  fileId?: number
  courseId?: string
  documentVersion?: string
  generationId?: string
  type: 'directory' | 'file'
  status?: string
  children?: FileTreeNode[]
}

export interface Reference {
  fileName: string
  page: number
  snippet: string
  filePath?: string
  sourceId?: string
  documentVersion?: string
  chunkId?: number
  fileId?: number
  generationId?: string
  accessScope?: 'PUBLIC'
  bbox?: [number, number, number, number]
  contextKind?: 'page' | 'selection' | 'region'
  locationPrecision?: string
}

export interface WorkspaceDocument {
  fileId: number
  fileName: string
  generationId?: string
  documentVersion?: string
  totalPages?: number
}

export interface ContextAttachment extends WorkspaceDocument {
  generationId: string
  page: number
  kind: 'page' | 'selection' | 'region'
  quote?: string
  bbox?: [number, number, number, number]
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  references?: Reference[]
  isStreaming?: boolean
  truncated?: boolean
  thinking?: string
  requestId?: string
  retrievalMode?: string
  degraded?: boolean
  contextRefs?: ContextAttachment[]
}

export interface ParseResult {
  filePath: string
  fileName: string
  parseMethod: string
  pages: PageResult[]
  totalPages: number
  parseTime: string
  fileHash: string
}

export interface PageResult {
  pageNumber: number
  text: string
  method: string
  confidence?: number
  imagePath?: string
}

export interface PreviewResponse {
  pageNumber: number
  text: string
  imageUrl?: string
  parseMethod: string
  confidence?: number
  fileName: string
  totalPages: number
}

export interface AIProvider {
  id: string
  name: string
  apiType: string
  defaultBaseUrl: string
  defaultModel: string
  knownModels: string[]
  reasoningModels?: string[]
  multimodalModels?: string[]
}

export interface AIConfig {
  providerId: string
  apiKey: string
  baseUrl?: string
  model: string
  temperature: number
  maxTokens: number
}

export interface QuizAttemptView {
  selectedAnswer: string
  correct: boolean
  attemptedAt: string
}

/** 刷题视图：未作答的题 answer/explanation 为 null（后端隐藏）。 */
export interface QuizQuestion {
  id: number
  questionType: 'choice' | 'judge' | 'short'
  stem: string
  options: string[] | null
  difficulty: string
  knowledgePoint?: string | null
  sourceFile?: string | null
  sourcePage?: number | null
  createdAt?: string | null
  answer?: string | null
  explanation?: string | null
  lastAttempt?: QuizAttemptView | null
}

export interface QuizGradeResult {
  correct: boolean
  correctAnswer: string
  explanation: string | null
}
