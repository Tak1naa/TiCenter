package com.exambank.chat.service;

import com.exambank.common.dto.Reference;
import com.exambank.common.entity.Question;
import com.exambank.common.entity.QuizAttempt;
import com.exambank.common.entity.Student;
import com.exambank.common.repository.ExamFileRepository;
import com.exambank.common.repository.QuestionRepository;
import com.exambank.common.repository.QuizAttemptRepository;
import com.exambank.common.repository.StudentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 智能题库闭环：AI 按题库页出题 → 入库 → 学生刷题判分 → 错题本 → 错题 AI 讲解。 */
@Service
public class QuestionBankService {

    private static final Logger log = LoggerFactory.getLogger(QuestionBankService.class);

    private final QuestionRepository questionRepository;
    private final QuizAttemptRepository attemptRepository;
    private final StudentRepository studentRepository;
    private final ExamFileRepository examFileRepository;
    private final RagService ragService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.llm.gateway-url:http://localhost:8003}")
    private String llmGatewayUrl;

    private static final String API_SERVICE_TOKEN = System.getenv("API_SERVICE_TOKEN") != null
            ? System.getenv("API_SERVICE_TOKEN") : "";

    private static final int GENERATION_CONTEXT_PAGES = 6;
    private static final int GENERATION_CONTEXT_MAX_TOKENS = 5000;

    public QuestionBankService(QuestionRepository questionRepository,
                               QuizAttemptRepository attemptRepository,
                               StudentRepository studentRepository,
                               ExamFileRepository examFileRepository,
                               RagService ragService) {
        this.questionRepository = questionRepository;
        this.attemptRepository = attemptRepository;
        this.studentRepository = studentRepository;
        this.examFileRepository = examFileRepository;
        this.ragService = ragService;
    }

    // ── DTO ──

    public record GenerateRequest(List<Long> selectedFileIds, List<String> selectedFiles, String courseId, String topic,
                                  Integer count, List<String> types, String difficulty,
                                  String provider, String model, String apiKey, String baseUrl,
                                  Integer maxTokens) {}

    public record AttemptView(String selectedAnswer, boolean correct, String attemptedAt) {}

    /** 刷题视图：未作答的题隐藏答案/解析（防前端偷看），作答后返回。 */
    public record QuestionView(Long id, String questionType, String stem, List<String> options,
                               String difficulty, String knowledgePoint,
                               String sourceFile, Integer sourcePage, String createdAt,
                               String answer, String explanation, AttemptView lastAttempt) {}

    public record GradeRequest(Long questionId, String answer) {}

    public record GradeResult(boolean correct, String correctAnswer, String explanation) {}

    public record ExplainRequest(Long questionId, String provider, String model,
                                 String apiKey, String baseUrl, Integer maxTokens) {}

    // ── 出题 ──

