package com.example;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class Main extends Application {
    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/layout.fxml"));
        Scene scene = new Scene(loader.load());
        // scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        // scene.getStylesheets().add(getClass().getResource("/dark-theme.css").toExternalForm());
        MainController controller = loader.getController();
        controller.setScene(scene);

        stage.setTitle("MDB to SQLite Viewer");
        stage.setScene(scene);
        stage.setWidth(1000);
        stage.setHeight(700);
        stage.setMaximized(true);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}