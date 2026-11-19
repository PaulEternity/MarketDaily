package com.pauleternity.marketdaily.mapper;

import com.pauleternity.marketdaily.model.knowledge.KnowledgeTerm;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeTermMapper {

    @Select("SELECT term, explanation, category FROM knowledge_term WHERE term = #{term}")
    KnowledgeTerm findByTerm(@Param("term") String term);
}