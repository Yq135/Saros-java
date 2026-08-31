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
 * PG TEXT[] ↔ String[]（经 JDBC Array；经 mybatis.type-handlers-package 自动注册）。
 *
 * <p>用途：qa_messages.suggested_tags。写库时 null 元素与 null 数组均直传 NULL。
 */
@MappedTypes(String[].class)
public class StringArrayTypeHandler extends BaseTypeHandler<String[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String[] parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setArray(i, ps.getConnection().createArrayOf("text", parameter));
    }

    @Override
    public String[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toArray(rs.getArray(columnName));
    }

    @Override
    public String[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toArray(rs.getArray(columnIndex));
    }

    @Override
    public String[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toArray(cs.getArray(columnIndex));
    }

    private String[] toArray(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        try {
            Object raw = array.getArray();
            if (raw instanceof String[] strings) {
                return strings;
            }
            Object[] items = (Object[]) raw;
            String[] result = new String[items.length];
            for (int i = 0; i < items.length; i++) {
                result[i] = items[i] == null ? null : String.valueOf(items[i]);
            }
            return result;
        } finally {
            array.free();
        }
    }
}
