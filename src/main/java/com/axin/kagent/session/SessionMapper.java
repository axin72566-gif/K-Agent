package com.axin.kagent.session;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 会话 MySQL 兜底存储 Mapper。
 */
@Mapper
public interface SessionMapper {

    @Select("SELECT data FROM agent_session WHERE session_id = #{sessionId}")
    String findDataById(String sessionId);

    @Insert("REPLACE INTO agent_session (session_id, data, updated_at) VALUES (#{sessionId}, #{data}, NOW())")
    void save(String sessionId, String data);

    @Delete("DELETE FROM agent_session WHERE session_id = #{sessionId}")
    void deleteById(String sessionId);
}
