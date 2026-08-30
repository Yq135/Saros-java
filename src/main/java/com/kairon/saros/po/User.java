package com.kairon.saros.po;

import java.time.OffsetDateTime;

/**
 * 表对象：users（当前单用户，user_id 由应用层约定）。
 */
public class User {

    public Long id;
    public String username;
    public OffsetDateTime createdAt;
}
