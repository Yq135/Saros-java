package com.kairon.saros.po;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 表对象：users（当前单用户，user_id 由应用层约定）。
 *
 * <p>PO 统一约定（DEVELOPMENT.md §4）：字段 private + Lombok @Data，
 * service 层经 setter 赋值、getter 读取；MyBatis 经 setter 映射列。
 */
@Data
public class User {

    private Long id;
    private String username;
    private OffsetDateTime createdAt;
}