    public List<QuestionView> generate(String studentNo, GenerateRequest req) throws Exception {
        int count = req.count() != null ? Math.min(Math.max(req.count(), 1), 20) : 5;
        List<String> types = (req.types() == null || req.types().isEmpty())
                ? List.of("choice") : req.types();
        String difficulty = req.difficulty() == null || req.difficulty().isBlank()
                ? "medium" : req.difficulty();

        List<Reference> refs = ragService.buildGenerationContext(
                req.selectedFileIds(), req.selectedFiles(), req.courseId(), req.topic(), GENERATION_CONTEXT_PAGES);
        if (refs.isEmpty()) {
            throw new IllegalArgumentException("所选文件暂无已解析的文本内容，请先等待解析完成或重新解析");
        }

        StringBuilder contextBuilder = new StringBuilder();
        for (Reference ref : refs) {
            int remaining = GENERATION_CONTEXT_MAX_TOKENS - PromptBudgeter.count(contextBuilder.toString());
            if (remaining < 40) break;
            String snippet = ref.getSnippet() == null ? "" : ref.getSnippet();
            String prefix = "[" + ref.getSourceId() + " 来源: " + ref.getFileName()
                    + " 第" + ref.getPage() + "页]\n";
            snippet = PromptBudgeter.truncate(snippet, remaining - PromptBudgeter.count(prefix));
            contextBuilder.append(prefix)
                    .append(snippet).append("\n");
        }

        String systemPrompt = PromptCatalog.QUESTION_SYSTEM;

        String typeDesc = String.join("/", types);
        String userPrompt = "题库资料：\n" + contextBuilder + "\n请出 " + count + " 道题（题型：" + typeDesc
                + "，难度：" + difficulty + "）。"
                + (req.topic() != null && !req.topic().isBlank()
                    ? "聚焦主题：" + req.topic() + "。" : "")
                + "只输出 JSON 数组。";

        String raw = callLlm(systemPrompt, userPrompt,
                req.provider(), req.model(), req.apiKey(), req.baseUrl(),
                req.maxTokens() != null ? Math.min(req.maxTokens(), 8192)
                        : Math.min(8192, Math.max(2048, count * 500)));

        ArrayNode array = extractJsonArray(raw);
        if (array == null || array.isEmpty()) {
            throw new IllegalStateException("AI 未返回有效题目 JSON，请重试");
        }

        // 文件名/页码 → fileId（出题来源归属）
        Map<String, Map<Integer, Long>> filePageIds = new HashMap<>();
        for (Reference ref : refs) {
            examFileRepository.findByFilePath(ref.getFilePath()).ifPresent(f ->
                    filePageIds.computeIfAbsent(ref.getFileName(), k -> new HashMap<>())
                            .put(ref.getPage(), f.getId()));
        }

        List<Question> saved = new ArrayList<>();
        for (JsonNode node : array) {
            if (node == null || !node.has("stem")) continue;
            Question q = new Question();
            String type = node.path("type").asText("choice");
            if (!type.equals("choice") && !type.equals("judge") && !type.equals("short")) continue;
            q.setQuestionType(type);
            q.setStem(node.path("stem").asText());
            if (q.getStem().isBlank() || node.path("answer").asText("").isBlank()) continue;
            if (type.equals("choice") && (!node.has("options") || !node.get("options").isArray()
                    || node.get("options").size() != 4 || !node.path("answer").asText().matches("[A-D]"))) continue;
            if (type.equals("judge") && judgeKey(node.path("answer").asText()) == ' ') continue;
            if (type.equals("choice")) {
                q.setOptionsJson(objectMapper.writeValueAsString(node.get("options")));
            }
            q.setAnswer(node.path("answer").asText());
            q.setExplanation(node.path("explanation").asText(null));
            q.setKnowledgePoint(node.path("knowledge_point").asText(null));
            q.setDifficulty(difficulty);
            String source = node.path("source").asText(null);
            int page = node.path("source_page").asInt(0);
            q.setSourceFile(source);
            q.setSourcePage(page > 0 ? page : null);
            if (source == null || page <= 0 || !filePageIds.getOrDefault(source, Map.of()).containsKey(page)) continue;
            if (source != null) {
                Map<Integer, Long> pages = filePageIds.getOrDefault(source, Map.of());
                Long fileId = pages.getOrDefault(page > 0 ? page : pages.keySet().stream().findFirst().orElse(-1), null);
                if (fileId == null) fileId = pages.values().stream().findFirst().orElse(null);
                q.setFileId(fileId);
            }
            String contentHash = sha256(type + "\n" + q.getStem().strip().toLowerCase() + "\n" + q.getAnswer().strip().toLowerCase());
            if (questionRepository.existsByContentHash(contentHash)) continue;
            q.setContentHash(contentHash);
            q.setReviewStatus("PENDING");
            saved.add(questionRepository.save(q));
        }
        if (saved.isEmpty()) {
            throw new IllegalStateException("AI 返回的题目均缺少必填字段，请重试");
        }
        log.info("Generated {} questions for student {}", saved.size(), studentNo);
        // 新题未作答 → 视图隐藏答案
        Map<Long, AttemptView> none = Map.of();
        return saved.stream().map(q -> toView(q, none)).toList();
    }

    // ── 刷题 / 错题本 ──

    /** 题目列表（带当前学生最新作答状态；未作答隐藏答案与解析）。 */
    public List<QuestionView> listQuestions(Long studentId) {
        Map<Long, AttemptView> latest = latestAttempts(studentId);
        return questionRepository.findTop200ByReviewStatusOrderByCreatedAtDesc("APPROVED").stream()
                .map(q -> toView(q, latest))
                .toList();
    }

