package org.jumpserver.chen.framework.datasource.sql;


import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
public class SQL {


    private String sql;
    private List<Object> parameters = List.of();

    /** Fixed metadata query with JDBC value parameters, never SQL interpolation. */
    public static SQL bound(String sql, Object... values) {
        SQL query = new SQL(sql);
        query.parameters = java.util.Arrays.asList(values.clone());
        return query;
    }

    public SQL(String sql) {
        this.sql = sql;
    }

    public static SQL of(String sql, Map<String, Object> params) {
        List<String> paramNames = new ArrayList<>();
        Matcher matcher = Pattern.compile(":(\\w+)").matcher(sql);
        while (matcher.find()) {
            paramNames.add(matcher.group(1));
        }
        for (String paramName : paramNames) {
            Object paramValue = params.get(paramName);
            if (paramValue == null) {
                paramValue = "";
            }
            sql = sql.replace(String.format(":%s", paramName), paramValue.toString());
        }
        return new SQL(sql);
    }

    public static SQL of(String sql, Object... params) {
        for (Object param : params) {
            sql = sql.replaceFirst("\\?", param.toString());
        }
        return new SQL(sql);
    }

    public static SQL of(String sql) {
        return new SQL(sql);
    }
}
