package com.tpe.systemtests.harness;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.UUID;

/** A query's rows as plain text for a {@link DebugBundle}: a title, then one tab-separated line per row. */
final class SqlDump {

    private SqlDump() {}

    /** {@code sql} with {@code ids} as its one {@code uuid[]} parameter. */
    static String of(Connection connection, String title, String sql, Collection<UUID> ids) throws SQLException {
        var text = new StringBuilder("== ").append(title).append('\n');
        try (var statement = connection.prepareStatement(sql)) {
            statement.setArray(1, connection.createArrayOf("uuid", ids.toArray(UUID[]::new)));
            try (var rs = statement.executeQuery()) {
                int columns = rs.getMetaData().getColumnCount();
                for (int i = 1; i <= columns; i++) {
                    text.append(i == 1 ? "" : "\t").append(rs.getMetaData().getColumnLabel(i));
                }
                text.append('\n');
                while (rs.next()) {
                    for (int i = 1; i <= columns; i++) {
                        text.append(i == 1 ? "" : "\t").append(rs.getString(i));
                    }
                    text.append('\n');
                }
            }
        }
        return text.append('\n').toString();
    }
}
