package com.kairon.saros.mapper;

import com.kairon.saros.po.QaConversation;
import com.kairon.saros.po.QaConversationListItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * qa_conversations 表访问接口（SQL 实现见 src/main/resources/mapper/QaConversationMapper.xml）。
 */
@Mapper
public interface QaConversationMapper {

    int insertConversation(QaConversation row);

    QaConversation findById(@Param("id") long id, @Param("userId") long userId);

    int deleteById(@Param("id") long id, @Param("userId") long userId);

    /**
     * 删除无消息的空会话（流失败/中断清理用，对齐阶段二 delete_conversation_if_empty）。
     */
    int deleteIfEmpty(@Param("id") long id, @Param("userId") long userId);

    /**
     * 会话列表：JOIN qa_messages 聚合 message_count/last_active，最近活跃倒序，
     * 关键词匹配标题/问题/答案（ILIKE），LIMIT 100（对齐阶段二 qa.py 列表 SQL）。
     */
    List<QaConversationListItem> listWithCount(@Param("userId") long userId,
                                               @Param("qPattern") String qPattern,
                                               @Param("limit") int limit);
}
