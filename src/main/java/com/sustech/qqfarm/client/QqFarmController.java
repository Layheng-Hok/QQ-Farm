package com.sustech.qqfarm.client;

import com.sustech.qqfarm.common.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Window;
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
    @FXML
    private Button btnDisconnect;
    @FXML
    private Button btnReconnect;
    // Network
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private String myUsername;
    private String currentViewUser;
    // State
    private Farm currentFarmState;
    private int selectedPlotIndex = -1;
    private int hoveredPlotIndex = -1; // NEW: Track which plot is being hovered
    private boolean isOwnerWatching;
    // Asset Base Path
    private static final String ASSET_PATH = "/com/sustech/qqfarm/assets/";
    private static final int PLOT_SIZE = 48;
    // Structure
    private boolean farmStructureBuilt = false;
    private ImageView[][] cropViews = new ImageView[4][4];
    private Rectangle[][] selectionOverlays = new Rectangle[4][4];
    private StackPane[][] plotPanes = new StackPane[4][4]; // Keep reference to set cursors
    private String[][] previousCropImages = new String[4][4];
    // Character
    private ImageView characterView;
    private Timeline characterAnimation;
    // Animation tracking
    private int previousCoins = -1;

    @FXML
    public void initialize() {
        connectDialog();
        new Thread(this::listen).start();
        // Handle clicks on the background to deselect
        gridFarm.setOnMouseClicked(e -> deselectPlot());
        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(1), ev -> {
            if (currentFarmState != null) {
                renderFarm(currentFarmState);
            }
        }));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
        updateConnectionButtons();
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
        if (socket == null || socket.isClosed()) {
            lblMessage.setText("No connection.");
            return;
        }
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
            Platform.runLater(() -> {
                lblMessage.setText("Disconnected from server.");
                updateConnectionButtons();
            });
        }
    }

    private void handleResponse(NetMessage msg) {
        if (msg.getMessage() != null) lblMessage.setText(msg.getMessage());

        // Update User's Coins (The viewer's coins)
        if (msg.getUserCoins() != -1) {
            int newCoins = msg.getUserCoins();
            if (previousCoins != -1 && previousCoins != newCoins) {
                int diff = newCoins - previousCoins;
                animateCoinChange(diff);
            }

            // Distinguish "My Coins" when visiting a friend
            String coinLabel = "Coins: ";
            if (currentViewUser != null && !currentViewUser.equals(myUsername)) {
                coinLabel = "My Coins: ";
            }
            lblCoins.setText(coinLabel + newCoins);

            previousCoins = newCoins;
        }

        if (msg.getCommand() == Command.UPDATE) {
            Farm f = (Farm) msg.getData();
            if (f.getOwner().equals(currentViewUser)) {
                currentFarmState = f;
                isOwnerWatching = msg.isOwnerWatching();
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
                    Window mainWindow = gridFarm.getScene().getWindow();
                    dialog.initOwner(mainWindow);
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
            isOwnerWatching = msg.isOwnerWatching();
            renderFarm(currentFarmState);
        }
    }

    private void animateCoinChange(int diff) {
        Text coinChangeText = new Text((diff > 0 ? "+" : "") + diff);
        coinChangeText.setFont(Font.font("System", FontWeight.BOLD, 14));
        coinChangeText.setFill(diff > 0 ? Color.web("#27ae60") : Color.web("#e74c3c"));
        coinChangeText.setStrokeWidth(1.5);
        StackPane parent = (StackPane) lblCoins.getParent();
        parent.getChildren().add(coinChangeText);
        coinChangeText.setTranslateY(0);
        coinChangeText.setTranslateX(50);
        TranslateTransition translateTransition = new TranslateTransition(Duration.millis(1000), coinChangeText);
        translateTransition.setByY(-40);
        FadeTransition fadeTransition = new FadeTransition(Duration.millis(1000), coinChangeText);
        fadeTransition.setFromValue(1.0);
        fadeTransition.setToValue(0.0);
        ScaleTransition scaleTransition = new ScaleTransition(Duration.millis(200), coinChangeText);
        scaleTransition.setFromX(0.5);
        scaleTransition.setFromY(0.5);
        scaleTransition.setToX(1.2);
        scaleTransition.setToY(1.2);
        ParallelTransition parallelTransition = new ParallelTransition(translateTransition, fadeTransition);
        SequentialTransition sequentialTransition = new SequentialTransition(scaleTransition, parallelTransition);
        sequentialTransition.setOnFinished(e -> parent.getChildren().remove(coinChangeText));
        sequentialTransition.play();
    }

    private void animateCropAppearance(ImageView cropView) {
        cropView.setOpacity(0);
        cropView.setScaleX(0.3);
        cropView.setScaleY(0.3);
        FadeTransition fadeTransition = new FadeTransition(Duration.millis(400), cropView);
        fadeTransition.setFromValue(0.0);
        fadeTransition.setToValue(1.0);
        ScaleTransition scaleTransition = new ScaleTransition(Duration.millis(400), cropView);
        scaleTransition.setFromX(0.3);
        scaleTransition.setFromY(0.3);
        scaleTransition.setToX(1.0);
        scaleTransition.setToY(1.0);
        scaleTransition.setInterpolator(Interpolator.EASE_OUT);
        ParallelTransition parallelTransition = new ParallelTransition(fadeTransition, scaleTransition);
        parallelTransition.play();
    }

    private void renderFarm(Farm farm) {
        // Show owner's coins if it is not my farm
        boolean isMyFarm = farm.getOwner().equals(myUsername);

        if (isMyFarm) {
            lblUser.setText("Farm Owner: " + farm.getOwner());
        } else {
            // Displays: "Farm Owner: player2, Coins: 40"
            lblUser.setText("Farm Owner: " + farm.getOwner() + ", Coins: " + farm.getCoins());
        }

        btnMyFarm.setText(isMyFarm ? "Friends" : "My Farm");

        if (!farmStructureBuilt) {
            gridFarm.getChildren().clear();
            for (int gridRow = 0; gridRow < 10; gridRow++) {
                for (int gridCol = 0; gridCol < 10; gridCol++) {
                    StackPane pane;
                    if (gridRow >= 3 && gridRow <= 6 && gridCol >= 3 && gridCol <= 6) {
                        // Plot
                        int row = gridRow - 3;
                        int col = gridCol - 3;
                        String plotImageName = getPlotImageName(row, col);
                        Image bgImg = loadAsset("plots/" + plotImageName);
                        ImageView bgView = new ImageView(bgImg);
                        bgView.setFitWidth(PLOT_SIZE);
                        bgView.setFitHeight(PLOT_SIZE);
                        bgView.setSmooth(false);
                        bgView.setPreserveRatio(false);
                        ImageView cropView = new ImageView();
                        cropView.setFitWidth(PLOT_SIZE * 0.5);
                        cropView.setFitHeight(PLOT_SIZE * 0.5);
                        cropView.setSmooth(false);
                        cropView.setPreserveRatio(false);
                        cropViews[row][col] = cropView;
                        Rectangle selOverlay = new Rectangle(PLOT_SIZE, PLOT_SIZE);
                        selOverlay.setMouseTransparent(true);
                        selOverlay.setFill(Color.TRANSPARENT);
                        selOverlay.setStroke(Color.TRANSPARENT);
                        selectionOverlays[row][col] = selOverlay;
                        pane = new StackPane(bgView, cropView, selOverlay);
                        pane.setMinSize(PLOT_SIZE, PLOT_SIZE);
                        pane.setPrefSize(PLOT_SIZE, PLOT_SIZE);
                        pane.setMaxSize(PLOT_SIZE, PLOT_SIZE);
                        plotPanes[row][col] = pane;
                        int index = row * 4 + col;
                        pane.setOnMouseClicked(e -> {
                            selectedPlotIndex = index;
                            updateSelections();
                            updateActionButtons();
                            updateNotification();
                            e.consume();
                        });

                        pane.setOnMouseEntered(e -> {
                            hoveredPlotIndex = index;
                            updateSelections();
                        });

                        pane.setOnMouseExited(e -> {
                            hoveredPlotIndex = -1;
                            updateSelections();
                        });
                    } else if (gridRow >= 1 && gridRow <= 8 && gridCol >= 1 && gridCol <= 8) {
                        // Grass
                        pane = createGrassBlock(gridRow - 1, gridCol - 1);
                    } else {
                        // Water
                        pane = createWaterBlock(gridRow, gridCol);
                    }
                    gridFarm.add(pane, gridCol, gridRow);
                }
            }
            farmStructureBuilt = true;
        }
        // Update dynamic parts with animation
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int index = row * 4 + col;
                Plot p = farm.getPlots().get(index);
                String cropImageName = getCropImageName(p, row);
                Image cropImg = cropImageName != null ? loadAsset("crops/" + cropImageName) : null;
                ImageView cropView = cropViews[row][col];
                String previousImage = previousCropImages[row][col];
                boolean isNewCrop = (previousImage == null || previousImage.isEmpty()) &&
                        cropImageName != null &&
                        cropImageName.contains("growing-0");
                cropView.setImage(cropImg);
                if (isNewCrop && cropImg != null) {
                    animateCropAppearance(cropView);
                }
                previousCropImages[row][col] = cropImageName;
            }
        }
        updateSelections();
        updateActionButtons();
        updateNotification();
        handleCharacterAnimation();
    }

    private void deselectPlot() {
        selectedPlotIndex = -1;
        updateSelections();
        updateActionButtons();
    }

    // MODIFIED: Handles Selected, Hovered, and Normal states in one place
    private void updateSelections() {
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int index = row * 4 + col;
                Rectangle sel = selectionOverlays[row][col];
                StackPane pane = plotPanes[row][col];
                if (index == selectedPlotIndex) {
                    // Selected State
                    sel.setFill(Color.rgb(255, 255, 255, 0.3));
                    sel.setStroke(Color.WHITE);
                    sel.setStrokeWidth(2);
                    if (pane != null) pane.setCursor(Cursor.DEFAULT);
                } else if (index == hoveredPlotIndex) {
                    // Hovered State
                    sel.setFill(Color.rgb(255, 255, 255, 0.15));
                    sel.setStroke(Color.rgb(255, 255, 255, 0.5));
                    sel.setStrokeWidth(1);
                    if (pane != null) pane.setCursor(Cursor.HAND);
                } else {
                    // Normal State
                    sel.setFill(Color.TRANSPARENT);
                    sel.setStroke(Color.TRANSPARENT);
                    sel.setStrokeWidth(0);
                    if (pane != null) pane.setCursor(Cursor.DEFAULT);
                }
            }
        }
    }

    private void handleCharacterAnimation() {
        boolean isMyFarm = currentFarmState.getOwner().equals(myUsername);
        if (!isMyFarm && isOwnerWatching) {
            if (characterView == null) {
                characterView = new ImageView();
                characterView.setFitWidth(PLOT_SIZE);
                characterView.setFitHeight(PLOT_SIZE);
                characterView.setSmooth(false);
                characterView.setPreserveRatio(false);
                characterView.setImage(loadAsset("characters/farmer1.png"));
                gridFarm.add(characterView, 7, 6);
            }
            if (characterAnimation == null) {
                startCharacterAnimation();
            }
        } else {
            if (characterView != null) {
                gridFarm.getChildren().remove(characterView);
                characterView = null;
            }
            if (characterAnimation != null) {
                characterAnimation.stop();
                characterAnimation = null;
            }
        }
    }

    private void startCharacterAnimation() {
        String[] sequence = {
                "characters/farmer1.png",
                "characters/farmer2.png",
                "characters/farmer1.png",
                "characters/farmer2.png",
                "characters/farmer1.png",
                "characters/farmer2.png",
                "characters/farmer1.png",
                "characters/farmer3.png",
                "characters/farmer4.png",
                "characters/farmer3.png",
                "characters/farmer4.png",
                "characters/farmer3.png",
                "characters/farmer4.png",
                "characters/farmer3.png",
                "characters/farmer3.png"
        };
        characterAnimation = new Timeline();
        characterAnimation.setCycleCount(Timeline.INDEFINITE);
        int i = 0;
        for (String path : sequence) {
            final Image img = loadAsset(path);
            KeyFrame kf = new KeyFrame(Duration.seconds(0.7 * i), event -> characterView.setImage(img));
            characterAnimation.getKeyFrames().add(kf);
            i++;
        }
        characterAnimation.play();
    }

    private Image loadAsset(String relativePath) {
        try {
            InputStream is = getClass().getResourceAsStream(ASSET_PATH + relativePath);
            if (is != null) {
                return new Image(is);
            }
        } catch (Exception e) {
            System.err.println("Could not load asset: " + relativePath);
        }
        return null;
    }

    private StackPane createGrassBlock(int gridRow, int gridCol) {
        StackPane stack = new StackPane();
        stack.setMinSize(PLOT_SIZE, PLOT_SIZE);
        stack.setPrefSize(PLOT_SIZE, PLOT_SIZE);
        stack.setMaxSize(PLOT_SIZE, PLOT_SIZE);
        ImageView grassView = new ImageView(loadAsset("tiles/grass.png"));
        grassView.setFitWidth(PLOT_SIZE);
        grassView.setFitHeight(PLOT_SIZE);
        grassView.setSmooth(false);
        grassView.setPreserveRatio(false);
        stack.getChildren().add(grassView);
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
            ImageView overlayView = new ImageView(loadAsset("decorations/" + overlay1));
            overlayView.setFitWidth(PLOT_SIZE * 0.8);
            overlayView.setFitHeight(PLOT_SIZE * 0.8);
            overlayView.setSmooth(false);
            overlayView.setPreserveRatio(false);
            stack.getChildren().add(overlayView);
        }
        if (overlay2 != null) {
            ImageView overlayView = new ImageView(loadAsset("fences/" + overlay2));
            overlayView.setFitWidth(PLOT_SIZE);
            overlayView.setFitHeight(PLOT_SIZE);
            overlayView.setSmooth(false);
            overlayView.setPreserveRatio(false);
            stack.getChildren().add(overlayView);
        }
        return stack;
    }

    private StackPane createWaterBlock(int gridRow, int gridCol) {
        StackPane stack = new StackPane();
        stack.setMinSize(PLOT_SIZE, PLOT_SIZE);
        stack.setPrefSize(PLOT_SIZE, PLOT_SIZE);
        stack.setMaxSize(PLOT_SIZE, PLOT_SIZE);
        ImageView waterView = new ImageView(loadAsset("tiles/water.png"));
        waterView.setFitWidth(PLOT_SIZE);
        waterView.setFitHeight(PLOT_SIZE);
        waterView.setSmooth(false);
        waterView.setPreserveRatio(false);
        stack.getChildren().add(waterView);
        double shadowOpacity = 0.3;
        double highlightOpacity = 0.3;
        double effectSize = PLOT_SIZE * 0.2;
        if (gridRow == 9 && gridCol >= 1 && gridCol <= 8) {
            Rectangle shadow = new Rectangle(PLOT_SIZE, effectSize, Color.rgb(0, 0, 0, shadowOpacity));
            StackPane.setAlignment(shadow, Pos.TOP_CENTER);
            stack.getChildren().add(shadow);
        }
        if (gridCol == 9 && gridRow >= 1 && gridRow <= 8) {
            Rectangle shadow = new Rectangle(effectSize, PLOT_SIZE, Color.rgb(0, 0, 0, shadowOpacity));
            StackPane.setAlignment(shadow, Pos.CENTER_LEFT);
            stack.getChildren().add(shadow);
        }
        if (gridRow == 0 && gridCol >= 1 && gridCol <= 8) {
            Rectangle highlight = new Rectangle(PLOT_SIZE, effectSize / 1.5, Color.rgb(255, 255, 255, highlightOpacity));
            StackPane.setAlignment(highlight, Pos.BOTTOM_CENTER);
            stack.getChildren().add(highlight);
        }
        if (gridCol == 0 && gridRow >= 1 && gridRow <= 8) {
            Rectangle highlight = new Rectangle(effectSize / 1.5, PLOT_SIZE, Color.rgb(255, 255, 255, highlightOpacity));
            StackPane.setAlignment(highlight, Pos.CENTER_RIGHT);
            stack.getChildren().add(highlight);
        }
        String overlay = null;
        if (gridRow == 9 && gridCol == 4) overlay = "path1.png";
        else if (gridRow == 9 && gridCol == 5) overlay = "path2.png";
        else if (gridRow == 6 && gridCol == 9) overlay = "lilypad.png";
        else if (gridRow == 3 && gridCol == 0) overlay = "rock.png";
        if (overlay != null) {
            ImageView overlayView = new ImageView(loadAsset("decorations/" + overlay));
            overlayView.setFitWidth(PLOT_SIZE * 0.8);
            overlayView.setFitHeight(PLOT_SIZE * 0.8);
            overlayView.setSmooth(false);
            overlayView.setPreserveRatio(false);
            stack.getChildren().add(overlayView);
        }
        return stack;
    }

    private String getPlotImageName(int row, int col) {
        if (row == 0) {
            if (col == 0) return "top-left-plot.png";
            if (col == 3) return "top-right-plot.png";
            return "top-center-plot.png";
        } else if (row == 3) {
            if (col == 0) return "bottom-left-plot.png";
            if (col == 3) return "bottom-right-plot.png";
            return "bottom-center-plot.png";
        } else {
            if (col == 0) return "center-left-plot.png";
            if (col == 3) return "center-right-plot.png";
            return "center-center-plot.png";
        }
    }

    private String getCropImageName(Plot p, int gridRow) {
        int imgRow = gridRow + 1;
        long elapsed = System.currentTimeMillis() - p.getPlantedTime();
        boolean isReadyTimeWise = elapsed >= Plot.GROW_TIME_MS;
        if (p.getState() == PlotState.STOLEN) {
            return "row" + imgRow + "-stolen.png";
        }
        if (p.getState() == PlotState.RIPE || (p.getState() == PlotState.GROWING && isReadyTimeWise)) {
            return "row" + imgRow + "-ripe.png";
        }
        if (p.getState() == PlotState.GROWING) {
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
            if (isRipe || p.getState() == PlotState.STOLEN) {
                btnHarvest.setDisable(false);
            }
        } else {
            if (isRipe) {
                btnSteal.setDisable(false);
            }
        }
    }

    private void updateNotification() {
        if (selectedPlotIndex == -1 || currentFarmState == null) return;
        Plot p = currentFarmState.getPlots().get(selectedPlotIndex);
        boolean isMyFarm = currentFarmState.getOwner().equals(myUsername);
        PlotState state = p.getState();
        String message;
        if (state == PlotState.EMPTY) {
            message = isMyFarm ? "Empty plot, plant your seed!" : "Empty plot.";
        } else if (state == PlotState.STOLEN) {
            message = isMyFarm ? "Your crop was stolen, harvest to clean up!" : "This crop has been stolen.";
        } else if (state == PlotState.RIPE || (state == PlotState.GROWING && p.isReadyToHarvest())) {
            message = isMyFarm ? "Your crop is ripe, harvest your fruits!" : "Ripe crop, steal it!";
        } else {
            message = isMyFarm ? "Your crop is growing, wait patiently!" : "Crop is growing, not ready yet.";
        }
        lblMessage.setText(message);
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
            deselectPlot();
        }
    }

    @FXML
    public void onHarvestClick() {
        if (selectedPlotIndex != -1) {
            send(Command.HARVEST, selectedPlotIndex, null);
            deselectPlot();
        }
    }

    @FXML
    public void onStealClick() {
        if (selectedPlotIndex != -1) {
            send(Command.STEAL, selectedPlotIndex, currentViewUser);
            deselectPlot();
        }
    }

    @FXML
    public void onDisconnectClick() {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
                lblMessage.setText("Simulated disconnect. UI remains responsive.");
                updateConnectionButtons();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @FXML
    public void onReconnectClick() {
        if (socket == null || socket.isClosed()) {
            connectToServer();
            if (socket != null && !socket.isClosed()) {
                new Thread(this::listen).start();
                send(Command.LOGIN, myUsername, null);
                send(Command.GET_FARM, null, currentViewUser);
                updateConnectionButtons();
            } else {
                lblMessage.setText("Reconnection failed.");
            }
        }
    }

    private void updateConnectionButtons() {
        boolean connected = socket != null && !socket.isClosed();
        btnDisconnect.setDisable(!connected);
        btnReconnect.setDisable(connected);
    }
}
