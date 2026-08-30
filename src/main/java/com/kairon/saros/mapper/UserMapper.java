package com.kairon.saros.mapper;

import com.kairon.saros.po.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * users 表访问接口（SQL 实现见 src/main/resources/mapper/UserMapper.xml）。
 */
@Mapper
public interface UserMapper {

    User findByUsername(@Param("username") String username);

    int insertIgnore(@Param("username") String username);
}
