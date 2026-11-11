package com.exambank.common.repository;

import com.exambank.common.entity.ChatHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ChatHistoryRepository extends JpaRepository<ChatHistory, Long> {
    List<ChatHistory> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /** 会话历史最近 N 条（倒序取，再在内存反转）——避免每次提问全量加载历史。 */
    List<ChatHistory> findTop24BySessionIdOrderByCreatedAtDesc(String sessionId);

    /** 删除某学生全部聊天（sessionId = 学号_xxx，前缀匹配）。 */
    long deleteBySessionIdStartingWith(String sessionIdPrefix);

    /** 学生聊天数统计（替代 findAll + Java 过滤）。 */
    long countBySessionIdStartingWith(String sessionIdPrefix);

    /** 按角色计数（仪表盘 user/assistant 消息数）。 */
    long countByRole(String role);
}
