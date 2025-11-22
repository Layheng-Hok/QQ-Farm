package com.sustech.qqfarm.client;

import animatefx.animation.*;
import com.sustech.qqfarm.common.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;
import javafx.scene.text.Text;
import javafx.util.Duration;
import java.io.*;
import java.net.Socket;
import java.util.*;

public class QqFarmController {
    @FXML private Label lblUser;
    @FXML private Label lblCoins;
    @FXML private Label lblMessage;
    @FXML private GridPane gridFarm;
    @FXML private TextField txtFriendName;
    @FXML private Button btnMyFarm;

    // Action Buttons
    @FXML private Button btnPlant;
    @FXML private Button btnHarvest;
    @FXML private Button btnSteal;

    // Network
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private String myUsername;
    private String currentViewUser;

    // State
    private Farm currentFarmState;
    private int selectedPlotIndex = -1;
    private int prevCoins = 0;
    private final StackPane[] plotPanes = new StackPane[16];

    @FXML
    public void initialize() {
        // Initialize grid styling
        for (int i = 0; i < 16; i++) {
            plotPanes[i] = new StackPane();
            plotPanes[i].setPrefSize(80, 80);
            // Default styling
            plotPanes[i].setStyle("-fx-background-color: #8B4513; -fx-background-radius: 10; -fx-border-color: #5D4037; -fx-border-radius: 10; -fx-border-width: 2;");

            final int index = i;
            plotPanes[i].setOnMouseClicked(e -> {
                selectedPlotIndex = index;
                renderFarm(currentFarmState);
            });
            gridFarm.add(plotPanes[i], i % 4, i / 4);
        }

        connectDialog();
        new Thread(this::listen).start();

        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(1), ev -> {
            if (currentFarmState != null) {
                renderFarm(currentFarmState);
            }
        }));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

    // --- VISUAL RENDERING LOGIC ---
    private void renderFarm(Farm farm) {
        if (farm == null) return;

        Platform.runLater(() -> {
            lblUser.setText("Farm Owner: " + farm.getOwner());
            boolean isMyFarm = farm.getOwner().equals(myUsername);
            btnMyFarm.setDisable(isMyFarm);

            for (int i = 0; i < farm.getPlots().size(); i++) {
                Plot p = farm.getPlots().get(i);
                StackPane pane = plotPanes[i];
                pane.getChildren().clear();

                // Create visual elements
                Rectangle bg = new Rectangle(70, 70);
                bg.setArcWidth(10);
                bg.setArcHeight(10);

                Text statusText = new Text();
                statusText.setStyle("-fx-font-weight: bold; -fx-fill: white; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.8), 2, 0, 0, 1);");

                if (p.getState() == PlotState.EMPTY) {
                    bg.setFill(Color.SADDLEBROWN);
                    statusText.setText("Empty");
                } else if (p.getState() == PlotState.RIPE || (p.getState() == PlotState.GROWING && p.isReadyToHarvest())) {
                    bg.setFill(Color.GOLD);
                    statusText.setText("RIPE");
                } else {
                    bg.setFill(Color.FORESTGREEN);
                    long elapsed = System.currentTimeMillis() - p.getPlantedTime();
                    int remaining = (int) ((Plot.GROW_TIME_MS - elapsed) / 1000);
                    statusText.setText(Math.max(0, remaining) + "s");
                }

                pane.getChildren().addAll(bg, statusText);

                // Selection Highlight
                if (i == selectedPlotIndex) {
                    pane.setStyle("-fx-border-color: #00BFFF; -fx-border-width: 4; -fx-border-radius: 10; -fx-effect: dropshadow(gaussian, #00BFFF, 10, 0.5, 0, 0);");
                } else {
                    pane.setStyle("-fx-border-color: #5D4037; -fx-border-width: 2; -fx-border-radius: 10;");
                }
            }
            updateActionButtons();
        });
    }

    private void updateActionButtons() {
        btnPlant.setDisable(true);
        btnHarvest.setDisable(true);
        btnSteal.setDisable(true);

        if (currentFarmState == null || selectedPlotIndex == -1) return;

        Plot p = currentFarmState.getPlots().get(selectedPlotIndex);
        boolean isReady = p.isReadyToHarvest() || p.getState() == PlotState.RIPE;
        boolean isMyFarm = currentFarmState.getOwner().equals(myUsername);

        if (isMyFarm) {
            if (p.getState() == PlotState.EMPTY) btnPlant.setDisable(false);
            if (isReady) btnHarvest.setDisable(false);
        } else {
            if (isReady) btnSteal.setDisable(false);
        }
    }
    // -----------------------------

    private void connectDialog() {
        TextInputDialog dialog = new TextInputDialog("player1");
        dialog.setTitle("Login");
        dialog.setHeaderText("Enter Username");
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            myUsername = name;
            currentViewUser = name;
            connectToServer();
            send(Command.LOGIN, name, null);
        });
    }

    private void connectToServer() {
        try {
            socket = new Socket("localhost", 6969);
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());
        } catch (IOException e) {
            lblMessage.setText("Connection failed!");
        }
    }

    private void send(Command cmd, Object data, String target) {
        try {
            NetMessage msg = new NetMessage(cmd);
            msg.setData(data);
            msg.setTargetUser(target);
            out.writeObject(msg);
            out.flush();
            out.reset();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void listen() {
        try {
            while (true) {
                Object obj = in.readObject();
                if (obj instanceof NetMessage) {
                    NetMessage msg = (NetMessage) obj;
                    Platform.runLater(() -> handleResponse(msg));
                }
            }
        } catch (Exception e) {
            Platform.runLater(() -> lblMessage.setText("Disconnected from server."));
        }
    }

    private void handleResponse(NetMessage msg) {
        if (msg.getMessage() != null) lblMessage.setText(msg.getMessage());

        if (msg.getUserCoins() != -1) {
            int newCoins = msg.getUserCoins();
            lblCoins.setText("Coins: " + newCoins);
            if (newCoins != prevCoins) {
                if (newCoins > prevCoins) {
                    new Flash(lblCoins).setSpeed(2.0).play();
                } else {
                    new Shake(lblCoins).setSpeed(2.0).play();
                }
                prevCoins = newCoins;
            }
        }

        List<Integer> newGrowing = new ArrayList<>();
        List<Integer> newRipe = new ArrayList<>();
        List<Integer> newEmpty = new ArrayList<>();

        Farm newFarm = null;
        if (msg.getData() instanceof Farm) {
            newFarm = (Farm) msg.getData();
        } else if (msg.getCommand() == Command.UPDATE && msg.getData() instanceof Farm) {
            newFarm = (Farm) msg.getData();
            if (!newFarm.getOwner().equals(currentViewUser)) {
                return;
            }
        }

        if (newFarm != null) {
            if (currentFarmState != null) {
                for (int i = 0; i < newFarm.getPlots().size(); i++) {
                    Plot oldP = currentFarmState.getPlots().get(i);
                    Plot newP = newFarm.getPlots().get(i);
                    if (oldP.getState() != newP.getState()) {
                        if (newP.getState() == PlotState.GROWING) {
                            newGrowing.add(i);
                        } else if (newP.getState() == PlotState.RIPE) {
                            newRipe.add(i);
                        } else if (newP.getState() == PlotState.EMPTY && oldP.getState() == PlotState.RIPE) {
                            newEmpty.add(i);
                        }
                    }
                }
            }
            currentFarmState = newFarm;
            renderFarm(currentFarmState);

            // Play animations for changes
            for (int i : newGrowing) new BounceIn(plotPanes[i]).play();
            for (int i : newRipe) new Flash(plotPanes[i]).play();
            for (int i : newEmpty) playHarvestAnimation(plotPanes[i], Color.GOLD);
        }

        // Command-specific animations using selectedPlotIndex
        if (msg.isSuccess()) {
            switch (msg.getCommand()) {
                case PLANT:
                    if(selectedPlotIndex != -1) new BounceIn(plotPanes[selectedPlotIndex]).play();
                    break;
                case HARVEST:
                    if(selectedPlotIndex != -1) {
                        playHarvestAnimation(plotPanes[selectedPlotIndex], Color.GOLD);
                        new Tada(btnHarvest).play();
                    }
                    break;
                case STEAL:
                    if(selectedPlotIndex != -1) {
                        playHarvestAnimation(plotPanes[selectedPlotIndex], Color.RED);
                        new Shake(btnSteal).play();
                    }
                    break;
            }
        }
    }

    private void playHarvestAnimation(StackPane plot, Color color) {
        for (int j = 0; j < 5; j++) {
            Circle particle = new Circle(5, color);
            particle.setOpacity(0.8);
            particle.setStrokeType(StrokeType.OUTSIDE);
            particle.setStroke(Color.YELLOW);
            particle.setStrokeWidth(1);
            plot.getChildren().add(particle);

            TranslateTransition tt = new TranslateTransition(Duration.seconds(1), particle);
            tt.setByY(-50);
            tt.setByX(Math.random() * 20 - 10);

            FadeTransition ft = new FadeTransition(Duration.seconds(1), particle);
            ft.setToValue(0);

            ParallelTransition pt = new ParallelTransition(tt, ft);
            pt.setOnFinished(e -> plot.getChildren().remove(particle));
            pt.play();
        }
    }

    // --- FIX: ADDED THE MISSING BUTTON HANDLERS BELOW ---

    @FXML
    public void onMyFarmClick() {
        selectedPlotIndex = -1;
        currentViewUser = myUsername;
        send(Command.GET_FARM, null, myUsername);
    }

    @FXML
    public void onVisitClick() {
        String target = txtFriendName.getText();
        if (target != null && !target.isEmpty()) {
            selectedPlotIndex = -1;
            currentViewUser = target;
            send(Command.GET_FARM, null, target);
        }
    }

    @FXML
    public void onPlantClick() {
        if (selectedPlotIndex != -1) {
            send(Command.PLANT, selectedPlotIndex, null);
        }
    }

    @FXML
    public void onHarvestClick() {
        if (selectedPlotIndex != -1) {
            send(Command.HARVEST, selectedPlotIndex, null);
        }
    }

    @FXML
    public void onStealClick() {
        if (selectedPlotIndex != -1) {
            send(Command.STEAL, selectedPlotIndex, currentViewUser);
        }
    }
}
