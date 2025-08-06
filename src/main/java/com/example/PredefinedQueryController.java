package com.example;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.sql.*;
import java.time.LocalDate;

public class PredefinedQueryController {

    private LocalDate startDate;
    private LocalDate endDate;

    @FXML
    private TableView<ObservableList<String>> resultTable;

    @FXML
    private TextArea queryArea;
    @FXML
    private Button exportButton;

    private Connection sqliteConnection;
    private MainController parentController;

    public void setSqliteConnection(Connection connection) {
        this.sqliteConnection = connection;
    }

    public void setQueryDisplay(TextArea area, TableView<ObservableList<String>> table, Button export,
            MainController pController) {
        this.queryArea = area;
        this.resultTable = table;
        this.exportButton = export;
        this.parentController = pController;
    }

    public void runPredefiendQuery(String queryType, LocalDate start, LocalDate end) {
        this.startDate = start;
        this.endDate = end;

        switch (queryType) {
            case "TX_NEW":
                this.executeTxNewQuery();
                break;
            case "HTS_TST":
                this.executeHtsTstQuery();
                break;
            default:
                // showAlert("Error", "Unknown predefined query.");
        }
    }

    public void executeTxNewQuery() {

        if (this.startDate == null || this.endDate == null)
            return;

        this.queryArea.setText("RUNNING TX NEW...");
        String query = "SELECT p.PatientID, p.sex, p.DateOfBirth, " +
                "(CAST(strftime('%Y', 'now') AS INTEGER) - CAST(strftime('%Y', p.DateOfBirth) AS INTEGER)) AS AGE, " +
                "p.dateconfirmedHIVPositive, v.VisitDate, v.ARVStatusCode, p.ReferredFromID AS 'TESTING POINT', " +
                "v.NowPregnant, v.NowBreastFeeding " +
                "FROM tblPatients p JOIN tblVisits v ON p.PatientID = v.PatientID " +
                "WHERE v.ARVStatusCode IN (2) " +
                "GROUP BY p.PatientID, p.sex, p.DateOfBirth, v.VisitDate, p.ReferredFromID, " +
                "p.dateconfirmedHIVPositive, v.NowPregnant, v.NowBreastFeeding, v.ARVStatusCode " +
                "HAVING MIN(v.VisitDate) BETWEEN ? AND ? " +
                "ORDER BY p.PatientID";

        this.queryArea.setText("OUTPUT TX NEW");
        executeQueryAndDisplay(query, this.startDate, this.endDate);
    }

    public void executeHtsTstQuery() {

        if (this.startDate == null || this.endDate == null)
            return;
        this.queryArea.setText("RUNNING HTS TST....");

        String query = "SELECT c.visitDate, c.ClientCode AS 'TESTING ID', " +
                "(CAST(strftime('%Y', 'now') AS INTEGER) - CAST(strftime('%Y', c.DateOfBirth) AS INTEGER)) AS Age, " +
                "c.AttendanceCode AS 'TYPE OF CLIENT', c.SexCode AS 'SEX', c.ReferredFromCode, " +
                "c.PregnancyStatusCode AS 'PMTCT STATUS', c.HIVResultCode AS 'HIV Result', " +
                "c.visitType AS 'Testing Modality', c.ClientType AS 'Source of Testing', " +
                "c.TestingType AS 'Testing Type', c.EQA, c.Remarks, " +
                "c.CondomsIssuedFemale, c.CondomsIssuedMale " +
                "FROM tblCT c " +
                "WHERE c.visitDate BETWEEN ? AND ? " +
                "AND c.TestingType NOT IN ('UU', 'ST') " +
                "AND c.TypeOfSampleID IN (1) " +
                "AND (c.visitType IN ('PITC','CBHTS','All') OR (c.visitType IN ('CITC') AND c.HIVResultCode IN ('CH'))) "
                +
                "GROUP BY c.ClientCode, c.DateOfBirth, c.AttendanceCode, c.SexCode, c.ReferredFromCode, " +
                "c.PregnancyStatusCode, c.HIVResultCode, c.visitType, c.ClientType, c.TestingType, c.EQA, " +
                "c.Remarks, c.CondomsIssuedFemale, c.CondomsIssuedMale, c.visitDate " +
                "ORDER BY c.ClientCode";

        this.queryArea.setText("OUTPUT HTS TST");
        executeQueryAndDisplay(query, this.startDate, this.endDate);
    }

    private void executeQueryAndDisplay(String query, LocalDate startDate, LocalDate endDate) {
        if (sqliteConnection == null)
            return;

        try (PreparedStatement stmt = sqliteConnection.prepareStatement(query)) {
            stmt.setString(1, startDate.toString());
            stmt.setString(2, endDate.toString());

            try (ResultSet rs = stmt.executeQuery()) {
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

                this.parentController.updateResults(data);
                this.exportButton.setVisible(!data.isEmpty());
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
