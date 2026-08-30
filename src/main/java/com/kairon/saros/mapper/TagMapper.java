package com.kairon.saros.mapper;

import com.kairon.saros.po.Tag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * tags 表访问接口（SQL 实现见 src/main/resources/mapper/TagMapper.xml）。
 *
 * <p>标签按 tags.id 升序返回（对应阶段二 array_agg ORDER BY t.id）。
 */
@Mapper
public interface TagMapper {

    int deleteTags(@Param("knowledgeId") long knowledgeId);

    int insertTag(@Param("knowledgeId") long knowledgeId, @Param("name") String name);

    List<Tag> findTagsByKnowledgeIds(@Param("ids") List<Long> ids);

    List<String> suggestTags(@Param("userId") long userId, @Param("pattern") String pattern);
}
