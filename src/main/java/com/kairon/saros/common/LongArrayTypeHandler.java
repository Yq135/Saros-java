package com.kairon.saros.common;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PG BIGINT[] ↔ Long[]（经 JDBC Array；经 mybatis.type-handlers-package 自动注册）。
 *
 * <p>用途：qa_messages.referenced_knowledge_ids。写库时 null 元素与 null 数组均直传 NULL。
 */
@MappedTypes(Long[].class)
public class LongArrayTypeHandler extends BaseTypeHandler<Long[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Long[] parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setArray(i, ps.getConnection().createArrayOf("bigint", parameter));
    }

    @Override
    public Long[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toArray(rs.getArray(columnName));
    }

    @Override
    public Long[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toArray(rs.getArray(columnIndex));
    }

    @Override
    public Long[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toArray(cs.getArray(columnIndex));
    }

    private Long[] toArray(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        try {
            Object raw = array.getArray();
            if (raw instanceof Long[] longs) {
                return longs;
            }
            Object[] items = (Object[]) raw;
            Long[] result = new Long[items.length];
            for (int i = 0; i < items.length; i++) {
                result[i] = items[i] == null ? null : ((Number) items[i]).longValue();
            }
            return result;
        } finally {
            array.free();
        }
    }
}