    /** 判分并记录作答。choice 按选项字母、judge 按对/错归一化比较；short 归一化后全等或互相包含。 */
    public GradeResult grade(Long studentId, GradeRequest req) {
        Question q = questionRepository.findById(req.questionId())
                .orElseThrow(() -> new IllegalArgumentException("题目不存在"));
        boolean correct = judge(q, req.answer());
        attemptRepository.save(new QuizAttempt(studentId, q.getId(),
                truncate(req.answer(), 500), correct));
        return new GradeResult(correct, q.getAnswer(), q.getExplanation());
    }

    /** 错题本：每人每题最新一次作答为错的题（之后答对自动移出）。 */
    public List<QuestionView> mistakes(Long studentId) {
        Map<Long, AttemptView> latest = latestAttempts(studentId);
        List<QuestionView> out = new ArrayList<>();
        for (Question q : questionRepository.findTop200ByReviewStatusOrderByCreatedAtDesc("APPROVED")) {
            AttemptView a = latest.get(q.getId());
            if (a != null && !a.correct()) out.add(toView(q, latest));
        }
        return out;
    }

    /** 错题 AI 讲解：结合学生错误作答针对性讲解。 */
    public String explainWrong(Long studentId, ExplainRequest req) throws Exception {
        AttemptView attempt = latestAttempts(studentId).get(req.questionId());
        Question q = questionRepository.findById(req.questionId())
                .orElseThrow(() -> new IllegalArgumentException("题目不存在"));

        String options = q.getOptionsJson() != null ? "选项：" + q.getOptionsJson() : "";
        String systemPrompt = PromptCatalog.EXPLAIN_SYSTEM;
        String userPrompt = "题目（" + q.getQuestionType() + "）：" + q.getStem() + "\n"
                + options + "\n"
                + "正确答案：" + q.getAnswer() + "\n"
                + (q.getExplanation() != null ? "资料解析：" + q.getExplanation() + "\n" : "")
                + (attempt != null ? "学生作答：" + attempt.selectedAnswer() + "\n" : "")
                + "请针对该学生的错误作答讲解这道题。";

        return callLlm(systemPrompt, userPrompt,
                req.provider(), req.model(), req.apiKey(), req.baseUrl(),
                req.maxTokens() != null ? Math.min(req.maxTokens(), 4096) : 2048);
    }

    public Long resolveStudentId(String studentNo) {
        return studentRepository.findByStudentNo(studentNo)
                .map(Student::getId)
                .orElseThrow(() -> new IllegalArgumentException("学生账号不存在"));
    }

    // ── 内部 ──

    private Map<Long, AttemptView> latestAttempts(Long studentId) {
        // 按 attemptedAt 降序，每题只留第一条（最新）
        Map<Long, AttemptView> latest = new HashMap<>();
        for (QuizAttempt a : attemptRepository.findByStudentIdOrderByAttemptedAtDesc(studentId)) {
            latest.putIfAbsent(a.getQuestionId(), new AttemptView(
                    a.getSelectedAnswer(), a.isCorrect(),
                    a.getAttemptedAt() != null ? a.getAttemptedAt().toString() : null));
        }
        return latest;
    }

