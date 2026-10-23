package org.jai.fuelmarking.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.service.AuthService;
import org.jai.fuelmarking.service.ShiftService;
import org.jai.fuelmarking.session.Session;

/** Picks the site and user, then checks the PIN against the replicated hash (works offline). */
class LoginView extends VBox {
    LoginView(Runnable onSignedIn) {
        setAlignment(Pos.CENTER);
        setPadding(new Insets(40));
        getStyleClass().add("login-bg");
        VBox card = new VBox(12);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("login-card");
        card.setMaxWidth(Region.USE_PREF_SIZE);
        MasterDataDao master = new MasterDataDao();
        ComboBox<Site> site = new ComboBox<>();
        ComboBox<AppUser> user = new ComboBox<>();
        site.setPrefWidth(300); user.setPrefWidth(300);
        site.setMaxWidth(300); user.setMaxWidth(300);
        PasswordField pin = new PasswordField();
        pin.setPromptText("PIN");
        pin.setPrefWidth(300);
        pin.setMaxWidth(300);
        Button signIn = new Button("Sign in");
        signIn.setDefaultButton(true);
        Ui.primary(signIn);

        var sites = visibleSites(master);
        site.getItems().setAll(sites);
        site.valueProperty().addListener((o, a, s) -> {
            user.getItems().setAll(s == null ? java.util.List.of() : Tx.call(c -> master.users(c, s.id())));
        });
        // Master data keeps arriving from the web app while this screen is open, so re-read it when a list is opened.
        site.setOnShowing(e -> {
            var fresh = visibleSites(master);
            if (!fresh.equals(site.getItems())) {
                Site chosen = site.getValue();
                site.getItems().setAll(fresh);
                if (chosen != null) fresh.stream().filter(x -> x.id().equals(chosen.id())).findFirst().ifPresent(site::setValue);
            }
        });
        user.setOnShowing(e -> {
            Site s = site.getValue();
            if (s == null) return;
            var fresh = Tx.call(c -> master.users(c, s.id()));
            if (!fresh.equals(user.getItems())) {
                AppUser chosen = user.getValue();
                user.getItems().setAll(fresh);
                if (chosen != null) fresh.stream().filter(x -> x.id().equals(chosen.id())).findFirst().ifPresent(user::setValue);
            }
        });
        if (sites.isEmpty()) {
            card.getChildren().add(new Label("No marking sites yet. Master data is created in the web app and synced here. Open the Marking site list again once they arrive."));
        }
        signIn.setOnAction(e -> Ui.attempt(() -> {
            if (site.getValue() == null || user.getValue() == null) throw new IllegalArgumentException("Choose a site and a user");
            AppUser authenticated;
            try {
                authenticated = new AuthService().authenticate(user.getValue().id(), pin.getText());
            } finally {
                pin.clear();
            }
            Session s = Session.get();
            s.setSite(site.getValue());
            s.setUser(authenticated);
            s.setShift(new ShiftService().findOpen(site.getValue().id()).orElse(null));
            onSignedIn.run();
        }));
        Label title = new Label("Fuel Marking System");
        title.getStyleClass().add("title");
        Label sub = new Label("Sign in to continue");
        sub.getStyleClass().add("login-sub");
        signIn.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(signIn, new Insets(10, 0, 0, 0));
        card.getChildren().addAll(title, sub, new Label("MARKING SITE"), site, new Label("USER"), user, new Label("PIN"), pin, signIn);
        getChildren().add(card);
    }

    /**
     * An installation belongs to ONE site: it syncs with that site's key, and the central system refuses anything recorded for another
     * site. So when synchronisation is configured, only that site can be chosen here (master data lists them all).
     */
    private static java.util.List<Site> visibleSites(MasterDataDao master) {
        var all = Tx.call(master::sites);
        if (!org.jai.fuelmarking.config.YamlConfigurationLoader.Sync.isEnabled()) return all;
        String own = org.jai.fuelmarking.config.YamlConfigurationLoader.Sync.getSiteId();
        if (own == null || own.isBlank()) return all;
        return all.stream().filter(x -> x.id().toString().equalsIgnoreCase(own.trim())).toList();
    }
}
