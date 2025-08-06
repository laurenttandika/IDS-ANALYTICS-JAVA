package com.example;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class MdbRecordManager {

    public static void removeRecordsBySource(Connection conn, String hfrCode) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"});
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