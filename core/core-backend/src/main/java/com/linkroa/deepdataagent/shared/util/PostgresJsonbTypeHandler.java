package com.linkroa.deepdataagent.shared.util;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PostgreSQL JSONB 列类型处理器。
 * <p>将 Java String 字段与 PG jsonb 列互转：写入时将字符串包装为 jsonb 类型的 {@link PGobject}，
 * 读取时把驱动返回的 PGobject 还原为 JSON 文本字符串。用于 MyBatis-Plus 自动 SQL 及注解 SQL
 * 中对 JSONB 列的读写，避免 setString 直写 jsonb 列导致类型不匹配。</p>
 */
public class PostgresJsonbTypeHandler extends BaseTypeHandler<String> {

    /** jsonb 类型名 */
    private static final String JSONB_TYPE = "jsonb";

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject object = new PGobject();
        object.setType(JSONB_TYPE);
        object.setValue(parameter);
        ps.setObject(i, object);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toJsonText(rs.getObject(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toJsonText(rs.getObject(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toJsonText(cs.getObject(columnIndex));
    }

    /**
     * 将驱动返回的数据库对象还原为 JSON 文本。
     *
     * @param value 驱动返回对象（可能为 null）
     * @return JSON 文本；值为 null 时返回 null
     */
    private String toJsonText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof PGobject pgObject) {
            return pgObject.getValue();
        }
        return value.toString();
    }
}
