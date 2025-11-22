package com.sustech.qqfarm.client;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

public class QqFarmApplication extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(QqFarmApplication.class.getResource("/com/sustech/qqfarm/qq-farm-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 480, 650);
        scene.getStylesheets().add(Objects.requireNonNull(QqFarmApplication.class.getResource("/com/sustech/qqfarm/styles.css")).toExternalForm());
        stage.setTitle("QQ Farm Game");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();
        // Close socket on exit
        stage.setOnCloseRequest(e -> System.exit(0));
    }

    public static void main(String[] args) {
        launch();
    }

}
