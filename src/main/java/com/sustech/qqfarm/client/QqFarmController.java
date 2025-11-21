package com.sustech.qqfarm.client;

import com.sustech.qqfarm.common.*;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;

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

    // Network
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private String myUsername;
    private String currentViewUser; // Whose farm are we looking at?

    // State
    private Farm currentFarmState;

    @FXML
    public void initialize() {
        connectDialog();
        // Start listener thread
        new Thread(this::listen).start();
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
            // Server push update
            Farm f = (Farm) msg.getData();
            // Only update if we are looking at this farm
            if (f.getOwner().equals(currentViewUser)) {
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
        // Only show coins if it's my farm (privacy?) - Requirement says "Surface coin balance"
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
    }

    private StackPane createPlotView(Plot p, int index, String owner) {
        StackPane stack = new StackPane();
        Rectangle rect = new Rectangle(80, 80);
        rect.setStroke(Color.BLACK);

        Text statusText = new Text();

        // Logic to handle local timer visualization
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
            statusText.setText("Growing\n" + (int)((Plot.GROW_TIME_MS - elapsed)/1000) + "s");
        }

        stack.getChildren().addAll(rect, statusText);

        // Interaction
        stack.setOnMouseClicked(e -> {
            if (owner.equals(myUsername)) {
                if (p.getState() == PlotState.EMPTY) {
                    send(Command.PLANT, index, null);
                } else if (p.getState() == PlotState.RIPE || isReady) {
                    send(Command.HARVEST, index, null);
                }
            } else {
                // Visiting someone else
                if (p.getState() == PlotState.RIPE || isReady) {
                    send(Command.STEAL, null, owner);
                }
            }
        });

        return stack;
    }

    // --- Toolbar Actions ---

    @FXML
    public void onVisitClick() {
        String target = txtFriendName.getText();
        if (target != null && !target.isEmpty()) {
            currentViewUser = target;
            send(Command.GET_FARM, null, target);
        }
    }

    @FXML
    public void onMyFarmClick() {
        currentViewUser = myUsername;
        send(Command.GET_FARM, null, myUsername);
    }

    @FXML
    public void onRefreshClick() {
        send(Command.GET_FARM, null, currentViewUser);
    }
}