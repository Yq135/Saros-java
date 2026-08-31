package com.kairon.saros.mapper;

import com.kairon.saros.po.QaHistoryRow;
import com.kairon.saros.po.QaMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * qa_messages 表访问接口（SQL 实现见 src/main/resources/mapper/QaMessageMapper.xml）。
 */
@Mapper
public interface QaMessageMapper {

    int insertMessage(QaMessage row);

    /** 会话内全部轮次，按 id 升序（时间正序展示）。 */
    List<QaMessage> findByConversation(@Param("conversationId") long conversationId,
                                       @Param("userId") long userId);

    /**
     * 最近 N 轮历史（多轮上下文注入）：子查询按 id DESC 取 N 条，外层按 created_at 正序
     * （对齐阶段二 load_history 的两段排序）。
     */
    List<QaHistoryRow> recentHistory(@Param("conversationId") long conversationId,
                                     @Param("userId") long userId,
                                     @Param("limit") int limit);
}
