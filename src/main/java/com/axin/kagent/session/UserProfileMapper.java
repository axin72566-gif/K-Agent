package com.axin.kagent.session;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 用户画像 MySQL 兜底存储 Mapper。
 */
@Mapper
public interface UserProfileMapper {

    @Select("SELECT role, tech_stack, preferences, current_project, extra, updated_at FROM user_profile WHERE user_id = #{userId}")
    UserProfileRow findById(String userId);

    @Insert("REPLACE INTO user_profile (user_id, role, tech_stack, preferences, current_project, extra, updated_at) "
        + "VALUES (#{userId}, #{role}, #{techStack}, #{preferences}, #{currentProject}, #{extra}, #{updatedAt})")
    void save(UserProfileRow row);

    @Delete("DELETE FROM user_profile WHERE user_id = #{userId}")
    void deleteById(String userId);
}
