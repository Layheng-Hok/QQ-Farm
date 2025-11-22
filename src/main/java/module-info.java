module com.sustech.qqfarm {
    requires javafx.controls;
    requires javafx.fxml;

    opens com.sustech.qqfarm.client to javafx.fxml;
    exports com.sustech.qqfarm.client;
    exports com.sustech.qqfarm.common;
}