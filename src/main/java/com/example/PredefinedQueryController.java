package com.example;

import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.sql.*;
import java.time.LocalDate;
import java.util.Objects;

public class PredefinedQueryController {

    @FXML
    private TableView<ObservableList<String>> resultTable;

    @FXML
    private TextArea queryArea;
    @FXML
    private Button exportButton;

    private Connection sqliteConnection;

    public void setSqliteConnection(Connection connection) {
        this.sqliteConnection = connection;
    }

    public void setQueryDisplay(TextArea area, TableView<ObservableList<String>> table, Button export) {
        this.queryArea = area;
        this.resultTable = table;
        this.exportButton = export;
    }

    // Put this in QueryController
    public void runPredefiendQuery(String queryType, LocalDate start, LocalDate end, Runnable onComplete) {
        Objects.requireNonNull(sqliteConnection, "sqliteConnection is null");
        Objects.requireNonNull(start, "start date is null");
        Objects.requireNonNull(end, "end date is null");

        final String startStr = start.toString();
        final String endStr = end.toString();

        // Use a Task so we can keep UI responsive
        javafx.concurrent.Task<QueryPayload> task = new javafx.concurrent.Task<>() {
            @Override
            protected QueryPayload call() throws Exception {
                switch (queryType) {
                    case "TX_NEW":
                        return buildTxNewPayload(startStr, endStr); // DB work only
                    case "HTS_TST":
                        return buildHtsTstPayload(startStr, endStr); // DB work only
                    case "HTS_SELF":
                        return buildHtsSelfPayload(startStr, endStr); // DB work only
                    default:
                        throw new IllegalArgumentException("Unknown predefined query: " + queryType);
                }
            }
        };

        // Optional: show a spinner / disable export while running
        task.setOnRunning(e -> {
            if (exportButton != null)
                exportButton.setVisible(false);
            // If you have a status/progress in Main, you can call Platform.runLater to
            // update it
        });

        // On success -> update UI
        task.setOnSucceeded(e -> {
            QueryPayload payload = task.getValue();

            // Show the SQL text, if you store it
            if (queryArea != null && payload.sql != null) {
                queryArea.setText(payload.sql);
            }

            // Rebuild columns
            resultTable.getColumns().clear();
            for (int i = 0; i < payload.headers.size(); i++) {
                final int colIndex = i;
                TableColumn<ObservableList<String>, String> col = new TableColumn<>(payload.headers.get(i));
                col.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().get(colIndex)));
                resultTable.getColumns().add(col);
            }

            // Set rows
            ObservableList<ObservableList<String>> items = FXCollections.observableArrayList();
            for (java.util.List<String> r : payload.rows) {
                items.add(FXCollections.observableArrayList(r));
            }
            resultTable.setItems(items);

            if (exportButton != null)
                exportButton.setVisible(!items.isEmpty());

            if (onComplete != null)
                onComplete.run(); // <- signal finished
        });

        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            showError(ex != null ? ex.getMessage() : "Query failed.");
        });

        Thread t = new Thread(task, "predef-query-" + queryType);
        t.setDaemon(true);
        t.start();
    }

    /*
     * ------------------------------------------
     * Helpers: a simple payload and builder stubs
     * ------------------------------------------
     */

    // Holds headers, rows, and (optionally) the SQL used
    static class QueryPayload {
        final java.util.List<String> headers;
        final java.util.List<java.util.List<String>> rows;
        final String sql;

        QueryPayload(java.util.List<String> headers, java.util.List<java.util.List<String>> rows, String sql) {
            this.headers = headers;
            this.rows = rows;
            this.sql = sql;
        }
    }

    /**
     * Each builder should:
     * - compose the SQL (with ? placeholders)
     * - run the PreparedStatement with start/end
     * - read ResultSet into plain lists (NO JavaFX classes)
     * - return new QueryPayload(headers, rows, sql)
     */
    private QueryPayload buildHtsTstPayload(String startStr, String endStr) throws SQLException {
        String sql = QueryLoader.getQuery("HTS_TST");
        try (PreparedStatement ps = sqliteConnection.prepareStatement(sql)) {
            ps.setString(1, startStr);
            ps.setString(2, endStr);
            try (ResultSet rs = ps.executeQuery()) {
                java.util.List<String> headers = new java.util.ArrayList<>();
                java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                for (int i = 1; i <= cols; i++)
                    headers.add(md.getColumnLabel(i));
                while (rs.next()) {
                    java.util.List<String> row = new java.util.ArrayList<>(cols);
                    for (int i = 1; i <= cols; i++)
                        row.add(rs.getString(i));
                    rows.add(row);
                }
                return new QueryPayload(headers, rows, sql);
            }
        }
    }

    private QueryPayload buildTxNewPayload(String startStr, String endStr) throws SQLException {
        // Similar pattern; compose SQL, bind dates, read headers/rows, return payload
        String sql = QueryLoader.getQuery("TX_NEW");
        try (PreparedStatement ps = sqliteConnection.prepareStatement(sql)) {
            ps.setString(1, startStr);
            ps.setString(2, endStr);
            try (ResultSet rs = ps.executeQuery()) {
                java.util.List<String> headers = new java.util.ArrayList<>();
                java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                for (int i = 1; i <= cols; i++)
                    headers.add(md.getColumnLabel(i));
                while (rs.next()) {
                    java.util.List<String> row = new java.util.ArrayList<>(cols);
                    for (int i = 1; i <= cols; i++)
                        row.add(rs.getString(i));
                    rows.add(row);
                }
                return new QueryPayload(headers, rows, sql);
            }
        }
        // throw new UnsupportedOperationException("Implement TX_NEW builder");
    }

    private QueryPayload buildHtsSelfPayload(String startStr, String endStr) throws SQLException {
        // Similar pattern
        String sql = QueryLoader.getQuery("HTS_SELF");
        try (PreparedStatement ps = sqliteConnection.prepareStatement(sql)) {
            ps.setString(1, startStr);
            ps.setString(2, endStr);
            try (ResultSet rs = ps.executeQuery()) {
                java.util.List<String> headers = new java.util.ArrayList<>();
                java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                for (int i = 1; i <= cols; i++)
                    headers.add(md.getColumnLabel(i));
                while (rs.next()) {
                    java.util.List<String> row = new java.util.ArrayList<>(cols);
                    for (int i = 1; i <= cols; i++)
                        row.add(rs.getString(i));
                    rows.add(row);
                }
                return new QueryPayload(headers, rows, sql);
            }
        }
        // throw new UnsupportedOperationException("Implement HTS_SELF builder");
    }

    // Show errors on FX thread
    private void showError(String msg) {
        javafx.application.Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
            a.setHeaderText("Query Error");
            a.showAndWait();
        });
    }
}
