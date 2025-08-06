package com.example;

import com.healthmarketscience.jackcess.Database;
import com.healthmarketscience.jackcess.DatabaseBuilder;
import com.healthmarketscience.jackcess.Row;
import com.healthmarketscience.jackcess.Table;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class MainController {
    @FXML
    private TextArea queryArea;
    @FXML
    private TableView<ObservableList<String>> resultTable;
    @FXML
    private TreeView<String> tableTreeView;
    @FXML
    private ProgressBar importProgress;
    @FXML
    private Label statusLabel;
    @FXML
    private ListView<String> mdbListView;
    @FXML
    private ListView<String> failedImportsListView;

    private Connection sqliteConnection;
    private boolean freshImport = true;
    private ObservableList<String> failedImports = FXCollections.observableArrayList();

    public void initialize() {
        mdbListView.setContextMenu(createMdbListContextMenu());
        failedImportsListView.setItems(failedImports);
    }

    private boolean isAlreadyImported(String hfrCode) {
        try {
            // check if SecurityUsers table exists
            DatabaseMetaData meta = sqliteConnection.getMetaData();
            ResultSet tables = meta.getTables(null, null, "SecurityUsers", null);
            if (!tables.next()) {
                return false; // Allow first-time import
            }

            PreparedStatement stmt = sqliteConnection.prepareStatement(
                    "SELECT 1 FROM SecurityUsers WHERE hfr_code = ? LIMIT 1");
            stmt.setString(1, hfrCode);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            return true; // fail safe
        }
    }

    private ContextMenu createMdbListContextMenu() {
        ContextMenu contextMenu = new ContextMenu();
        MenuItem removeItem = new MenuItem("Remove Records");
        removeItem.setOnAction(e -> {
            String selected = mdbListView.getSelectionModel().getSelectedItem();
            if (selected != null && !selected.isEmpty()) {
                try {
                    Alert confirmDialog = new Alert(Alert.AlertType.CONFIRMATION);
                    confirmDialog.initOwner(mdbListView.getScene().getWindow()); // 👈 anchor to main window
                    confirmDialog.setTitle("Confirm Deletion");
                    confirmDialog.setHeaderText(null);
                    confirmDialog
                            .setContentText("Are you sure you want to remove all records from '" + selected + "'?");
                    Optional<ButtonType> result = confirmDialog.showAndWait();

                    if (result.isPresent() && result.get() == ButtonType.OK) {
                        // Extract hfr_code from 'hfr_code [ source_mdb ]'
                        String hfrCode = selected.split(" [ ")[0];
                        MdbRecordManager.removeRecordsBySource(sqliteConnection, hfrCode);
                        loadMdbSourcesList(sqliteConnection);
                        statusLabel.setText("✅ Records from '" + selected + "' removed.");
                    }
                    loadMdbSourcesList(sqliteConnection);
                    statusLabel.setText("✅ Records from '" + selected + "' removed.");
                } catch (SQLException ex) {
                    ex.printStackTrace();
                    showAlert("Error", "Failed to remove records: " + ex.getMessage());
                }
            }
        });
        contextMenu.getItems().add(removeItem);
        return contextMenu;
    }

    public void onFreshImportClicked() {
        freshImport = true;
        openMdbFiles();
    }

    public void onMergeImportClicked() {
        freshImport = false;
        openMdbFiles();
    }

    private void openMdbFiles() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open MDB File(s)");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Access DB", "*.mdb"));
        List<File> selectedFiles = fileChooser.showOpenMultipleDialog(new Stage());

        if (selectedFiles != null && !selectedFiles.isEmpty()) {
            importMdbFiles(selectedFiles);
        }
    }

    private void importMdbFiles(List<File> selectedFiles) {
        new Thread(() -> {
            try {
                Platform.runLater(() -> {
                    importProgress.setProgress(0);
                    statusLabel.setText("Starting import...");
                });

                if (freshImport) {
                    Files.deleteIfExists(Paths.get("converted.db"));
                    sqliteConnection = DriverManager.getConnection("jdbc:sqlite:converted.db");
                } else {
                    if (sqliteConnection == null || sqliteConnection.isClosed()) {
                        sqliteConnection = DriverManager.getConnection("jdbc:sqlite:converted.db");
                    }
                }

                int totalTableCount = 0;
                Map<File, List<String>> fileTableMap = new HashMap<>();

                for (File selectedFile : selectedFiles) {
                    Database mdb = DatabaseBuilder.open(selectedFile);
                    List<String> tableNames = new ArrayList<>(mdb.getTableNames());
                    totalTableCount += tableNames.size();
                    fileTableMap.put(selectedFile, tableNames);
                    mdb.close();
                }

                int importedTableCounter = 0;

                for (File selectedFile : selectedFiles) {
                    String sourceFile = selectedFile.getName();
                    Platform.runLater(() -> statusLabel.setText("Importing: " + sourceFile));

                    Database mdb = DatabaseBuilder.open(selectedFile);
                    List<String> tableNames = fileTableMap.get(selectedFile);

                    String hfrCode = "UNKNOWN";
                    try {
                        Table configTable = mdb.getTable("tblConfig");
                        Map<String, Integer> hfrCount = new HashMap<>();

                        for (Row row : configTable) {
                            String hfr = row.get("HFRCode") != null ? row.get("HFRCode").toString() : null;
                            if (hfr != null) {
                                hfrCount.put(hfr, hfrCount.getOrDefault(hfr, 0) + 1);
                            }
                        }

                        hfrCode = hfrCount.entrySet().stream()
                                .max(Comparator.comparingInt(Map.Entry::getValue))
                                .map(Map.Entry::getKey)
                                .orElse("UNKNOWN");

                        System.out.println("📌 HFRCode for " + sourceFile + ": " + hfrCode);
                    } catch (Exception e) {
                        System.err.println("⚠️ Could not read HFRCode from " + sourceFile);
                    }

                    // Before importing each file, after determining hfrCode:
                    if (isAlreadyImported(hfrCode)) {
                        String failedEntry = hfrCode + " [ " + sourceFile + " ] - Existing HFR_Code, kindly remove first!";
                        Platform.runLater(() -> failedImports.add(failedEntry));
                        continue;
                    }

                    for (String tableName : tableNames) {
                        Table mdbTable = mdb.getTable(tableName);
                        List<String> columnNames = new ArrayList<>();
                        mdbTable.getColumns().forEach(col -> columnNames.add(col.getName()));
                        columnNames.add("hfr_code");
                        columnNames.add("source_mdb");

                        if (freshImport) {
                            StringBuilder createSql = new StringBuilder("CREATE TABLE IF NOT EXISTS \"")
                                    .append(tableName).append("\" (");
                            for (String col : columnNames) {
                                createSql.append("\"").append(col).append("\" TEXT, ");
                            }
                            createSql.delete(createSql.length() - 2, createSql.length());
                            createSql.append(")");
                            sqliteConnection.createStatement().execute(createSql.toString());
                        }

                        String placeholders = columnNames.stream().map(c -> "?").collect(Collectors.joining(", "));
                        String insertSql = "INSERT INTO \"" + tableName + "\" (" +
                                columnNames.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(", ")) +
                                ") VALUES (" + placeholders + ")";
                        PreparedStatement insertStmt = sqliteConnection.prepareStatement(insertSql);

                        for (Row row : mdbTable) {
                            for (int i = 0; i < columnNames.size() - 2; i++) {
                                Object val = row.get(columnNames.get(i));
                                insertStmt.setString(i + 1, val != null ? val.toString() : null);
                            }
                            insertStmt.setString(columnNames.size() - 1, hfrCode);
                            insertStmt.setString(columnNames.size(), sourceFile);
                            insertStmt.executeUpdate();
                        }

                        importedTableCounter++;
                        double progress = (double) importedTableCounter / totalTableCount;
                        Platform.runLater(() -> {
                            importProgress.setProgress(progress);
                            statusLabel.setText("Imported table: " + tableName);
                        });
                    }
                }

                Platform.runLater(() -> {
                    loadTablesIntoTreeView(sqliteConnection);
                    loadMdbSourcesList(sqliteConnection);
                    statusLabel.setText("✅ All MDB files imported.");
                });

            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> showAlert("Error", "Failed to import MDBs: " + e.getMessage()));
            }
        }).start();
    }

    public void onRunQueryClicked() {
        if (sqliteConnection == null) {
            showAlert("Error", "No SQLite database loaded. Please import an MDB file first.");
            return;
        }

        String query = queryArea.getText();
        if (query == null || query.isBlank()) {
            showAlert("Error", "Please enter a SQL query.");
            return;
        }

        executeQueryAndDisplay(query);
    }

    private void executeQueryAndDisplay(String query) {
        try (Statement stmt = sqliteConnection.createStatement();
                ResultSet rs = stmt.executeQuery(query)) {

            resultTable.getColumns().clear();
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();

            for (int i = 1; i <= columnCount; i++) {
                final int colIndex = i;
                TableColumn<ObservableList<String>, String> col = new TableColumn<>(meta.getColumnName(i));
                col.setCellValueFactory(param -> new ReadOnlyStringWrapper(param.getValue().get(colIndex - 1)));
                resultTable.getColumns().add(col);
            }

            ObservableList<ObservableList<String>> data = FXCollections.observableArrayList();
            while (rs.next()) {
                ObservableList<String> row = FXCollections.observableArrayList();
                for (int i = 1; i <= columnCount; i++) {
                    row.add(rs.getString(i));
                }
                data.add(row);
            }

            resultTable.setItems(data);

        } catch (SQLException e) {
            e.printStackTrace();
            showAlert("Query Error", e.getMessage());
        }
    }

    private void loadTablesIntoTreeView(Connection conn) {
        try {
            TreeItem<String> root = new TreeItem<>("Tables");
            root.setExpanded(true);

            DatabaseMetaData meta = conn.getMetaData();
            ResultSet rs = meta.getTables(null, null, "%", new String[] { "TABLE" });
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                TreeItem<String> tableItem = new TreeItem<>(tableName);
                root.getChildren().add(tableItem);
            }

            tableTreeView.setRoot(root);

            tableTreeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null && newVal.isLeaf()) {
                    String selectedTable = newVal.getValue();
                    autoQueryTable(selectedTable);
                }
            });

        } catch (SQLException e) {
            e.printStackTrace();
            showAlert("Error", "Failed to load table list: " + e.getMessage());
        }
    }

    private void loadMdbSourcesList(Connection conn) {
        try {
            ObservableList<String> sources = FXCollections.observableArrayList();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt
                    .executeQuery("SELECT DISTINCT hfr_code, source_mdb FROM SecurityUsers ORDER BY hfr_code");
            while (rs.next()) {
                String hfr = rs.getString("hfr_code");
                String src = rs.getString("source_mdb");
                if (hfr != null && src != null) {
                    sources.add(hfr + " [ " + src + " ] ");
                }
            }
            mdbListView.setItems(sources);
        } catch (SQLException e) {
            e.printStackTrace();
            showAlert("Error", "Failed to load imported MDB sources: " + e.getMessage());
        }
    }

    private void autoQueryTable(String tableName) {
        String query = "SELECT * FROM \"" + tableName + "\"";
        queryArea.setText(query);
        executeQueryAndDisplay(query);
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
