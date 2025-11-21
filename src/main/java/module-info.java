module com.sustech.qqfarm {
    requires javafx.controls;
    requires javafx.fxml;


    opens com.sustech.qqfarm to javafx.fxml;
    exports com.sustech.qqfarm;
    opens com.sustech.qqfarm.client to javafx.fxml;
    exports com.sustech.qqfarm.client;
}