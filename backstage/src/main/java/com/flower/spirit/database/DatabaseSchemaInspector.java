package com.flower.spirit.database;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.springframework.stereotype.Service;

/** JDBC metadata access shared by SQLite and PostgreSQL schema bootstrap code. */
@Service
public class DatabaseSchemaInspector {

    private final DataSource dataSource;

    public DatabaseSchemaInspector(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Set<String> columns(String table) {
        Set<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet rows = metadata.getColumns(connection.getCatalog(), connection.getSchema(), table, null)) {
                while (rows.next()) {
                    result.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
            if (result.isEmpty()) {
                try (ResultSet rows = metadata.getColumns(connection.getCatalog(), connection.getSchema(),
                        table.toUpperCase(Locale.ROOT), null)) {
                    while (rows.next()) {
                        result.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (Exception ignored) {
            return Set.of();
        }
        return result;
    }
}
