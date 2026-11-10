package com.jitsimeet.api.config.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * SQLite 时间字段处理器，统一把 Instant 保存为 ISO-8601 文本。
 */
@MappedTypes(Instant.class)
@MappedJdbcTypes(value = JdbcType.VARCHAR, includeNullJdbcType = true)
public class InstantSqliteTypeHandler extends BaseTypeHandler<Instant> {

    /**
     * 写入非空时间值。
     *
     * @param ps 预编译语句
     * @param i 参数位置
     * @param parameter 时间值
     * @param jdbcType JDBC 类型
     * @throws SQLException 数据库写入异常
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Instant parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, parameter.toString());
    }

    /**
     * 按字段名读取时间值。
     *
     * @param rs 查询结果集
     * @param columnName 字段名
     * @return 时间值
     * @throws SQLException 数据库读取异常
     */
    @Override
    public Instant getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    /**
     * 按字段序号读取时间值。
     *
     * @param rs 查询结果集
     * @param columnIndex 字段序号
     * @return 时间值
     * @throws SQLException 数据库读取异常
     */
    @Override
    public Instant getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    /**
     * 从存储过程输出中读取时间值，保持 TypeHandler 接口完整。
     *
     * @param cs 存储过程语句
     * @param columnIndex 字段序号
     * @return 时间值
     * @throws SQLException 数据库读取异常
     */
    @Override
    public Instant getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private Instant parse(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
