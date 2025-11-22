package com.sustech.qqfarm.client;

import com.sustech.qqfarm.common.*;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;
import javafx.util.Duration;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.List;
import java.util.Optional;

public class QqFarmController {

    @FXML
    private Label lblUser;

    @FXML
    private Label lblCoins;

    @FXML
    private Label lblMessage;

    @FXML
    private GridPane gridFarm;

    @FXML
    private TextField txtFriendName;

    @FXML
    private Button btnMyFarm;

    // Action Buttons
    @FXML
    private Button btnPlant;

    @FXML
    private Button btnHarvest;

    @FXML
    private Button btnSteal;

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
    private static final int PLOT_SIZE = 48;

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

        if (msg.getCommand() == Command.GET_PLAYERS) {
            if (msg.isSuccess()) {
                @SuppressWarnings("unchecked")
                List<String> players = (List<String>) msg.getData();
                if (!players.isEmpty()) {
                    ChoiceDialog<String> dialog = new ChoiceDialog<>(players.get(0), players);
                    dialog.setTitle("Friends List");
                    dialog.setHeaderText("Select a friend to visit");
                    dialog.setContentText("Friend:");
                    Optional<String> result = dialog.showAndWait();
                    result.ifPresent(friend -> {
                        txtFriendName.setText(friend);
                        selectedPlotIndex = -1;
                        currentViewUser = friend;
                        send(Command.GET_FARM, null, friend);
                    });
                } else {
                    lblMessage.setText("No other players found.");
                }
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

        boolean isMyFarm = farm.getOwner().equals(myUsername);
        btnMyFarm.setText(isMyFarm ? "Friends" : "My Farm");

        gridFarm.getChildren().clear();

        for (int gridRow = 0; gridRow < 10; gridRow++) {
            for (int gridCol = 0; gridCol < 10; gridCol++) {
                if (gridRow >= 3 && gridRow <= 6 && gridCol >= 3 && gridCol <= 6) {
                    // Render plot
                    int plotIndex = (gridRow - 3) * 4 + (gridCol - 3);
                    Plot p = farm.getPlots().get(plotIndex);
                    StackPane plotPane = createPlotView(p, plotIndex);
                    gridFarm.add(plotPane, gridCol, gridRow);
                } else if (gridRow >= 1 && gridRow <= 8 && gridCol >= 1 && gridCol <= 8) {
                    // Render surrounding grass block (shifted by +1)
                    StackPane grassPane = createGrassBlock(gridRow - 1, gridCol - 1);
                    gridFarm.add(grassPane, gridCol, gridRow);
                } else {
                    // Render outer water tile
                    StackPane waterPane = createWaterBlock(gridRow, gridCol);
                    gridFarm.add(waterPane, gridCol, gridRow);
                }
            }
        }

        updateActionButtons();
    }

    private StackPane createGrassBlock(int gridRow, int gridCol) {
        StackPane stack = new StackPane();
        stack.setMinSize(PLOT_SIZE, PLOT_SIZE);
        stack.setPrefSize(PLOT_SIZE, PLOT_SIZE);
        stack.setMaxSize(PLOT_SIZE, PLOT_SIZE);

        ImageView grassView = loadImageView("tiles/grass.png", PLOT_SIZE);
        stack.getChildren().add(grassView);

        // Overlay some with flower, stone, weed, or fence
        String overlay1 = null;
        String overlay2 = null;

        if (gridRow == 0 && gridCol == 0) overlay2 = "back-left-fence.png";
        else if (gridRow == 0 && gridCol == 1) overlay2 = "back-center-fence.png";
        else if (gridRow == 0 && gridCol == 2) overlay2 = "back-center-fence.png";
        else if (gridRow == 0 && gridCol == 3) overlay2 = "back-center-fence.png";
        else if (gridRow == 0 && gridCol == 4) overlay2 = "back-center-fence.png";
        else if (gridRow == 0 && gridCol == 5) overlay2 = "back-center-fence.png";
        else if (gridRow == 0 && gridCol == 6) overlay2 = "back-center-fence.png";
        else if (gridRow == 0 && gridCol == 7) overlay2 = "back-right-fence.png";
        else if (gridRow == 1 && gridCol == 0) overlay2 = "center-left-fence.png";
        else if (gridRow == 1 && gridCol == 1) overlay1 = "cart.png";
        else if (gridRow == 1 && gridCol == 2) overlay1 = "weed.png";
        else if (gridRow == 1 && gridCol == 3) overlay1 = "weed.png";
        else if (gridRow == 1 && gridCol == 4) overlay1 = "weed.png";
        else if (gridRow == 1 && gridCol == 5) overlay1 = "weed.png";
        else if (gridRow == 1 && gridCol == 6) overlay1 = "flower.png";
        else if (gridRow == 1 && gridCol == 7) overlay2 = "center-right-fence.png";
        else if (gridRow == 2 && gridCol == 0) overlay2 = "center-left-fence.png";
        else if (gridRow == 2 && gridCol == 1) overlay1 = "weed.png";
        else if (gridRow == 2 && gridCol == 2) overlay1 = "weed.png";
        else if (gridRow == 2 && gridCol == 3) overlay1 = "weed.png";
        else if (gridRow == 2 && gridCol == 4) overlay1 = "weed.png";
        else if (gridRow == 2 && gridCol == 5) overlay1 = "weed.png";
        else if (gridRow == 2 && gridCol == 6) overlay1 = "weed.png";
        else if (gridRow == 2 && gridCol == 7) overlay2 = "center-right-fence.png";
        else if (gridRow == 3 && gridCol == 0) overlay2 = "center-left-fence.png";
        else if (gridRow == 3 && gridCol == 1) overlay1 = "weed.png";
        else if (gridRow == 3 && gridCol == 2) overlay1 = "weed.png";
        else if (gridRow == 3 && gridCol == 3) overlay1 = "weed.png";
        else if (gridRow == 3 && gridCol == 4) overlay1 = "weed.png";
        else if (gridRow == 3 && gridCol == 5) overlay1 = "weed.png";
        else if (gridRow == 3 && gridCol == 6) overlay1 = "weed.png";
        else if (gridRow == 3 && gridCol == 7) overlay2 = "center-right-fence.png";
        else if (gridRow == 4 && gridCol == 0) overlay2 = "center-left-fence.png";
        else if (gridRow == 4 && gridCol == 1) overlay1 = "flower.png";
        else if (gridRow == 4 && gridCol == 2) overlay1 = "weed.png";
        else if (gridRow == 4 && gridCol == 3) overlay1 = "weed.png";
        else if (gridRow == 4 && gridCol == 4) overlay1 = "weed.png";
        else if (gridRow == 4 && gridCol == 5) overlay1 = "weed.png";
        else if (gridRow == 4 && gridCol == 6) overlay1 = "weed.png";
        else if (gridRow == 4 && gridCol == 7) overlay2 = "center-right-fence.png";
        else if (gridRow == 5 && gridCol == 0) overlay2 = "center-left-fence.png";
        else if (gridRow == 5 && gridCol == 1) overlay1 = "weed.png";
        else if (gridRow == 5 && gridCol == 2) overlay1 = "weed.png";
        else if (gridRow == 5 && gridCol == 3) overlay1 = "weed.png";
        else if (gridRow == 5 && gridCol == 4) overlay1 = "weed.png";
        else if (gridRow == 5 && gridCol == 5) overlay1 = "weed.png";
        else if (gridRow == 5 && gridCol == 6) overlay1 = "weed.png";
        else if (gridRow == 5 && gridCol == 7) overlay2 = "center-right-fence.png";
        else if (gridRow == 6 && gridCol == 0) overlay2 = "center-left-fence.png";
        else if (gridRow == 6 && gridCol == 1) overlay1 = "weed.png";
        else if (gridRow == 6 && gridCol == 2) overlay1 = "weed.png";
        else if (gridRow == 6 && gridCol == 3) overlay1 = "stone.png";
        else if (gridRow == 6 && gridCol == 4) overlay1 = "stone.png";
        else if (gridRow == 6 && gridCol == 5) overlay1 = "weed.png";
        else if (gridRow == 6 && gridCol == 6) overlay1 = "weed.png";
        else if (gridRow == 6 && gridCol == 7) overlay2 = "center-right-fence.png";
        else if (gridRow == 7 && gridCol == 0) overlay2 = "front-left-fence.png";
        else if (gridRow == 7 && gridCol == 1) overlay2 = "back-center-fence.png";
        else if (gridRow == 7 && gridCol == 2) overlay2 = "back-center-fence.png";
        else if (gridRow == 7 && gridCol == 3) overlay1 = "stone.png";
        else if (gridRow == 7 && gridCol == 4) overlay1 = "stone.png";
        else if (gridRow == 7 && gridCol == 5) overlay2 = "back-center-fence.png";
        else if (gridRow == 7 && gridCol == 6) overlay2 = "back-center-fence.png";
        else if (gridRow == 7 && gridCol == 7) overlay2 = "front-right-fence.png";

        if (overlay1 != null) {
            ImageView overlayView = loadImageView("decorations/" + overlay1, PLOT_SIZE * 0.8);
            stack.getChildren().add(overlayView);
        }
        if (overlay2 != null) {
            ImageView overlayView = loadImageView("fences/" + overlay2, PLOT_SIZE);
            stack.getChildren().add(overlayView);
        }

        return stack;
    }

    private StackPane createWaterBlock(int gridRow, int gridCol) {
        StackPane stack = new StackPane();
        stack.setMinSize(PLOT_SIZE, PLOT_SIZE);
        stack.setPrefSize(PLOT_SIZE, PLOT_SIZE);
        stack.setMaxSize(PLOT_SIZE, PLOT_SIZE);

        ImageView waterView = loadImageView("tiles/water.png", PLOT_SIZE);
        stack.getChildren().add(waterView);

        String overlay = null;
        if (gridRow == 9 && gridCol == 4) overlay = "path1.png";
        else if (gridRow == 9 && gridCol == 5) overlay = "path2.png";
        else if (gridRow == 6 && gridCol == 9) overlay = "lilypad.png";
        else if (gridRow == 3 && gridCol == 0) overlay = "rock.png";

        if (overlay != null) {
            ImageView overlayView = loadImageView("decorations/" + overlay, PLOT_SIZE * 0.8);
            stack.getChildren().add(overlayView);
        }

        return stack;
    }

    /**
     * Creates the visual representation of a plot using images.
     */
    private StackPane createPlotView(Plot p, int index) {
        StackPane stack = new StackPane();
        stack.setMinSize(PLOT_SIZE, PLOT_SIZE);
        stack.setPrefSize(PLOT_SIZE, PLOT_SIZE);
        stack.setMaxSize(PLOT_SIZE, PLOT_SIZE);

        int row = index / 4; // 0-3
        int col = index % 4; // 0-3

        // 1. Determine Background Plot Image (100% Size)
        String plotImageName = getPlotImageName(row, col);
        ImageView bgView = loadImageView("plots/" + plotImageName, PLOT_SIZE);

        // 2. Determine Crop Overlay Image
        ImageView cropView = null;
        if (p.getState() != PlotState.EMPTY) {
            String cropImageName = getCropImageName(p, row);
            if (cropImageName != null) {
                cropView = loadImageView("crops/" + cropImageName, PLOT_SIZE * 0.5);
            }
        }

        // 3. Selection/Hover Indicator Overlay
        Rectangle selectionOverlay = new Rectangle(PLOT_SIZE, PLOT_SIZE);
        selectionOverlay.setMouseTransparent(true); // Important: Let clicks pass through to the StackPane
        selectionOverlay.setStrokeType(StrokeType.INSIDE);
        if (index == selectedPlotIndex) {
            // "Selected" effect: Strong highlight
            selectionOverlay.setFill(Color.rgb(255, 255, 255, 0.3));
            selectionOverlay.setStroke(Color.WHITE);
            selectionOverlay.setStrokeWidth(2);
        } else {
            // Default: Invisible
            selectionOverlay.setFill(Color.TRANSPARENT);
            selectionOverlay.setStroke(Color.TRANSPARENT);
        }

        // Add layers to stack (StackPane automatically centers children)
        stack.getChildren().add(bgView);
        if (cropView != null) {
            stack.getChildren().add(cropView);
        }
        stack.getChildren().add(selectionOverlay);

        // --- Event Handlers ---
        // Click: Select the plot
        stack.setOnMouseClicked(e -> {
            selectedPlotIndex = index;
            renderFarm(currentFarmState); // Re-render to update selection visuals
        });

        // Hover Enter: Show faint highlight if NOT selected
        stack.setOnMouseEntered(e -> {
            if (index != selectedPlotIndex) {
                selectionOverlay.setFill(Color.rgb(255, 255, 255, 0.15)); // Faint white
                selectionOverlay.setStroke(Color.rgb(255, 255, 255, 0.5)); // Faint border
                selectionOverlay.setStrokeWidth(1);
                stack.setCursor(Cursor.HAND); // Change cursor to hand
            }
        });

        // Hover Exit: Remove highlight if NOT selected
        stack.setOnMouseExited(e -> {
            if (index != selectedPlotIndex) {
                selectionOverlay.setFill(Color.TRANSPARENT);
                selectionOverlay.setStroke(Color.TRANSPARENT);
                stack.setCursor(Cursor.DEFAULT);
            }
        });

        return stack;
    }

    /**
     * Helper to load image resource and scale it for display.
     * Now accepts a specific size to allow for crop scaling.
     */
    private ImageView loadImageView(String relativePath, double size) {
        try {
            InputStream is = getClass().getResourceAsStream(ASSET_PATH + relativePath);
            if (is != null) {
                Image img = new Image(is);
                ImageView iv = new ImageView(img);
                // Updated scaling to passed size
                iv.setFitWidth(size);
                iv.setFitHeight(size);
                // Keep smooth false for crisp pixels
                iv.setSmooth(false);
                iv.setPreserveRatio(false);
                return iv;
            }
        } catch (Exception e) {
            System.err.println("Could not load image: " + relativePath);
        }
        return new ImageView();
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
        if (currentViewUser.equals(myUsername)) {
            send(Command.GET_PLAYERS, null, null);
        } else {
            selectedPlotIndex = -1;
            currentViewUser = myUsername;
            send(Command.GET_FARM, null, myUsername);
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
