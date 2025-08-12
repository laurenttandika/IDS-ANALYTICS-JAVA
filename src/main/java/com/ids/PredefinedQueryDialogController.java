package com.ids;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

public class PredefinedQueryDialogController {

    @FXML
    private ComboBox<String> queryTypeComboBox;

    @FXML
    private DatePicker startDatePicker;

    @FXML
    private DatePicker endDatePicker;

    private String selectedQueryType;
    private LocalDate selectedStartDate;
    private LocalDate selectedEndDate;

    private Stage dialogStage;
    private boolean confirmed = false;

    public void initialize() {
        queryTypeComboBox.getItems().addAll(
                "TX_NEW",
                "HTS_TST",
                "HTS_SELF",
                "HTS_INDEX_ELICITATION",
                "TX_NEW_INDEX_CONTACTS",
                "TX_CURR_HVL_OFFERED_ACCEPTED",
                "TX_CURR_HVL_CONTACTS",
                "TX_CURR_HVL_OTHER",
                "TX_CURR_HVL_OTHER_CONTACTS",
                "TX_RTT_OFFERED_ACCEPTED",
                "TX_RTT_CONTACTS");
        queryTypeComboBox.getSelectionModel().selectFirst();
    }

    @FXML
    private void onConfirmClicked() {
        selectedQueryType = queryTypeComboBox.getValue();
        selectedStartDate = startDatePicker.getValue();
        selectedEndDate = endDatePicker.getValue();
        confirmed = true;

        if (dialogStage != null) {
            dialogStage.close();
        }
    }

    @FXML
    private void onCancelClicked() {
        confirmed = false;
        if (dialogStage != null) {
            dialogStage.close();
        }
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public void setDialogStage(Stage stage) {
        this.dialogStage = stage;
    }

    public String getSelectedQueryType() {
        return selectedQueryType;
    }

    public LocalDate getSelectedStartDate() {
        return selectedStartDate;
    }

    public LocalDate getSelectedEndDate() {
        return selectedEndDate;
    }

    // Optional: return all selections as a Map
    public Map<String, Object> getSelection() {
        Map<String, Object> map = new HashMap<>();
        map.put("queryType", selectedQueryType);
        map.put("startDate", selectedStartDate);
        map.put("endDate", selectedEndDate);
        return map;
    }
}