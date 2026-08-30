package com.kairon.saros.common;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Select("SELECT id FROM users WHERE username = #{username}")
    Long findIdByUsername(String username);

    @Insert("INSERT INTO users (username) VALUES (#{username}) ON CONFLICT (username) DO NOTHING")
    int insertIgnore(String username);
}
