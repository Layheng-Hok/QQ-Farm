package com.sustech.qqfarm.client;

import com.sustech.qqfarm.common.*;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.io.*;
import java.net.Socket;
import java.util.Optional;

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
    private int selectedPlotIndex = -1; // -1 means no plot selected

    @FXML
    public void initialize() {
        connectDialog();
        // Start network listener thread
        new Thread(this::listen).start();

        // Local timer to update "Growing" text countdowns immediately (visual only)
        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(1), ev -> {
            if (currentFarmState != null) {
                renderFarm(currentFarmState); // Re-render to update text
            }
        }));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

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

        if (msg.getCommand() == Command.UPDATE) {
            Farm f = (Farm) msg.getData();
            // If the update matches the farm we are currently looking at, refresh UI
            if (f.getOwner().equals(currentViewUser)) {
                currentFarmState = f;
                renderFarm(f);
            }
            return;
        }

        if (msg.getData() instanceof Farm) {
            currentFarmState = (Farm) msg.getData();
            renderFarm(currentFarmState);
        }
    }

    private void renderFarm(Farm farm) {
        lblUser.setText("Farm Owner: " + farm.getOwner());
        if(farm.getOwner().equals(myUsername)) {
            lblCoins.setText("Coins: " + farm.getCoins());
            btnMyFarm.setDisable(true);
        } else {
            lblCoins.setText("Coins: ???");
            btnMyFarm.setDisable(false);
        }

        gridFarm.getChildren().clear();
        for (int i = 0; i < farm.getPlots().size(); i++) {
            Plot p = farm.getPlots().get(i);
            StackPane plotPane = createPlotView(p, i, farm.getOwner());
            gridFarm.add(plotPane, i % 4, i / 4);
        }

        // Refresh button states based on selection and new farm data
        updateActionButtons();
    }

    private StackPane createPlotView(Plot p, int index, String owner) {
        StackPane stack = new StackPane();
        Rectangle rect = new Rectangle(80, 80);

        // Visual Selection Logic
        if (index == selectedPlotIndex) {
            rect.setStroke(Color.BLUE);
            rect.setStrokeWidth(3);
        } else {
            rect.setStroke(Color.BLACK);
            rect.setStrokeWidth(1);
        }

        Text statusText = new Text();
        long elapsed = System.currentTimeMillis() - p.getPlantedTime();
        boolean isReady = p.getState() == PlotState.GROWING && elapsed >= Plot.GROW_TIME_MS;

        if (p.getState() == PlotState.EMPTY) {
            rect.setFill(Color.SADDLEBROWN);
            statusText.setText("Empty");
        } else if (p.getState() == PlotState.RIPE || isReady) {
            rect.setFill(Color.GOLD);
            statusText.setText("RIPE");
        } else {
            rect.setFill(Color.LIGHTGREEN);
            int remaining = (int)((Plot.GROW_TIME_MS - elapsed)/1000);
            statusText.setText("Growing\n" + Math.max(0, remaining) + "s");
        }

        stack.getChildren().addAll(rect, statusText);

        // Click to SELECT only
        stack.setOnMouseClicked(e -> {
            selectedPlotIndex = index;
            // Force redraw to update border and buttons
            renderFarm(currentFarmState);
        });

        return stack;
    }

    private void updateActionButtons() {
        // Default: Disable all
        btnPlant.setDisable(true);
        btnHarvest.setDisable(true);
        btnSteal.setDisable(true);

        if (currentFarmState == null || selectedPlotIndex == -1) return;

        Plot p = currentFarmState.getPlots().get(selectedPlotIndex);
        long elapsed = System.currentTimeMillis() - p.getPlantedTime();
        boolean isReady = p.getState() == PlotState.GROWING && elapsed >= Plot.GROW_TIME_MS;
        boolean isRipe = p.getState() == PlotState.RIPE || isReady;

        boolean isMyFarm = currentFarmState.getOwner().equals(myUsername);

        if (isMyFarm) {
            if (p.getState() == PlotState.EMPTY) {
                btnPlant.setDisable(false);
            }
            if (isRipe) {
                btnHarvest.setDisable(false);
            }
        } else {
            // Visiting friend
            if (isRipe) {
                btnSteal.setDisable(false);
            }
        }
    }

    // --- Toolbar Actions ---

    @FXML
    public void onVisitClick() {
        String target = txtFriendName.getText();
        if (target != null && !target.isEmpty()) {
            selectedPlotIndex = -1; // Reset selection
            currentViewUser = target;
            send(Command.GET_FARM, null, target);
        }
    }

    @FXML
    public void onMyFarmClick() {
        selectedPlotIndex = -1; // Reset selection
        currentViewUser = myUsername;
        send(Command.GET_FARM, null, myUsername);
    }

    // --- Game Actions ---

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
        // Steal targets the whole farm owner, not a specific index usually,
        // but our UI implies selecting a ripe crop. The server logic
        // handles the "random" stealing or first ripe stealing.
        if (selectedPlotIndex != -1) {
            send(Command.STEAL, null, currentViewUser);
        }
    }
}
