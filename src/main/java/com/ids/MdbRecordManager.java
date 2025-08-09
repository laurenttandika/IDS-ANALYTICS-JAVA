package com.ids;

import com.healthmarketscience.jackcess.*;

import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class MdbRecordManager {

    public static void mergeMdbToSqlite(Connection sqliteConnection, Database mdb, String hfrCode, String sourceFile)
            throws SQLException, IOException {
        // (PrintWriter logWriter = new PrintWriter(new BufferedWriter(new
        // FileWriter("import_stats.log", true))))
        try {
            sqliteConnection.setAutoCommit(false);

            for (String tableName : mdb.getTableNames()) {
                Table mdbTable = mdb.getTable(tableName);
                // long sourceCount = mdbTable.getRowCount();

                // Prepare columns
                List<String> columnNames = new ArrayList<>();
                for (Column col : mdbTable.getColumns()) {
                    columnNames.add(col.getName());
                }
                columnNames.add("hfr_code");
                columnNames.add("source_mdb");

                // Create table if not exists
                StringBuilder createSql = new StringBuilder("CREATE TABLE IF NOT EXISTS \"")
                        .append(tableName).append("\" (");
                for (String col : columnNames) {
                    createSql.append("\"").append(col).append("\" TEXT, ");
                }
                createSql.delete(createSql.length() - 2, createSql.length()).append(")");
                sqliteConnection.createStatement().execute(createSql.toString());

                // Prepare insert
                String placeholders = columnNames.stream().map(c -> "?").collect(Collectors.joining(", "));
                String insertSql = "INSERT INTO \"" + tableName + "\" (" +
                        columnNames.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(", ")) +
                        ") VALUES (" + placeholders + ")";
                PreparedStatement insertStmt = sqliteConnection.prepareStatement(insertSql);

                int batchSize = 500;
                int count = 0;

                for (Row row : mdbTable) {
                    for (int i = 0; i < columnNames.size() - 2; i++) {
                        Object val = row.get(columnNames.get(i));
                        insertStmt.setString(i + 1, val != null ? val.toString() : null);
                    }
                    insertStmt.setString(columnNames.size() - 1, hfrCode);
                    insertStmt.setString(columnNames.size(), sourceFile);
                    insertStmt.addBatch();

                    if (++count % batchSize == 0) {
                        insertStmt.executeBatch();
                    }
                }

                insertStmt.executeBatch(); // Final batch
                insertStmt.close();
                sqliteConnection.commit();

                // ResultSet rs = sqliteConnection.createStatement()
                // .executeQuery("SELECT COUNT(*) FROM \"" + tableName + "\" where hfr_code=\""
                // + hfrCode + "\"");
                // rs.next();
                // long destinationCount = rs.getLong(1);

                // String logLine = String.format("File %s Table %s - Access Rows: %d -> SQLite
                // Rows: %d",
                // sourceFile, tableName, sourceCount, destinationCount);
                // System.out.println(logLine);
                // logWriter.println(logLine);
            }
        } catch (Exception ex) {
            sqliteConnection.rollback();
            throw ex;
        } finally {
            sqliteConnection.setAutoCommit(true);
        }
    }

    public static void removeRecordsBySource(Connection conn, String hfrCode) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        ResultSet rs = meta.getTables(null, null, "%", new String[] { "TABLE" });
        Statement stmt = conn.createStatement();

        while (rs.next()) {
            String tableName = rs.getString("TABLE_NAME");
            if (tableName != null && !tableName.isEmpty()) {
                String deleteSQL = "DELETE FROM \"" + tableName + "\" WHERE hfr_code = '" + hfrCode + "'";
                stmt.executeUpdate(deleteSQL);
            }
        }
    }
}