    private QuestionView toView(Question q, Map<Long, AttemptView> latest) {
        List<String> options = null;
        if (q.getOptionsJson() != null) {
            try {
                options = objectMapper.readValue(q.getOptionsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            } catch (Exception ignored) {}
        }
        AttemptView attempt = latest.get(q.getId());
        // 未作答隐藏答案/解析；已作答（无论对错）展示用于复盘
        String answer = attempt != null ? q.getAnswer() : null;
        String explanation = attempt != null ? q.getExplanation() : null;
        return new QuestionView(q.getId(), q.getQuestionType(), q.getStem(), options,
                q.getDifficulty(), q.getKnowledgePoint(), q.getSourceFile(), q.getSourcePage(),
                q.getCreatedAt() != null ? q.getCreatedAt().toString() : null,
                answer, explanation, attempt);
    }

    private boolean judge(Question q, String studentAnswer) {
        switch (q.getQuestionType()) {
            case "choice" -> {
                return choiceKey(q.getAnswer()) == choiceKey(studentAnswer)
                        && choiceKey(studentAnswer) != ' ';
            }
            case "judge" -> {
                return judgeKey(q.getAnswer()) == judgeKey(studentAnswer)
                        && judgeKey(studentAnswer) != ' ';
            }
            default -> {
                String a = norm(q.getAnswer());
                String s = norm(studentAnswer);
                return !a.isEmpty() && (a.equals(s)
                        || (s.length() >= 4 && (a.contains(s) || s.contains(a))));
            }
        }
    }

    /** 选项字母归一化：取第一个 A-D 字母；非法返回空格。 */
    private static char choiceKey(String s) {
        if (s == null) return ' ';
        String t = s.toUpperCase().replaceAll("[^A-D]", "");
        return t.isEmpty() ? ' ' : t.charAt(0);
    }

    /** 判断题归一化：对/√/正确/T → T；错/×/误/F/不… → F；无法识别返回空格。 */
    private static char judgeKey(String s) {
        if (s == null || s.isBlank()) return ' ';
        String t = norm(s);
        boolean positive = t.contains("对") || t.contains("√") || t.contains("正确")
                || t.contains("TRUE") || t.equals("T") || t.equals("Y") || t.contains("YES");
        boolean negative = t.contains("错") || t.contains("误") || t.contains("×")
                || t.contains("FALSE") || t.equals("F") || t.equals("N") || t.contains("NO");
        if (positive && negative) {
            // "不正确/不对/错误"：否定优先
            return t.contains("不正确") || t.contains("不对") || t.contains("错误") ? 'F' : 'T';
        }
        if (positive) return 'T';
        if (negative) return 'F';
        return ' ';
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.replace("（", "(").replace("）", ")").replaceAll("\\s+", "").trim().toUpperCase();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** 从模型输出提取 JSON 数组（容忍 ```json 围栏与前后多余文本）。 */
    private ArrayNode extractJsonArray(String text) {
        try {
            String t = text.replace("```json", "").replace("```", "").trim();
            int s = t.indexOf('[');
            int e = t.lastIndexOf(']');
            if (s < 0 || e <= s) return null;
            JsonNode node = objectMapper.readTree(t.substring(s, e + 1));
            return node instanceof ArrayNode a ? a : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /** 调 llm-gateway（SSE 流式）聚合成完整文本。与 RagService 同协议，但无历史/引用逻辑。 */
    private String callLlm(String systemPrompt, String userPrompt,
                           String provider, String model, String apiKey, String baseUrl,
                           int maxTokens) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userPrompt));

        String cacheKey = sha256(PromptCatalog.VERSION + "|" + systemPrompt + "|" + userPrompt).substring(0, 16);
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "messages", messages, "temperature", 0.3,
                "max_tokens", maxTokens, "stream", true,
                "provider", provider != null ? provider : "deepseek",
                "model", model != null ? model : "deepseek-chat",
                "api_key", provider != null && provider.matches("zhipu(_en)?") ? ""
                        : (apiKey != null ? apiKey : ""),
                "base_url", baseUrl != null ? baseUrl : "",
                "prompt_version", PromptCatalog.VERSION,
                "cache_key", cacheKey));
        byte[] bodyBytes = requestBody.getBytes(StandardCharsets.UTF_8);

        java.net.URL url = new java.net.URL(llmGatewayUrl + "/chat/stream");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        if (!API_SERVICE_TOKEN.isEmpty()) conn.setRequestProperty("X-Api-Token", API_SERVICE_TOKEN);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(300000);
        conn.setFixedLengthStreamingMode(bodyBytes.length);

        try (var os = conn.getOutputStream()) {
            os.write(bodyBytes);
            os.flush();
        }

        StringBuilder full = new StringBuilder();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            String currentEvent = "";
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    currentEvent = line.substring(6).trim();
                    continue;
                }
                if (!line.startsWith("data:")) continue;
                String json = line.substring(5).trim();
                if (json.isEmpty()) continue;
                try {
                    var node = objectMapper.readTree(json);
                    if (currentEvent.equals("error")) {
                        throw new IllegalStateException(node.path("error").asText("AI 调用失败"));
                    }
                    if (!currentEvent.equals("reason")) {
                        String content = node.path("content").asText("");
                        full.append(content);
                    }
                } catch (IllegalStateException ise) {
                    throw ise;
                } catch (Exception ignored) {
                    // skip malformed JSON
                }
            }
        } finally {
            conn.disconnect();
        }
        return full.toString();
    }
}
