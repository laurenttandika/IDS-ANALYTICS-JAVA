package com.example;

import com.healthmarketscience.jackcess.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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
    @FXML
    private Button exportButton;

    private ObservableList<ObservableList<String>> currentResults = FXCollections.observableArrayList();
    private List<String> currentColumnHeaders = new ArrayList<>();

    private Connection sqliteConnection;
    private boolean freshImport = true;
    private ObservableList<String> failedImports = FXCollections.observableArrayList();
    private int totalMdbs = 0;
    private final AtomicInteger completedMdbs = new AtomicInteger(0);

    public void initialize() {
        mdbListView.setContextMenu(createMdbListContextMenu());
        failedImportsListView.setItems(failedImports);

        try {
            File dbFile = new File("converted.db");
            if (dbFile.exists()) {
                sqliteConnection = DriverManager.getConnection("jdbc:sqlite:converted.db");
                loadTablesIntoTreeView(sqliteConnection);
                loadMdbSourcesList(sqliteConnection);
                statusLabel.setText("✅ Loaded existing converted.db");
            } else {
                statusLabel.setText("ℹ️ No existing database found. Please import MDBs.");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            showAlert("Error", "Failed to load existing database: " + e.getMessage());
        }
    }

    public void onFreshImportClicked() {
        freshImport = true;
        openFileOrFolderForImport();
    }

    public void onMergeImportClicked() {
        freshImport = false;
        openFileOrFolderForImport();
    }

    private void openFileOrFolderForImport() {
        Alert optionDialog = new Alert(Alert.AlertType.CONFIRMATION);
        optionDialog.initOwner(mdbListView.getScene().getWindow()); // 👈 anchor to main window
        optionDialog.setTitle("Select Input Type");
        optionDialog.setHeaderText("Choose import source:");
        ButtonType fileButton = new ButtonType("Select Files");
        ButtonType folderButton = new ButtonType("Select Folder");
        optionDialog.getButtonTypes().setAll(fileButton, folderButton, ButtonType.CANCEL);

        Optional<ButtonType> result = optionDialog.showAndWait();
        if (result.isEmpty() || result.get() == ButtonType.CANCEL)
            return;

        List<File> allMdbs = new ArrayList<>();

        if (result.get() == fileButton) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select MDB Files");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("MDB files", "*.mdb"));
            List<File> files = chooser.showOpenMultipleDialog(null);
            if (files != null)
                allMdbs.addAll(files);
        } else {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Folder");
            File dir = chooser.showDialog(null);
            if (dir != null) {
                try {
                    Files.walk(dir.toPath())
                            .filter(path -> path.toString().toLowerCase().endsWith(".mdb"))
                            .map(Path::toFile)
                            .forEach(allMdbs::add);
                } catch (IOException e) {
                    showAlert("Error", "Failed to scan folder: " + e.getMessage());
                    return;
                }
            }
        }

        if (allMdbs.isEmpty())
            return;

        try {
            if (freshImport) {
                Files.deleteIfExists(Paths.get("converted.db"));
                sqliteConnection = DriverManager.getConnection("jdbc:sqlite:converted.db");
            } else {
                if (sqliteConnection == null || sqliteConnection.isClosed()) {
                    sqliteConnection = DriverManager.getConnection("jdbc:sqlite:converted.db");
                }
            }

            Platform.runLater(() -> {
                importProgress.setProgress(0);
                statusLabel.setText("Starting threaded import...");
            });

            startAutoMerge(allMdbs);

        } catch (Exception e) {
            showAlert("Error", "Failed to open database connection: " + e.getMessage());
        }
    }

    public void triggerImport(boolean isFresh) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select MDB Files");
        List<File> selectedFiles = chooser.showOpenMultipleDialog(null);
        if (selectedFiles == null || selectedFiles.isEmpty())
            return;

        if (isFresh) {
            resetConvertedDatabase();
        }

        startAutoMerge(selectedFiles);
    }

    private void resetConvertedDatabase() {
        try {
            if (sqliteConnection != null)
                sqliteConnection.close();
            File dbFile = new File("converted.db");
            if (dbFile.exists())
                dbFile.delete();
            sqliteConnection = DriverManager.getConnection("jdbc:sqlite:converted.db");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void startAutoMerge(List<File> mdbFiles) {
        this.totalMdbs = mdbFiles.size();
        completedMdbs.set(0);

        int threads = Math.min(mdbFiles.size(), Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        for (File mdbFile : mdbFiles) {
            executor.submit(() -> importIfNotExists(mdbFile));
        }

        executor.shutdown();

        new Thread(() -> {
            while (!executor.isTerminated()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }
            Platform.runLater(() -> {
                loadTablesIntoTreeView(sqliteConnection);
                loadMdbSourcesList(sqliteConnection);
                statusLabel.setText("✅ All MDB files imported.");
            });
        }).start();
    }

    private void importIfNotExists(File mdbFile) {
        try (Database mdb = DatabaseBuilder.open(mdbFile)) {
            Table configTable = mdb.getTable("tblConfig");
            Map<String, Integer> hfrCount = new HashMap<>();
            for (Row row : configTable) {
                String hfr = row.get("HFRCode") != null ? row.get("HFRCode").toString() : null;
                if (hfr != null)
                    hfrCount.put(hfr, hfrCount.getOrDefault(hfr, 0) + 1);
            }
            String hfrCode = hfrCount.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("UNKNOWN");

            synchronized (sqliteConnection) {
                if (isAlreadyImported(hfrCode)) {
                    String msg = hfrCode + " [ " + mdbFile.getName() + " ] already imported";
                    Platform.runLater(() -> failedImports.add(msg));
                    return;
                }

                try (Statement pragma = sqliteConnection.createStatement()) {
                    pragma.execute("PRAGMA synchronous = OFF");
                    pragma.execute("PRAGMA journal_mode = MEMORY");
                }

                MdbRecordManager.mergeMdbToSqlite(sqliteConnection, mdb, hfrCode, mdbFile.getName());
                Platform.runLater(() -> mdbListView.getItems().add(hfrCode + " [ " + mdbFile.getName() + " ]"));
            }

            int done = completedMdbs.incrementAndGet();
            double progress = (double) done / totalMdbs;

            Platform.runLater(() -> {
                importProgress.setProgress(progress);
                statusLabel.setText("Imported " + done + " / " + totalMdbs + " MDBs");
            });
        } catch (Exception e) {
            String msg = "Failed: [ " + mdbFile.getName() + " ] - " + e.getMessage();
            Platform.runLater(() -> failedImports.add(msg));
        }
    }

    private boolean isAlreadyImported(String hfrCode) {
        try {
            DatabaseMetaData meta = sqliteConnection.getMetaData();
            ResultSet tables = meta.getTables(null, null, "SecurityUsers", null);
            if (!tables.next())
                return false;

            PreparedStatement stmt = sqliteConnection.prepareStatement(
                    "SELECT 1 FROM SecurityUsers WHERE hfr_code = ? LIMIT 1");
            stmt.setString(1, hfrCode);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            return false;
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
                        String hfrCode = selected.split("\\s*\\[")[0].trim();
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
            currentResults.clear();
            currentColumnHeaders.clear();

            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();

            for (int i = 1; i <= columnCount; i++) {
                final int colIndex = i;
                String columnLabel = meta.getColumnLabel(i);
                currentColumnHeaders.add(columnLabel);

                TableColumn<ObservableList<String>, String> col = new TableColumn<>(columnLabel);
                col.setCellValueFactory(param -> new ReadOnlyStringWrapper(param.getValue().get(colIndex - 1)));
                resultTable.getColumns().add(col);
            }

            while (rs.next()) {
                ObservableList<String> row = FXCollections.observableArrayList();
                for (int i = 1; i <= columnCount; i++) {
                    row.add(rs.getString(i));
                }
                currentResults.add(row);
            }

            resultTable.setItems(currentResults);
            exportButton.setVisible(!currentResults.isEmpty());

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

    public void onExportClicked() {
        if (currentResults.isEmpty()) {
            showAlert("No Data", "There are no query results to export.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save CSV File");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files (*.csv)", "*.csv"));
        File file = chooser.showSaveDialog(null);

        if (file != null) {
            try (PrintWriter writer = new PrintWriter(file)) {
                writer.println(String.join(",", currentColumnHeaders));
                for (ObservableList<String> row : currentResults) {
                    writer.println(row.stream()
                            .map(val -> val != null ? "\"" + val.replace("\"", "\"\"") + "\"" : "")
                            .collect(Collectors.joining(",")));
                }

                showAlert("Success", "Query results exported successfully.");
            } catch (Exception e) {
                e.printStackTrace();
                showAlert("Error", "Failed to export CSV: " + e.getMessage());
            }
        }
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(mdbListView.getScene().getWindow()); // 👈 anchor to main window
        alert.setTitle(title);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
