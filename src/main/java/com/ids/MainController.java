package com.ids;

import com.healthmarketscience.jackcess.*;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
    @FXML
    private TextField mdbSearchField;
    @FXML
    private Label importedCountLabel;
    @FXML
    private ToggleButton themeToggle;

    private Scene scene;
    private final String DARK_THEME = getClass().getResource("/dark-theme.css").toExternalForm();
    private final String LIGHT_THEME = getClass().getResource("/light-theme.css").toExternalForm();

    private ObservableList<ObservableList<String>> currentResults = FXCollections.observableArrayList();
    private List<String> currentColumnHeaders = new ArrayList<>();

    private Connection sqliteConnection;
    private boolean freshImport = true;
    private ObservableList<String> failedImports = FXCollections.observableArrayList();
    private int totalMdbs = 0;
    private final AtomicInteger completedMdbs = new AtomicInteger(0);

    private ObservableList<String> allMdbSources;
    private FilteredList<String> filteredMdbList;

    private PredefinedQueryController queryController = new PredefinedQueryController();
    private final java.util.concurrent.ScheduledExecutorService progressExec = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor();
    private java.util.concurrent.ScheduledFuture<?> progressTask;

    private Path appDataDir;
    private Path dbPath;

    // for dark mode/light
    public void setScene(Scene scene) {
        this.scene = scene;
        if (!scene.getStylesheets().contains(LIGHT_THEME)) {
            scene.getStylesheets().add(LIGHT_THEME); // default
        }
    }

    public void initialize() {
        // Context menu + failed imports
        mdbListView.setContextMenu(createMdbListContextMenu());
        failedImportsListView.setItems(failedImports);

        // Searchable "Imported MDBs" list
        allMdbSources = FXCollections.observableArrayList();
        filteredMdbList = new FilteredList<>(allMdbSources, s -> true);
        mdbListView.setItems(filteredMdbList);
        mdbSearchField.textProperty().addListener((obs, oldVal, newVal) -> filteredMdbList.setPredicate(
                item -> newVal == null || newVal.isBlank() || item.toLowerCase().contains(newVal.toLowerCase())));
        importedCountLabel.textProperty().bind(Bindings.size(allMdbSources).asString("( %d )"));

        // Theme toggle (hook scene safely after attach)
        themeToggle.setSelected(false);
        Platform.runLater(() -> {
            Scene sc = statusLabel.getScene(); // any node already in the scene works
            if (sc != null) {
                // ensure one theme present at start
                if (!sc.getStylesheets().contains(LIGHT_THEME))
                    sc.getStylesheets().add(LIGHT_THEME);
                themeToggle.setText("Dark Mode");
                themeToggle.setOnAction(e -> {
                    if (themeToggle.isSelected()) {
                        themeToggle.setText("Light Mode");
                        sc.getStylesheets().remove(LIGHT_THEME);
                        if (!sc.getStylesheets().contains(DARK_THEME))
                            sc.getStylesheets().add(DARK_THEME);
                    } else {
                        themeToggle.setText("Dark Mode");
                        sc.getStylesheets().remove(DARK_THEME);
                        if (!sc.getStylesheets().contains(LIGHT_THEME))
                            sc.getStylesheets().add(LIGHT_THEME);
                    }
                });
            }
        });

        // DB: only connect if file exists; otherwise just update status label.
        try {
            if (appDataDir == null)
                initStorage();

            File dbFile = dbPath.toFile();
            if (dbFile.exists()) {
                sqliteConnection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

                // ✅ Check if there are any tables in the DB
                try (ResultSet rs = sqliteConnection.getMetaData().getTables(null, null, "%",
                        new String[] { "TABLE" })) {
                    if (rs.next()) {
                        // There is at least one table → load data into UI
                        loadTablesIntoTreeView(sqliteConnection);
                        loadMdbSourcesList(sqliteConnection);
                        statusLabel.setText("✅ Loaded existing converted.db");
                    } else {
                        statusLabel.setText("ℹ️ Database is empty. Please import MDBs.");
                    }
                }
            } else {
                statusLabel.setText("ℹ️ No existing database found. Please import MDBs.");
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            showAlert("Error", "Failed to load existing database: " + ex.getMessage());
            statusLabel.setText("⚠️ Failed to load database.");
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
                // Files.deleteIfExists(Paths.get("converted.db"));
                // sqliteConnection = DriverManager.getConnection("jdbc:sqlite:converted.db");
                if (appDataDir == null)
                    initStorage();
                Files.deleteIfExists(dbPath);
                sqliteConnection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toString());
            } else {
                if (sqliteConnection == null || sqliteConnection.isClosed()) {
                    if (appDataDir == null)
                        initStorage();
                    sqliteConnection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toString());
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
            if (appDataDir == null)
                initStorage();
            Files.deleteIfExists(dbPath);
            sqliteConnection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toString());
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

            Platform.runLater(() -> {
                allMdbSources.setAll(sources); // ✅ safe update
            });

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

    @FXML
    public void onExportClicked() {
        // Use the TableView as the single source of truth
        if (resultTable.getItems() == null || resultTable.getItems().isEmpty()) {
            showAlert("No Data", "There are no query results to export.");
            return;
        }

        // Choose file
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save CSV File");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files (*.csv)", "*.csv"));
        File file = chooser.showSaveDialog(resultTable.getScene().getWindow());
        if (file == null)
            return;

        // Build header from column titles
        List<TableColumn<ObservableList<String>, ?>> cols = new ArrayList<>(resultTable.getColumns());
        String header = cols.stream()
                .map(c -> csvEscape(c.getText()))
                .collect(java.util.stream.Collectors.joining(","));

        try (java.io.PrintWriter out = new java.io.PrintWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
            out.println(header);

            // Each row is ObservableList<String> in your setup
            for (ObservableList<String> row : resultTable.getItems()) {
                String line = "";
                for (int i = 0; i < cols.size(); i++) {
                    // Row i-th value (defensive if sizes mismatch)
                    String val = (i < row.size() && row.get(i) != null) ? row.get(i) : "";
                    line += (i == 0 ? "" : ",") + csvEscape(val);
                }
                out.println(line);
            }
        } catch (Exception e) {
            showAlert("Export Error", e.getMessage());
        }
    }

    // Minimal CSV escaping: wrap in quotes and double any internal quotes
    private static String csvEscape(String s) {
        String v = (s == null) ? "" : s;
        if (v.contains("\"") || v.contains(",") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    public void onPredefinedQueryDialogClicked() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/PredefinedQueryDialog.fxml"));
            Parent dialogRoot = loader.load();

            PredefinedQueryDialogController controller = loader.getController();
            Stage dialogStage = new Stage();
            dialogStage.setTitle("Predefined Query");
            dialogStage.initModality(Modality.APPLICATION_MODAL);

            // Owner is current main stage
            Stage primaryStage = (Stage) queryArea.getScene().getWindow();
            dialogStage.initOwner(primaryStage);

            Scene dialogScene = new Scene(dialogRoot);
            dialogScene.getStylesheets().add(getClass().getResource("/dialog.css").toExternalForm());
            dialogStage.setScene(dialogScene);
            dialogStage.setWidth(300);
            dialogStage.setHeight(275);
            dialogStage.setResizable(false); // ✅ Prevent resizing
            dialogStage.centerOnScreen();

            controller.setDialogStage(dialogStage);
            dialogStage.showAndWait();

            if (controller.isConfirmed()) {
                String queryType = controller.getSelectedQueryType();
                LocalDate startDate = controller.getSelectedStartDate();
                LocalDate endDate = controller.getSelectedEndDate();

                if (startDate == null || endDate == null) {
                    showAlert("Missing Dates", "Please select both Start Date and End Date before running the query.");
                    return; // Stop execution
                }

                // Check if start date is after end date
                if (startDate.isAfter(endDate)) {
                    showAlert("Invalid Date Range", "Start Date cannot be after End Date.");
                    return;
                }

                // Set up connection and displays
                queryController.setSqliteConnection(this.sqliteConnection);
                queryController.setQueryDisplay(this.queryArea, this.resultTable, this.exportButton);

                // Show spinner / disable UI
                statusLabel.setText("Running query...");
                importProgress.setProgress(0); // indeterminate

                // Run in background thread
                new Thread(() -> {
                    try {
                        // Progress simulation
                        this.startSimulatedProgress();

                        queryController.runPredefiendQuery(queryType, startDate, endDate, () -> {
                            // stop simulator and fill bar when done
                            if (progressTask != null)
                                progressTask.cancel(true);
                            Platform.runLater(() -> {
                                importProgress.setProgress(1.0);
                                statusLabel.setText("Query complete.");
                            });
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> showAlert("Query Error", e.getMessage()));
                    } finally {
                        // Platform.runLater(() -> {
                        // statusLabel.setText("Query complete.");
                        // importProgress.setProgress(1.0);
                        // });
                    }
                }, "predefined-query-thread").start();
            }

        } catch (IOException e) {
            e.printStackTrace();
            showAlert("Dialog Error", "Failed to load predefined query dialog.");
        }
    }

    private void startSimulatedProgress() {
        // cancel any previous simulator
        if (progressTask != null && !progressTask.isDone()) {
            progressTask.cancel(true);
        }

        final double[] prog = { 0.0 };
        progressTask = progressExec.scheduleAtFixedRate(() -> {
            if (prog[0] >= 0.9)
                return; // cap at 90% until completion callback
            prog[0] += 0.05; // tick size
            double p = prog[0];
            Platform.runLater(() -> importProgress.setProgress(p));
        }, 0, 300, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(mdbListView.getScene().getWindow()); // 👈 anchor to main window
        alert.setTitle(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public void updateResults(ObservableList<ObservableList<String>> data) {
        currentResults = data;
    }

    private void initStorage() {
        appDataDir = getAppDataDir("IDS Analytics");
        try {
            Files.createDirectories(appDataDir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create app data dir: " + appDataDir, e);
        }
        dbPath = appDataDir.resolve("converted.db");

        // Make sure SQLite temp files go to a writable place too
        System.setProperty("org.sqlite.tmpdir", appDataDir.toString());
    }

    private static Path getAppDataDir(String appName) {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) {
            return Paths.get(System.getProperty("user.home"), "Library", "Application Support", appName);
        } else if (os.contains("win")) {
            String roaming = System.getenv("APPDATA");
            if (roaming != null && !roaming.isBlank()) {
                return Paths.get(roaming, appName);
            }
            return Paths.get(System.getProperty("user.home"), "AppData", "Roaming", appName);
        } else {
            // Linux / Unix
            return Paths.get(System.getProperty("user.home"), ".local", "share", appName);
        }
    }
}
