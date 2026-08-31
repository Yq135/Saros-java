package com.kairon.saros.service;

import com.kairon.saros.mapper.UserMapper;
import com.kairon.saros.po.User;
import org.springframework.stereotype.Service;

/**
 * 单用户保障（对齐阶段二 db.get_user_id()）：用户名固定 "saros"，
 * 惰性查库/建用户并缓存，应用层约定所有数据挂在同一 user_id 下。
 */
@Service
public class UserService {

    /** 与阶段二 db.DEFAULT_USERNAME 一致 */
    static final String DEFAULT_USERNAME = "saros";

    private final UserMapper userMapper;

    private volatile Long userId;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public long getUserId() {
        Long id = userId;
        if (id == null) {
            synchronized (this) {
                id = userId;
                if (id == null) {
                    userMapper.insertIgnore(DEFAULT_USERNAME);
                    User user = userMapper.findByUsername(DEFAULT_USERNAME);
                    userId = user.getId();
                }
            }
        }
        return userId;
    }
}
