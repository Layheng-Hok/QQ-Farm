package com.sustech.qqfarm.client;

import com.sustech.qqfarm.common.*;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
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
    private int selectedPlotIndex = -1;

    // Asset Base Path
    private static final String ASSET_PATH = "/com/sustech/qqfarm/assets/";

    @FXML
    public void initialize() {
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
            lblCoins.setText("Coins: " + msg.getUserCoins());
        }

        if (msg.getCommand() == Command.UPDATE) {
            Farm f = (Farm) msg.getData();
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
            btnMyFarm.setDisable(true);
        } else {
            btnMyFarm.setDisable(false);
        }

        gridFarm.getChildren().clear();
        for (int i = 0; i < farm.getPlots().size(); i++) {
            Plot p = farm.getPlots().get(i);
            StackPane plotPane = createPlotView(p, i);
            gridFarm.add(plotPane, i % 4, i / 4);
        }

        updateActionButtons();
    }

    /**
     * Creates the visual representation of a plot using images.
     */
    private StackPane createPlotView(Plot p, int index) {
        StackPane stack = new StackPane();

        // FIX: Strictly enforce size to 64x64.
        // This prevents the Layout Manager from adding sub-pixel gaps.
        stack.setMinSize(64, 64);
        stack.setPrefSize(64, 64);
        stack.setMaxSize(64, 64);

        int row = index / 4; // 0-3
        int col = index % 4; // 0-3

        // 1. Determine Background Plot Image
        String plotImageName = getPlotImageName(row, col);
        ImageView bgView = loadImageView("plots/" + plotImageName);

        // 2. Determine Crop Overlay Image
        ImageView cropView = null;
        if (p.getState() != PlotState.EMPTY) {
            String cropImageName = getCropImageName(p, row);
            if (cropImageName != null) {
                cropView = loadImageView("crops/" + cropImageName);
            }
        }

        // 3. Selection Indicator (Border)
        Rectangle border = new Rectangle(64, 64);
        border.setFill(Color.TRANSPARENT);
        border.setMouseTransparent(true); // Ensure click goes to stack
        if (index == selectedPlotIndex) {
            border.setStroke(Color.BLUE);
            border.setStrokeWidth(3);
            border.setStrokeType(javafx.scene.shape.StrokeType.INSIDE);
        } else {
            border.setStroke(Color.TRANSPARENT);
        }

        // Add layers to stack
        stack.getChildren().add(bgView);
        if (cropView != null) {
            stack.getChildren().add(cropView);
        }
        stack.getChildren().add(border);

        // Click handler
        stack.setOnMouseClicked(e -> {
            selectedPlotIndex = index;
            renderFarm(currentFarmState);
        });

        return stack;
    }

    /**
     * Helper to load image resource and scale it for display.
     * Original images are 16x16, we scale to 64x64.
     */
    private ImageView loadImageView(String relativePath) {
        try {
            InputStream is = getClass().getResourceAsStream(ASSET_PATH + relativePath);
            if (is != null) {
                Image img = new Image(is);
                ImageView iv = new ImageView(img);
                iv.setFitWidth(64);
                iv.setFitHeight(64);

                // FIX: Disable smoothing for pixel art.
                // Smooth interpolation can blur edges, creating visible transparent "gaps" at seams.
                iv.setSmooth(false);

                // FIX: Disable preserve ratio if we are forcing fitWidth/Height exactly.
                // Sometimes aspect ratio calculation floats cause 63.99px result.
                iv.setPreserveRatio(false);

                return iv;
            }
        } catch (Exception e) {
            System.err.println("Could not load image: " + relativePath);
        }
        return new ImageView(); // Return empty if failed
    }

    private String getPlotImageName(int row, int col) {
        // Top Row (0)
        if (row == 0) {
            if (col == 0) return "top-left-plot.png";
            if (col == 3) return "top-right-plot.png";
            return "top-center-plot.png";
        }
        // Bottom Row (3)
        else if (row == 3) {
            if (col == 0) return "bottom-left-plot.png";
            if (col == 3) return "bottom-right-plot.png";
            return "bottom-center-plot.png";
        }
        // Middle Rows (1, 2)
        else {
            if (col == 0) return "center-left-plot.png";
            if (col == 3) return "center-right-plot.png";
            return "center-center-plot.png";
        }
    }

    private String getCropImageName(Plot p, int gridRow) {
        // Image assets rely on 1-based row index (row1...row4)
        int imgRow = gridRow + 1;
        long elapsed = System.currentTimeMillis() - p.getPlantedTime();
        boolean isReadyTimeWise = elapsed >= Plot.GROW_TIME_MS;

        // Check for RIPE
        if (p.getState() == PlotState.RIPE || (p.getState() == PlotState.GROWING && isReadyTimeWise)) {
            return "row" + imgRow + "-ripe.png";
        }

        // Check for GROWING stages
        if (p.getState() == PlotState.GROWING) {
            // Total time is 6000ms. Half is 3000ms.
            boolean isHalfGrown = elapsed >= (Plot.GROW_TIME_MS / 2);

            if (isHalfGrown) {
                return "row" + imgRow + "-growing-50.png";
            } else {
                return "row" + imgRow + "-growing-0.png";
            }
        }

        return null;
    }

    private void updateActionButtons() {
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
            if (isRipe) {
                btnSteal.setDisable(false);
            }
        }
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
    public void onMyFarmClick() {
        selectedPlotIndex = -1;
        currentViewUser = myUsername;
        send(Command.GET_FARM, null, myUsername);
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
