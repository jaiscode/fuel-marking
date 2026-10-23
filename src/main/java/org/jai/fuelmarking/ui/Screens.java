package org.jai.fuelmarking.ui;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.jai.fuelmarking.session.Session;

/** Navigation between the login and main screens. */
public final class Screens {
    private Screens() {}

    public static Parent login(Stage stage) {
        return new LoginView(() -> stage.getScene().setRoot(new MainView(() -> {
            Session.get().setUser(null);
            Session.get().setShift(null);
            stage.getScene().setRoot(login(stage));
        })));
    }
}
