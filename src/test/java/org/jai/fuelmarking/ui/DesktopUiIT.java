package org.jai.fuelmarking.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.hardware.Hardware;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.model.Role;
import org.jai.fuelmarking.security.PinHasher;
import org.jai.fuelmarking.session.Session;
import org.junit.jupiter.api.*;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the real JavaFX screens (no human, no dialogs) through the complete operating day, and writes a PNG of each
 * screen to target/ui-shots. Scenes are rendered off-screen, so nothing appears on the desktop while this runs.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DesktopUiIT {
    static final String N = UUID.randomUUID().toString().substring(0, 6);
    static final String PIN = "246810";
    static final File SHOTS = new File("target/ui-shots");

    static Site siteA = new Site(UUID.randomUUID(), "UA" + N, "Alpha Site " + N, "FIXED");
    static Site siteB = new Site(UUID.randomUUID(), "UB" + N, "Bravo Site " + N, "FIXED");
    static Omc omc = new Omc(UUID.randomUUID(), "UO" + N, "UI Oil " + N);
    static final Map<String, AppUser> users = new HashMap<>();

    // what the stubbed dialogs saw
    static final List<String> errors = new CopyOnWriteArrayList<>();
    static final List<String> infos = new CopyOnWriteArrayList<>();
    static final List<String> confirms = new CopyOnWriteArrayList<>();
    static final List<String> signPrompts = new CopyOnWriteArrayList<>();
    static volatile boolean confirmAnswer = true;
    static volatile boolean signs = true;

    // ------------------------------------------------------------------ plumbing

    static <T> T fx(Callable<T> c) throws Exception {
        if (Platform.isFxApplicationThread()) return c.call();
        FutureTask<T> f = new FutureTask<>(c);
        Platform.runLater(f);
        try { return f.get(60, TimeUnit.SECONDS); }
        catch (ExecutionException e) { throw e.getCause() instanceof Exception ex ? ex : e; }
    }

    static void fx(Runnable r) throws Exception { fx(() -> { r.run(); return null; }); }

    static <T extends Node> List<T> all(Node root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T extends Node> void collect(Node n, Class<T> type, List<T> out) {
        if (type.isInstance(n)) out.add(type.cast(n));
        if (n instanceof ScrollPane sp && sp.getContent() != null) collect(sp.getContent(), type, out);
        else if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) collect(c, type, out);
    }

    /** True when the node and every parent above it are visible (a hidden form hides its buttons without changing their own flag). */
    static boolean showing(Node n) {
        for (Node x = n; x != null; x = x.getParent()) if (!x.isVisible()) return false;
        return true;
    }

    static Button button(Node root, String text) {
        return all(root, Button.class).stream().filter(b -> text.equals(b.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no button '" + text + "'; have " + all(root, Button.class).stream().map(Button::getText).toList()));
    }

    static <T> T lastOf(List<T> l) { return l.get(l.size() - 1); }

    static void click(Node root, String text) throws Exception { fx(() -> button(root, text).fire()); }

    static Tab tab(TabPane tabs, String title) {
        return tabs.getTabs().stream().filter(t -> title.equals(t.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no tab '" + title + "'; have " + tabs.getTabs().stream().map(Tab::getText).toList()));
    }

    static Node open(TabPane tabs, String title) throws Exception {
        return fx(() -> {
            Tab t = tab(tabs, title);
            tabs.getSelectionModel().select(t);
            // a shown window lays itself out; an off-screen scene needs a nudge before the tab's content is attached
            if (tabs.getScene() != null) { tabs.getScene().getRoot().applyCss(); tabs.getScene().getRoot().layout(); }
            return t.getContent();
        });
    }

    static List<String> tabTitles(TabPane tabs) { return tabs.getTabs().stream().map(Tab::getText).toList(); }

    static void type(TextInputControl field, String text) throws Exception { fx(() -> field.setText(text)); }

    static void shot(String name, Scene scene) throws Exception {
        fx(() -> {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            WritableImage img = scene.snapshot(null);
            SHOTS.mkdirs();
            try { ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(SHOTS, name + ".png")); }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
        });
    }

    static long count(String sql, Object... args) {
        return Tx.call(c -> {
            try (var ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
            }
        });
    }

    static double scalar(String sql, Object... args) {
        return Tx.call(c -> {
            try (var ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getDouble(1); }
            }
        });
    }

    static void waitUntil(String what, Callable<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < end) {
            if (fx(condition)) return;
            Thread.sleep(50);
        }
        fail("timed out waiting for " + what);
    }

    static void resetCapture() { errors.clear(); infos.clear(); confirms.clear(); signPrompts.clear(); confirmAnswer = true; signs = true; }

    static AppUser user(java.sql.Connection c, String username, String fullName, Role role, Site site) throws java.sql.SQLException {
        AppUser u = new AppUser(UUID.randomUUID(), username + N, fullName, role, site.id());
        try (var ps = c.prepareStatement("INSERT INTO app_users(id, username, full_name, role, site_id, pin_hash) VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, u.id()); ps.setString(2, u.username()); ps.setString(3, fullName); ps.setString(4, role.name());
            ps.setObject(5, site.id()); ps.setString(6, PinHasher.hash(PIN));
            ps.executeUpdate();
        }
        return u;
    }

    /** Sign in through the real login screen; returns the main window's content. */
    static MainView signIn(Site site, String userKey, String pin) throws Exception {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        LoginView login = fx(() -> new LoginView(() -> done.complete(true)));
        Scene scene = fx(() -> { Scene sc = new Scene(login, 1200, 800); Ui.applyTheme(sc); return sc; });
        fx(() -> {
            @SuppressWarnings("unchecked") ComboBox<Site> siteBox = all(login, ComboBox.class).get(0);
            @SuppressWarnings("unchecked") ComboBox<AppUser> userBox = all(login, ComboBox.class).get(1);
            siteBox.getSelectionModel().select(siteBox.getItems().stream().filter(s -> s.id().equals(site.id())).findFirst().orElseThrow());
            userBox.getSelectionModel().select(userBox.getItems().stream().filter(u -> u.id().equals(users.get(userKey).id())).findFirst().orElseThrow());
            all(login, PasswordField.class).get(0).setText(pin);
        });
        shot("login", scene);
        click(login, "Sign in");
        if (!done.isDone()) return null;           // rejected: caller inspects errors
        MainView main = fx(() -> new MainView(() -> { }));
        fx(() -> { Scene sc = new Scene(main, 1200, 800); Ui.applyTheme(sc); return sc; });
        return main;
    }

    static TabPane tabsOf(MainView main) throws Exception { return fx(() -> (TabPane) main.getCenter()); }

    static void signOut(MainView main) throws Exception { fx(() -> { Session.get().setUser(null); Session.get().setShift(null); }); }

    // ------------------------------------------------------------------ set-up

    @BeforeAll
    static void start() throws Exception {
        try { Platform.startup(() -> { }); } catch (IllegalStateException alreadyRunning) { /* toolkit already up */ }
        Platform.setImplicitExit(false);
        Ui.useDialogs(new Ui.Dialogs() {
            @Override public void error(String msg) { errors.add(msg); }
            @Override public void info(String msg) { infos.add(msg); }
            @Override public boolean confirm(String msg) { confirms.add(msg); return confirmAnswer; }
        });
        Hardware.SignaturePad pad = prompt -> { signPrompts.add(prompt); return signs ? new byte[]{1, 2, 3} : null; };
        Ui.useSignaturePad(pad);

        Tx.run(c -> {
            MasterDataDao m = new MasterDataDao();
            m.upsertSite(c, siteA); m.upsertSite(c, siteB); m.upsertOmc(c, omc);
            users.put("sup", user(c, "sup", "Sam Supervisor", Role.SUPERVISOR, siteA));
            users.put("dose", user(c, "dose", "Dana Dosing", Role.DOSING_OPERATOR, siteA));
            users.put("disp", user(c, "disp", "Dev Dispense", Role.DISPENSE_OPERATOR, siteA));
            users.put("wh", user(c, "wh", "Wendy Warehouse", Role.WAREHOUSE_MANAGER, siteA));
            users.put("supB", user(c, "supb", "Bea Bravo", Role.SUPERVISOR, siteB));
            return null;
        });
    }

    @AfterAll
    static void stop() { Ui.useDialogs(null); Ui.useSignaturePad(null); }

    @BeforeEach
    void clean() { resetCapture(); }

    // ------------------------------------------------------------------ the tests

    @Test @Order(1)
    void loginChecksThePinAndTellsThePersonWhatHappened() throws Exception {
        assertNull(signIn(siteA, "sup", "000001"));
        assertTrue(errors.get(0).startsWith("Incorrect PIN (4 attempt(s) left)"), errors.toString());
        assertNull(Session.get().user(), "a wrong PIN must not sign anyone in");
        errors.clear();
        MainView main = signIn(siteA, "sup", PIN);
        assertNotNull(main);
        assertEquals(users.get("sup").id(), Session.get().user().id());
    }

    @Test @Order(2)
    void everyRoleSeesExactlyItsOwnScreens() throws Exception {
        record Case(String key, List<String> tabs) {}
        for (Case c : List.of(
                new Case("sup", List.of("Shift", "Reconcile", "Open jerrican", "Close jerrican", "Declare empty", "Transfer jerrican", "Receive jerrican", "Register truck", "Verify", "Incidents", "Inventory", "Reports")),
                new Case("dose", List.of("Dose", "Incidents", "Inventory")),
                new Case("disp", List.of("Dispense", "Incidents")),
                new Case("wh", List.of("Receive jerrican", "Refill jerrican", "Transfer jerrican", "Receive returns", "Receive IBC / drum", "Stock count", "Inventory", "Incidents", "Reports")))) {
            MainView main = signIn(siteA, c.key(), PIN);
            assertEquals(c.tabs(), tabTitles(tabsOf(main)), c.key());
            // the same screens are listed down the left, each with an icon, and choosing one opens it
            List<ToggleButton> nav = fx(() -> all(main, ToggleButton.class).stream().filter(t -> t.getStyleClass().contains("nav-item")).toList());
            List<String> visible = fx(() -> nav.stream().filter(Node::isVisible).map(ToggleButton::getText).toList());
            assertEquals(c.key().equals("sup") ? List.of("Shift", "Reports") : c.tabs(), visible, c.key() + " sidebar (a supervisor has no shift yet)");
            assertEquals(c.tabs(), fx(() -> nav.stream().map(ToggleButton::getText).toList()), c.key() + " all items exist");
            assertTrue(fx(() -> nav.stream().allMatch(t -> t.getGraphic() != null)), "every item has an icon");
            assertTrue(fx(() -> nav.get(0).isSelected()), "the first screen is highlighted");
            assertTrue(fx(() -> nav.get(0).isVisible()));
            ToggleButton last = nav.stream().filter(Node::isVisible).reduce((x, y) -> y).orElseThrow();
            TabPane tp = tabsOf(main);
            fx(() -> last.fire());
            assertEquals(last.getText(), fx(() -> tp.getSelectionModel().getSelectedItem().getText()), "clicking an item opens its screen");
            assertTrue(fx(() -> last.isSelected() && !nav.get(0).isSelected()));
            fx(() -> last.fire());
            assertTrue(fx(() -> last.isSelected()), "a screen stays chosen when clicked again");
            fx(() -> tp.getSelectionModel().select(0));
            assertTrue(fx(() -> nav.get(0).isSelected()), "the highlight follows when the screen is opened another way");
            signOut(main);
        }
    }

    @Test @Order(3)
    void supervisorRunsTheMorningAndRegistersATruck() throws Exception {
        MainView main = signIn(siteA, "sup", PIN);
        TabPane tabs = tabsOf(main);
        Scene scene = main.getScene();

        // the jerrican was registered at head office, received empty at the warehouse and refilled and sealed there
        var whUser = users.get("wh");
        var cs = new org.jai.fuelmarking.service.ContainerService();
        org.jai.fuelmarking.Fixtures.ibc(cs, whUser, siteA, "UISRC-" + N, 900_000);
        org.jai.fuelmarking.Fixtures.plastic(cs, whUser, siteA, "UIJ-" + N, 1000, 1000, "UISRC-" + N);
        assertFalse(tabTitles(tabs).contains("Refill jerrican") || tabTitles(tabs).contains("Receive IBC / drum"), "marking sites take jerricans in through transfers, not from suppliers");

        // before the shift starts a supervisor can only start one or look at reports: every other screen is hidden and locked ...
        List<ToggleButton> nav = fx(() -> all(main, ToggleButton.class).stream().filter(t -> t.getStyleClass().contains("nav-item")).toList());
        assertEquals(List.of("Shift", "Reports"), fx(() -> nav.stream().filter(Node::isVisible).map(ToggleButton::getText).toList()), "only Shift and Reports before the shift");
        assertTrue(fx(() -> tabs.getTabs().stream().filter(t -> !List.of("Shift", "Reports").contains(t.getText())).allMatch(Tab::isDisable)), "the other screens are locked");
        assertEquals("Shift", fx(() -> tabs.getSelectionModel().getSelectedItem().getText()));
        // ... and the services refuse too
        assertTrue(assertThrows(IllegalStateException.class, () -> cs.openJerrican(users.get("sup"), siteA, null, "UIJ-" + N, org.jai.fuelmarking.Fixtures.seal("UIJ-" + N), "", new byte[]{1, 2, 3}))
                .getMessage().contains("Start a shift first"));
        assertTrue(assertThrows(IllegalStateException.class, () -> new org.jai.fuelmarking.service.IncidentService().report(users.get("sup"), siteA, null, null, "SPILLAGE", 5, "x"))
                .getMessage().contains("Start a shift first"), "even an incident needs a shift for a supervisor");

        Node shift = open(tabs, "Shift");
        click(shift, "Start shift");
        assertNotNull(Session.get().shift());
        assertEquals(List.of("Shift", "Reconcile", "Open jerrican", "Close jerrican", "Declare empty", "Transfer jerrican", "Receive jerrican", "Register truck", "Verify", "Incidents", "Inventory", "Reports"),
                fx(() -> nav.stream().filter(Node::isVisible).map(ToggleButton::getText).toList()), "starting the shift opens every screen");
        List<Button> menus = fx(() -> all(main, Button.class).stream().filter(b -> b.getStyleClass().contains("nav-group")).toList());
        assertEquals(1, menus.size());
        assertTrue(fx(() -> menus.get(0).isVisible() && menus.get(0).getText().startsWith("Jerricans")), "the Jerricans menu is there once the shift has started");
        assertEquals(List.of("Open jerrican", "Close jerrican", "Declare empty", "Transfer jerrican", "Receive jerrican"), fx(() -> nav.stream().filter(b -> b.getStyleClass().contains("nav-sub")).map(ToggleButton::getText).toList()),
                "all the jerrican operations are sub-items of that menu");
        assertTrue(fx(() -> nav.stream().filter(b -> b.getStyleClass().contains("nav-sub")).allMatch(b -> b.getParent().getStyleClass().contains("nav-sub-box"))));
        // the menu folds away and comes back
        fx(() -> menus.get(0).fire());
        assertTrue(fx(() -> nav.stream().filter(b -> b.getStyleClass().contains("nav-sub")).noneMatch(b -> showing(b))), "folded: the sub-items are hidden");
        assertTrue(fx(() -> menus.get(0).getText().contains("\u25B8")));
        fx(() -> menus.get(0).fire());
        assertTrue(fx(() -> nav.stream().filter(b -> b.getStyleClass().contains("nav-sub")).allMatch(b -> showing(b))), "shown again");
        assertTrue(fx(() -> tabs.getTabs().stream().noneMatch(Tab::isDisable)));
        shot("shift-open", scene);
        assertTrue(fx(() -> all(shift, Label.class).stream().anyMatch(l -> l.getText() != null && l.getText().startsWith("Shift OPEN"))));

        // wrong seals are refused and nothing changes; right seals open it
        Node openJ = open(tabs, "Open jerrican");
        // the screen: sealed jerricans as cards on the left (with counts), an action panel on the right that follows the chosen one
        @SuppressWarnings("unchecked") ListView<Container> jars = fx(() -> all(openJ, ListView.class).get(0));
        assertEquals(List.of("UIJ-" + N), fx(() -> jars.getItems().stream().map(Container::barcode).toList()));
        Button openButton = button(openJ, "Seals checked - open jerrican");
        assertFalse(fx(() -> showing(openButton)), "nothing is chosen yet, so there is nothing to open");
        fx(() -> all(openJ, TextField.class).get(0).setText("UIJ-" + N));                       // scanning the barcode jumps to that jerrican
        fx(() -> all(openJ, TextField.class).get(0).getOnAction().handle(new javafx.event.ActionEvent()));
        assertEquals("UIJ-" + N, fx(() -> jars.getSelectionModel().getSelectedItem().barcode()));
        assertTrue(fx(() -> showing(openButton)), "a sealed jerrican offers to be opened");
        assertTrue(fx(() -> !showing(button(openJ, "Close jerrican back & sign"))));
        shot("open-jerricans", scene);
        resetCapture();
        type(all(openJ, TextField.class).get(0), "NOPE-" + N);
        fx(() -> all(openJ, TextField.class).get(0).getOnAction().handle(new javafx.event.ActionEvent()));
        assertTrue(errors.get(0).contains("No sealed jerrican NOPE-"), errors.toString());
        fx(() -> all(openJ, TextField.class).get(0).setText("UIJ-" + N));
        fx(() -> { all(openJ, TextField.class).get(1).setText(org.jai.fuelmarking.Fixtures.seal("UIJ-" + N)); all(openJ, TextField.class).get(2).setText(""); });
        resetCapture();
        fx(() -> all(openJ, TextField.class).get(1).setText("WRONG"));
        click(openJ, "Seals checked - open jerrican");
        assertTrue(errors.get(0).contains("Seal numbers do not match"), errors.toString());
        assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode = ? AND status = 'SEALED'", "UIJ-" + N));
        resetCapture();
        fx(() -> all(openJ, TextField.class).get(1).setText(org.jai.fuelmarking.Fixtures.seal("UIJ-" + N)));
        signs = false;
        click(openJ, "Seals checked - open jerrican");   // person cancels the signature
        assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode = ? AND status = 'SEALED'", "UIJ-" + N), "no signature, no change");
        signs = true;
        click(openJ, "Seals checked - open jerrican");
        assertEquals(List.of("Jerrican opened."), infos);
        assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode = ? AND status = 'OPENED'", "UIJ-" + N));
        assertTrue(signPrompts.get(0).contains("UIJ-" + N));
        assertEquals(0, fx(() -> jars.getItems().size()).intValue(), "an opened jerrican leaves this screen ...");
        assertFalse(fx(() -> showing(openButton)));
        Node closeJ0 = open(tabs, "Close jerrican");                          // ... and is on the Close jerrican screen, ready to be closed away
        @SuppressWarnings("unchecked") ListView<Container> inUse = fx(() -> all(closeJ0, ListView.class).get(0));
        assertEquals(List.of("UIJ-" + N), fx(() -> inUse.getItems().stream().map(Container::barcode).toList()));
        fx(() -> inUse.getSelectionModel().select(0));
        assertTrue(fx(() -> showing(button(closeJ0, "Close jerrican back & sign"))), "an opened jerrican offers to be closed back with a new seal");
        assertTrue(fx(() -> all(closeJ0, Button.class).stream().noneMatch(b -> "Declare empty & sign".equals(b.getText()))), "declaring it empty is its own screen");
        assertTrue(fx(() -> !showing(button(closeJ0, "Seals checked - open jerrican"))), "and it no longer offers to be opened");
        Node emptyJ0 = open(tabs, "Declare empty");                            // the same opened jerrican, on the screen for declaring it empty
        @SuppressWarnings("unchecked") ListView<Container> emptyList = fx(() -> all(emptyJ0, ListView.class).get(0));
        assertEquals(List.of("UIJ-" + N), fx(() -> emptyList.getItems().stream().map(Container::barcode).toList()));
        fx(() -> emptyList.getSelectionModel().select(0));
        assertTrue(fx(() -> showing(button(emptyJ0, "Declare empty & sign"))));
        assertTrue(fx(() -> all(emptyJ0, Button.class).stream().noneMatch(b -> "Close jerrican back & sign".equals(b.getText()))), "closing it back is its own screen");
        open(tabs, "Close jerrican");
        resetCapture();
        type(all(closeJ0, TextField.class).get(0), "NOPE-" + N);
        fx(() -> all(closeJ0, TextField.class).get(0).getOnAction().handle(new javafx.event.ActionEvent()));
        assertTrue(errors.get(0).contains("No opened jerrican NOPE-"), errors.toString());
        resetCapture();

        // register a truck: bad input first
        Node reg = open(tabs, "Register truck");
        resetCapture();
        @SuppressWarnings("unchecked") ComboBox<Omc> omcBox = fx(() -> all(reg, ComboBox.class).get(0));
        fx(() -> omcBox.getSelectionModel().select(omcBox.getItems().stream().filter(o -> o.id().equals(omc.id())).findFirst().orElseThrow()));
        List<TextField> rf = all(reg, TextField.class);                    // truck, trailer, total loaded (read-only), then the compartment capacity and loaded
        type(rf.get(0), "ui-" + N); type(rf.get(1), "tr-" + N);
        assertFalse(fx(() -> rf.get(2).isEditable()), "the total follows the compartments");
        click(reg, "Review & sign");
        assertTrue(errors.get(0).contains("Add at least one compartment"), errors.toString());
        resetCapture();
        // every compartment carries its own fuel - there is no truck-level fuel or rum type, and no default
        assertEquals(2, fx(() -> all(reg, ComboBox.class).size()).intValue(), "only the company and the compartment's fuel are chosen from lists");
        type(rf.get(3), "6000");
        click(reg, "Add compartment");
        assertTrue(errors.get(0).contains("Select the fuel in this compartment"), errors.toString());
        resetCapture();
        @SuppressWarnings("unchecked") ComboBox<FuelType> fuelBox = fx(() -> all(reg, ComboBox.class).get(1));
        assertTrue(fx(() -> fuelBox.getItems().stream().map(Object::toString).toList().containsAll(List.of("Petrol", "Diesel", "Kerosene"))), "fuel types come from the master data");
        fx(() -> fuelBox.getSelectionModel().select(fuelBox.getItems().stream().filter(x -> x.toString().equals("Diesel")).findFirst().orElseThrow()));
        assertTrue(fx(() -> all(reg, Label.class).stream().anyMatch(l -> l.getText() != null && l.getText().contains("Diesel is marked with Standard"))), "the fuel says which rum it is marked with");
        click(reg, "Add compartment");                                      // 6000 L, blank loaded = full
        fx(() -> fuelBox.getSelectionModel().select(fuelBox.getItems().stream().filter(x -> x.toString().equals("Petrol")).findFirst().orElseThrow()));
        type(rf.get(3), "5000"); type(rf.get(4), "4000"); click(reg, "Add compartment");   // a different fuel in the second compartment
        assertEquals("10000", fx(() -> all(reg, TextField.class).get(2).getText()), "the total follows the compartments");
        // the rum to dose is shown as soon as there are compartments
        assertTrue(fx(() -> all(reg, Label.class).stream().anyMatch(l -> "100.0 g".equals(l.getText()))), "rum to dose for 10 000 L");
        assertTrue(fx(() -> all(reg, Label.class).stream().anyMatch(l -> l.getText() != null && l.getText().contains("Standard 100.0 g"))), "per rum type");
        assertTrue(fx(() -> all(reg, Label.class).stream().anyMatch(l -> l.getStyleClass().contains("check-ok") && l.getText().contains("2 compartment(s), 10,000.0 L loaded, 2 fuels"))));
        @SuppressWarnings("unchecked") TableView<Object> compTable = fx(() -> all(reg, TableView.class).get(0));
        assertEquals(2, fx(() -> compTable.getItems().size()).intValue());
        assertEquals(List.of("DIESEL", "PETROL"), fx(() -> ((TableView<org.jai.fuelmarking.model.CompartmentDetail>) (TableView<?>) compTable).getItems().stream().map(org.jai.fuelmarking.model.CompartmentDetail::getFuelType).toList()));
        shot("register-truck", scene);
        confirmAnswer = false;
        click(reg, "Review & sign");
        assertEquals(0, count("SELECT count(*) FROM truck_markings WHERE truck_number = ?", ("ui-" + N).toUpperCase()), "declined summary registers nothing");
        assertTrue(confirms.get(0).contains("Standard: 100.0 g") && confirms.get(0).contains("C1: 6,000.0 L DIESEL") && confirms.get(0).contains("C2: 4,000.0 L PETROL") && confirms.get(0).contains(("ui-" + N).toUpperCase())
                && confirms.get(0).contains("Trailer / wagon: " + ("tr-" + N).toUpperCase()), confirms.toString());
        resetCapture();
        click(reg, "Review & sign");
        assertTrue(infos.get(0).startsWith("Registered. Order number: UA" + N), infos.toString());
        assertEquals(1, count("SELECT count(*) FROM truck_markings WHERE truck_number = ? AND status = 'REGISTERED'", ("ui-" + N).toUpperCase()));
        assertEquals(1, count("SELECT count(*) FROM truck_markings WHERE truck_number = ? AND trailer_number = ?", ("ui-" + N).toUpperCase(), ("tr-" + N).toUpperCase()), "trailer / wagon number is recorded");
        assertTrue(fx(() -> all(reg, TextField.class).get(0).getText().isEmpty() && all(reg, TextField.class).get(1).getText().isEmpty()), "form is cleared after registering");

        // registered trucks wait in a list below; one registered by mistake can be deleted until its first dose
        @SuppressWarnings("unchecked") ListView<org.jai.fuelmarking.model.TruckMarking> waiting = fx(() -> lastOf(all(reg, ListView.class)));
        assertEquals(1, fx(() -> waiting.getItems().size()).intValue());
        Button deleteButton = button(reg, "Delete this truck");
        assertTrue(fx(() -> deleteButton.isDisabled()), "nothing is chosen yet");
        new org.jai.fuelmarking.service.MarkingService().register(users.get("sup"), siteA, Session.get().shift(), omc, "del-" + N, null,
                List.of(new org.jai.fuelmarking.model.CompartmentDetail("C1", 5000, 5000, "DIESEL", null)), new byte[]{1, 2, 3});
        open(tabs, "Shift");
        open(tabs, "Register truck");                                        // coming back to the screen reloads the list
        assertEquals(2, fx(() -> waiting.getItems().size()).intValue());
        shot("registered-waiting", scene);
        fx(() -> waiting.getSelectionModel().select(waiting.getItems().stream().filter(x -> x.getTruckNumber().equals(("del-" + N).toUpperCase())).findFirst().orElseThrow()));
        assertFalse(fx(() -> deleteButton.isDisabled()), "not dosed yet, so it can be deleted");
        assertTrue(fx(() -> all(reg, Label.class).stream().anyMatch(l -> l.getText() != null && l.getText().startsWith("Registered by mistake? Delete DEL-"))));
        resetCapture();
        confirmAnswer = false;
        click(reg, "Delete this truck");                                     // not confirmed: nothing happens
        assertEquals(1, count("SELECT count(*) FROM truck_markings WHERE truck_number = ? AND status = 'REGISTERED'", ("del-" + N).toUpperCase()));
        resetCapture();
        click(reg, "Delete this truck");
        assertTrue(confirms.get(0).contains("Delete the registration of truck DEL-" + N.toUpperCase()), confirms.toString());
        assertEquals(List.of("Deleted DEL-" + N.toUpperCase() + "."), infos);
        assertEquals(1, count("SELECT count(*) FROM truck_markings WHERE truck_number = ? AND status = 'DELETED' AND deleted_time IS NOT NULL AND deleted_by IS NOT NULL", ("del-" + N).toUpperCase()), "kept on record, with who and when");
        assertEquals(1, fx(() -> waiting.getItems().size()).intValue(), "it is gone from the list");
        assertTrue(fx(() -> deleteButton.isDisabled()));
        resetCapture();
        signOut(main);
    }

    @Test @Order(4)
    void operatorsDoseAndDispenseAndTheSupervisorVerifiesAndClosesTheShift() throws Exception {
        // ---- dosing operator
        MainView doseMain = signIn(siteA, "dose", PIN);
        fx(() -> Session.get().setShift(new org.jai.fuelmarking.service.ShiftService().findOpen(siteA.id()).orElseThrow()));
        TabPane tabs = tabsOf(doseMain);
        Node dose = open(tabs, "Dose");
        @SuppressWarnings("unchecked") ListView<org.jai.fuelmarking.model.TruckMarking> queue = fx(() -> all(dose, ListView.class).get(0));
        @SuppressWarnings("unchecked") ListView<RequiredDose> comps = fx(() -> all(dose, ListView.class).get(1));
        assertEquals(1, queue.getItems().size());
        org.jai.fuelmarking.hardware.SimulatedScale sim = new org.jai.fuelmarking.hardware.SimulatedScale(System::currentTimeMillis, new Random(7), 300, 1500);
        Ui.useScale(sim);
        Button confirmButton = button(dose, "Confirm dose & sign");
        ProgressBar gauge = fx(() -> all(dose, ProgressBar.class).get(0));
        try {
            resetCapture();
            // nothing chosen yet: no compartments, nothing to dose
            assertTrue(fx(() -> comps.getItems().isEmpty()));
            assertFalse(fx(() -> showing(confirmButton)), "the dosing panel waits for a compartment");
            // the truck has two compartments, each with its own target; the first one still to dose is chosen for you
            fx(() -> queue.getSelectionModel().select(0));
            assertEquals(List.of("C1", "C2"), fx(() -> comps.getItems().stream().map(RequiredDose::compartment).toList()));
            assertEquals(List.of(60.0, 40.0), fx(() -> comps.getItems().stream().map(RequiredDose::requiredG).map(g -> Math.round(g * 10) / 10.0).toList()), "6 000 L and 4 000 L");
            assertEquals("C1", fx(() -> comps.getSelectionModel().getSelectedItem().compartment()));
            assertTrue(fx(() -> showing(confirmButton)));
            assertTrue(fx(() -> all(dose, Label.class).stream().anyMatch(l -> l.getText() != null && l.getText().startsWith("Dose compartment C1 - DIESEL"))));
            assertTrue(fx(() -> all(dose, Label.class).stream().anyMatch(l -> "60.0 g".equals(l.getText()))), "the target weight is shown");
            assertTrue(fx(() -> all(dose, Label.class).stream().anyMatch(l -> l.getText() != null && l.getText().contains("accepted 58.8 to 61.2 g"))));
            click(dose, "Confirm dose & sign");
            assertTrue(errors.get(0).contains("Read the dose from the scale first"), errors.toString());

            // the pan is empty
            resetCapture();
            click(dose, "Read scale");
            waitUntil("the empty-pan message", () -> !errors.isEmpty());
            assertTrue(errors.get(0).contains("Nothing to weigh"), errors.toString());

            // put the empty small container (350 g) on the scale and tare it
            resetCapture();
            sim.placeOnScale(0.350);
            waitUntil("the live display to show the container", () -> all(dose, Label.class).stream().anyMatch(l -> "350.0 g".equals(l.getText())));
            click(dose, "Tare scale");
            waitUntil("the scale to be tared", () -> sim.tareKg() == 0.350);
            waitUntil("the live display to read zero", () -> all(dose, Label.class).stream().anyMatch(l -> "0.0 g".equals(l.getText())));

            // too little rum drawn (50 g of the 60 g target): the gauge says so, and it is refused - nothing changes
            sim.placeOnScale(0.400);
            click(dose, "Read scale");
            waitUntil("50.0 g to be read", () -> "50.0".equals(all(dose, TextField.class).get(0).getText()));
            assertTrue(fx(() -> all(dose, Label.class).stream().anyMatch(l -> "50.0 g".equals(l.getText()) && l.getStyleClass().contains("dose-big"))), "the measured weight is shown next to the target");
            assertTrue(fx(() -> gauge.getStyleClass().contains("gauge-low")), "below the accepted range");
            assertTrue(fx(() -> all(dose, Label.class).stream().anyMatch(l -> l.getStyleClass().contains("fb-low") && l.getText().contains("add about 10.0 g more"))));
            fx(() -> all(dose, TextField.class).get(1).setText("UIJ-" + N));
            shot("dose", doseMain.getScene());
            click(dose, "Confirm dose & sign");
            assertTrue(errors.get(0).contains("outside the allowed range") && errors.get(0).contains("compartment C1 needs 60.0 g") && errors.get(0).contains("Add more rum"), errors.toString());
            assertEquals(1000.0, scalar("SELECT current_volume_ml FROM containers WHERE barcode = ?", "UIJ-" + N), 0.001);
            assertEquals(1, queue.getItems().size(), "still waiting to be dosed");

            // too much (70 g): also flagged
            sim.placeOnScale(0.420);
            click(dose, "Read scale");
            waitUntil("70.0 g to be read", () -> "70.0".equals(all(dose, TextField.class).get(0).getText()));
            assertTrue(fx(() -> gauge.getStyleClass().contains("gauge-high") && all(dose, Label.class).stream().anyMatch(l -> l.getStyleClass().contains("fb-high"))));

            // top up to the target 60 g and read again: within range
            resetCapture();
            sim.placeOnScale(0.410);
            click(dose, "Read scale");
            waitUntil("60.0 g to be read", () -> "60.0".equals(all(dose, TextField.class).get(0).getText()));
            assertTrue(fx(() -> gauge.getStyleClass().contains("gauge-ok") && all(dose, Label.class).stream().anyMatch(l -> l.getStyleClass().contains("fb-ok") && l.getText().startsWith("Within range"))));
            click(dose, "Confirm dose & sign");
            assertTrue(infos.get(0).startsWith("Dosed 60.0 g for compartment C1. Still to dose: C2."), infos.toString());
            assertEquals(940.0, scalar("SELECT current_volume_ml FROM containers WHERE barcode = ?", "UIJ-" + N), 0.001);
            assertEquals(1, count("SELECT count(*) FROM marking_doses d JOIN truck_markings m ON m.id = d.truck_marking_id WHERE m.truck_number = ? AND d.compartment_number = 'C1' AND d.measured_g = 60 AND d.source = 'SCALE'", ("ui-" + N).toUpperCase()));
            assertEquals(1, count("SELECT count(*) FROM truck_markings WHERE truck_number = ? AND status = 'REGISTERED'", ("ui-" + N).toUpperCase()), "C2 is still to dose");
            assertEquals(1, queue.getItems().size(), "the truck stays in the queue until every compartment is dosed");
            assertEquals("C2", fx(() -> comps.getSelectionModel().getSelectedItem().compartment()), "the next compartment is chosen for you");
            assertTrue(fx(() -> all(dose, Label.class).stream().anyMatch(l -> "40.0 g".equals(l.getText()))), "its own target");
            assertTrue(fx(() -> all(dose, Label.class).stream().anyMatch(l -> "Dosed".equals(l.getText()))), "C1 is marked as dosed, with what it took");

            // the second compartment: 40 g against its own target
            resetCapture();
            sim.placeOnScale(0.390);
            click(dose, "Read scale");
            waitUntil("40.0 g to be read", () -> "40.0".equals(all(dose, TextField.class).get(0).getText()));
            fx(() -> all(dose, TextField.class).get(1).setText("UIJ-" + N));
            click(dose, "Confirm dose & sign");
            assertTrue(infos.get(0).startsWith("Dosed 40.0 g. Hand the container to the dispense operator."), infos.toString());
            assertEquals(900.0, scalar("SELECT current_volume_ml FROM containers WHERE barcode = ?", "UIJ-" + N), 0.001);
            assertEquals(100.0, scalar("SELECT dose_measured_g FROM truck_markings WHERE truck_number = ?", ("ui-" + N).toUpperCase()), 0.001);
            assertEquals(1, count("SELECT count(*) FROM truck_markings WHERE truck_number = ? AND dose_source = 'SCALE' AND status = 'DOSED'", ("ui-" + N).toUpperCase()), "recorded as read from the scale, and ready for dispensing");
            assertEquals(2, count("SELECT count(*) FROM marking_doses d JOIN truck_markings m ON m.id = d.truck_marking_id WHERE m.truck_number = ?", ("ui-" + N).toUpperCase()));
        } finally {
            Ui.useScale(null);
        }
        assertEquals(0, queue.getItems().size(), "a truck with every compartment dosed leaves the dosing queue");
        signOut(doseMain);

        // ---- dispense operator
        MainView dispMain = signIn(siteA, "disp", PIN);
        fx(() -> Session.get().setShift(new org.jai.fuelmarking.service.ShiftService().findOpen(siteA.id()).orElseThrow()));
        Node disp = open(tabsOf(dispMain), "Dispense");
        @SuppressWarnings("unchecked") TableView<org.jai.fuelmarking.model.TruckMarking> dq = fx(() -> all(disp, TableView.class).get(0));
        assertEquals(1, dq.getItems().size());
        resetCapture();
        fx(() -> dq.getSelectionModel().select(0));
        click(disp, "Rum dispensed - sign");
        assertEquals(List.of("Dispensing recorded."), infos);
        signOut(dispMain);

        // ---- supervisor verifies and closes the shift
        MainView supMain = signIn(siteA, "sup", PIN);
        TabPane st = tabsOf(supMain);
        Node verify = open(st, "Verify");
        @SuppressWarnings("unchecked") TableView<org.jai.fuelmarking.model.TruckMarking> vq = fx(() -> all(verify, TableView.class).get(0));
        assertEquals(1, vq.getItems().size());
        resetCapture();
        fx(() -> vq.getSelectionModel().select(0));
        click(verify, "Verified - sign");
        assertEquals(List.of("Marking completed."), infos);

        Node shift = open(st, "Shift");
        shot("shift-reconciliation", supMain.getScene());
        // the figures can be shown in grams or in kilograms, as the user prefers
        List<ToggleButton> unit = fx(() -> all(shift, ToggleButton.class).stream().filter(t -> t.getStyleClass().contains("segment")).toList());
        assertEquals(List.of("g", "kg"), fx(() -> unit.stream().map(ToggleButton::getText).toList()));
        assertTrue(fx(() -> unit.get(0).isSelected()), "grams to start with");
        assertTrue(fx(() -> all(shift, Label.class).stream().anyMatch(l -> "900.0 g".equals(l.getText()))), "expected and actual, in grams");
        assertTrue(fx(() -> all(shift, Label.class).stream().anyMatch(l -> "100.0 g".equals(l.getText()))), "dosed, in grams");
        fx(() -> unit.get(1).fire());
        assertTrue(fx(() -> unit.get(1).isSelected() && !unit.get(0).isSelected()));
        assertTrue(fx(() -> all(shift, Label.class).stream().anyMatch(l -> "0.9 kg".equals(l.getText()))), "the same figure in kilograms");
        assertTrue(fx(() -> all(shift, Label.class).stream().anyMatch(l -> "0.1 kg".equals(l.getText()))), "dosed 100 g is 0.1 kg");
        assertTrue(fx(() -> all(shift, Label.class).stream().anyMatch(l -> "1 kg".equals(l.getText()))), "opened today, 1000 g");
        assertTrue(fx(() -> all(shift, Label.class).stream().filter(l -> !l.getStyleClass().contains("mono")).noneMatch(l -> l.getText() != null && l.getText().matches("[+0-9,.]+ g"))),
                "no reconciliation amount is left in grams (the live scale display always reads in grams)");
        shot("shift-reconciliation-kg", supMain.getScene());
        fx(() -> unit.get(1).fire());
        assertTrue(fx(() -> unit.get(1).isSelected()), "a unit stays chosen when clicked again");
        fx(() -> unit.get(0).fire());
        assertTrue(fx(() -> all(shift, Label.class).stream().anyMatch(l -> "900.0 g".equals(l.getText()))), "and back to grams");
        // ---- reconcile any time: the opened jerrican is 50 g short -> a cause is required, and it becomes an incident;
        // a sealed jerrican at the site is counted too (nothing is weighed), and every sealed one has to be checked
        org.jai.fuelmarking.Fixtures.plastic(new org.jai.fuelmarking.service.ContainerService(), users.get("wh"), siteA, "UISL-" + N, 22_000, 20_000, "UISRC-" + N);
        Tx.run(c -> { try (var ps = c.prepareStatement("UPDATE containers SET current_volume_ml = current_volume_ml + 20000 WHERE barcode = ?")) { ps.setString(1, "UISRC-" + N); ps.executeUpdate(); } return null; });   // that rum is put back: the warehouse count later in this test relies on the IBC's figure
        resetCapture();
        click(shift, "Reconcile stock");                                       // the button on the Shift screen takes you to the Reconcile screen
        assertEquals("Reconcile", fx(() -> st.getSelectionModel().getSelectedItem().getText()), "Reconcile stock opens the Reconcile screen");
        Node rec = fx(() -> st.getSelectionModel().getSelectedItem().getContent());
        @SuppressWarnings("unchecked") TableView<ReconcilePanel.Row> rt = fx(() -> all(rec, TableView.class).get(0));
        assertEquals(1, fx(() -> rt.getItems().size()).intValue(), "the opened jerrican is listed");
        assertEquals("0.9", fx(() -> Ui.kg(rt.getItems().get(0).container.currentVolumeMl())));
        fx(() -> rt.getItems().get(0).counted.set("0.85"));
        assertEquals("-0.05", fx(() -> rt.getItems().get(0).difference.get()));
        assertTrue(fx(() -> rt.getItems().get(0).shortfall.get()));
        @SuppressWarnings("unchecked") TableView<ReconcilePanel.SealedRow> sealedRows = fx(() -> all(rec, TableView.class).get(1));
        assertEquals(List.of("UISL-" + N), fx(() -> sealedRows.getItems().stream().map(x -> x.container.barcode()).toList()), "the sealed jerrican is listed to be checked");
        shot("shift-reconcile-panel", supMain.getScene());
        click(rec, "Review & sign");
        assertTrue(errors.get(0).contains("Check UISL-" + N + " first"), "the sealed jerrican has to be checked first: " + errors);
        resetCapture();
        click(rec, "All found, seals intact");
        assertEquals("FOUND", fx(() -> sealedRows.getItems().get(0).result.get()));
        click(rec, "Review & sign");
        assertTrue(errors.get(0).contains("is short") && errors.get(0).contains("spillage, leakage or other"), errors.toString());
        assertEquals(0, count("SELECT count(*) FROM stock_counts WHERE kind = 'SHIFT' AND site_id = ?", siteA.id()), "nothing recorded without a cause");
        resetCapture();
        fx(() -> rt.getItems().get(0).cause.set("LEAKAGE"));
        click(rec, "Review & sign");
        assertTrue(confirms.get(0).contains("1 incident(s) will be recorded") && confirms.get(0).contains("leakage")
                && confirms.get(0).contains("Sealed jerricans: 1 checked - all found with their seals intact."), confirms.toString());
        assertEquals(1, count("SELECT count(*) FROM stock_count_lines l JOIN stock_counts c ON c.id = l.stock_count_id WHERE c.kind = 'SHIFT' AND c.site_id = ? AND l.barcode = ? AND l.check_result = 'FOUND'", siteA.id(), "UISL-" + N), "the sealed jerrican's check is part of the signed count");
        assertEquals(1, infos.size());
        assertTrue(infos.get(0).startsWith("Reconciliation recorded. 1 incident(s) raised"), infos.toString());
        assertEquals(1, count("SELECT count(*) FROM incidents WHERE site_id = ? AND incident_type = 'LEAKAGE' AND quantity_ml = 50 AND container_id IS NOT NULL", siteA.id()));
        assertEquals(850.0, scalar("SELECT current_volume_ml FROM containers WHERE barcode = ?", "UIJ-" + N), 0.001, "the system now holds what is there");
        assertEquals(1, count("SELECT count(*) FROM stock_counts c JOIN stock_count_lines l ON l.stock_count_id = c.id WHERE c.kind = 'SHIFT' AND c.site_id = ? AND l.cause = 'LEAKAGE' AND l.incident_id IS NOT NULL", siteA.id()));
        @SuppressWarnings("unchecked") TableView<StockCount> reconciledNow = fx(() -> lastOf(all(rec, TableView.class)));
        assertEquals(1, fx(() -> reconciledNow.getItems().size()).intValue(), "listed under what has been reconciled this shift");
        Node shiftAgain = open(st, "Shift");
        assertTrue(fx(() -> all(shiftAgain, Label.class).stream().anyMatch(l -> l.getText() != null && l.getText().startsWith("Last reconciled at"))));

        // ---- the shift cannot be closed while the jerrican is still open: the checklist says so and End shift stays locked
        Node shiftA = open(st, "Shift");
        assertTrue(fx(() -> button(shiftA, "End shift").isDisabled()), "a jerrican is still open");
        assertTrue(fx(() -> all(shiftA, Label.class).stream().anyMatch(l -> l.getText() != null && l.getText().contains("Every truck is verified"))), "the truck is verified");
        assertTrue(fx(() -> all(shiftA, Label.class).stream().anyMatch(l -> l.getText() != null && l.getText().contains("Still open: UIJ-" + N))));
        shot("shift-close-checklist", supMain.getScene());

        // close it back with a NEW seal (the one it was opened with is broken)
        Node openJ2 = open(st, "Close jerrican");
        @SuppressWarnings("unchecked") ListView<Container> openedList = fx(() -> all(openJ2, ListView.class).get(0));
        assertEquals(1, fx(() -> openedList.getItems().size()).intValue());
        fx(() -> openedList.getSelectionModel().select(0));
        resetCapture();
        type(all(openJ2, TextField.class).get(3), org.jai.fuelmarking.Fixtures.seal("UIJ-" + N));
        click(openJ2, "Close jerrican back & sign");
        assertTrue(errors.get(0).contains("was broken"), errors.toString());
        resetCapture();
        type(all(openJ2, TextField.class).get(3), "CLOSED-" + N);
        click(openJ2, "Close jerrican back & sign");
        assertEquals(1, infos.size(), errors.toString());
        assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode = ? AND status = 'SEALED' AND top_seal = ?", "UIJ-" + N, "CLOSED-" + N));
        assertEquals(850.0, scalar("SELECT current_volume_ml FROM containers WHERE barcode = ?", "UIJ-" + N), 0.001, "the rum stays in it");

        // the inventory shows the seal number on each container
        Node inventory = open(st, "Inventory");
        @SuppressWarnings("unchecked") TableView<Container> stock = fx(() -> all(inventory, TableView.class).get(0));
        TableColumn<Container, ?> sealColumn = fx(() -> stock.getColumns().stream().filter(c -> "Seals".equals(c.getText())).findFirst().orElseThrow());
        assertEquals("CLOSED-" + N, fx(() -> stock.getItems().stream().filter(k -> k.barcode().equals("UIJ-" + N)).map(k -> String.valueOf(sealColumn.getCellData(k))).findFirst().orElseThrow()));

        // now the shift can be closed
        Node shiftB = open(st, "Shift");
        assertFalse(fx(() -> button(shiftB, "End shift").isDisabled()));
        resetCapture();
        click(shiftB, "End shift");
        assertTrue(confirms.get(0).contains("Variance: 0.0 g"), confirms.toString());
        assertEquals(List.of("Shift closed."), infos);
        assertEquals(1, count("SELECT count(*) FROM shifts WHERE site_id = ? AND status = 'CLOSED'", siteA.id()));
        assertNull(Session.get().shift());
        shot("shift-closed", supMain.getScene());

        // every step left a signature behind: registration, one for each compartment dosed (two here), dispensing, verification (closing the shift and transfers need none)
        assertEquals(5, count("SELECT count(*) FROM signatures WHERE entity_type = 'TRUCK_MARKING' AND entity_id = (SELECT uuid FROM truck_markings WHERE truck_number = ?)",
                ("ui-" + N).toUpperCase()));
        assertEquals(0, count("SELECT count(*) FROM signatures WHERE entity_type = 'SHIFT' AND step = 'SHIFT_CLOSE' AND entity_id IN (SELECT id FROM shifts WHERE site_id = ?)", siteA.id()), "closing a shift asks for no signature");
        signOut(supMain);
    }

    @Test @Order(5)
    void reportsRunAndShowTheDay() throws Exception {
        MainView main = signIn(siteA, "sup", PIN);
        Node reports = open(tabsOf(main), "Reports");
        @SuppressWarnings("unchecked") ComboBox<Object> kinds = fx(() -> all(reports, ComboBox.class).get(0));
        @SuppressWarnings("unchecked") TableView<List<String>> table = fx(() -> all(reports, TableView.class).get(0));
        for (var kind : org.jai.fuelmarking.service.ReportService.Kind.values()) {
            resetCapture();
            fx(() -> kinds.getSelectionModel().select(kind));
            click(reports, "Run");
            assertTrue(errors.isEmpty(), kind + ": " + errors);
            if (kind == org.jai.fuelmarking.service.ReportService.Kind.MARKING_BY_FUEL) {
                assertEquals(2, table.getItems().size(), "the truck carries two fuels, so it counts under both");
                assertEquals(List.of("DIESEL", "1", "6000.00", "60.00"), List.of(table.getItems().get(0).get(0), table.getItems().get(0).get(1), table.getItems().get(0).get(3), table.getItems().get(0).get(4)),
                        "the 100 g dose is shared between the fuels by their litres");
                assertEquals(List.of("PETROL", "1", "4000.00", "40.00"), List.of(table.getItems().get(1).get(0), table.getItems().get(1).get(1), table.getItems().get(1).get(3), table.getItems().get(1).get(4)));
                shot("report-marking-by-fuel", main.getScene());
            }
        }
        fx(() -> kinds.getSelectionModel().select(org.jai.fuelmarking.service.ReportService.Kind.MARKING_DETAIL));
        click(reports, "Run");
        assertEquals(1, table.getItems().size());
        assertTrue(table.getItems().get(0).contains("Sam Supervisor") && table.getItems().get(0).contains("Dana Dosing"), "detail names who did each step: " + table.getItems());
        signOut(main);
    }

    @Test @Order(6)
    void warehouseRefillsAndDispatchesAndTheOtherSiteReceivesWithAWeightCheck() throws Exception {
        MainView main = signIn(siteA, "wh", PIN);
        TabPane tabs = tabsOf(main);
        Scene scene = main.getScene();

        // head office registered the jerricans (a metal one: two seals); the warehouse receives them empty
        org.jai.fuelmarking.Fixtures.register("UIE-" + N, "JERRICAN_METAL", 18_000, "STANDARD");
        org.jai.fuelmarking.Fixtures.register("UIP-" + N, "JERRICAN_PLASTIC", 22_000, "STANDARD");
        Node wh = open(tabs, "Receive jerrican");
        List<TextField> f = all(wh, TextField.class);   // the jerrican id
        List<ComboBox> boxes = all(wh, ComboBox.class);
        @SuppressWarnings("unchecked") ComboBox<Object> jerricanRum = boxes.get(0);
        // the jerrican operations are in the Jerricans menu, each a screen of its own; receiving an IBC/drum is a menu item of its own
        List<ToggleButton> wnav = fx(() -> all(main, ToggleButton.class).stream().filter(t -> t.getStyleClass().contains("nav-item")).toList());
        assertEquals(List.of("Receive jerrican", "Refill jerrican", "Transfer jerrican", "Receive returns"), fx(() -> wnav.stream().filter(b -> b.getStyleClass().contains("nav-sub")).map(ToggleButton::getText).toList()));
        assertTrue(fx(() -> wnav.stream().allMatch(t -> t.getGraphic() != null)), "every menu item has an icon");
        Button receiveJerricanButton = button(wh, "Receive empty jerrican");
        assertTrue(fx(() -> all(wh, Button.class).stream().noneMatch(b -> "Refill and seal".equals(b.getText()) || "Receive IBC / drum".equals(b.getText()))), "only this operation's form is on the screen");
        assertTrue(fx(() -> showing(receiveJerricanButton)));
        type(f.get(0), "NOPE-" + N);
        click(wh, "Receive empty jerrican");
        assertTrue(errors.get(0).contains("not registered at head office"), errors.toString());
        resetCapture();
        type(f.get(0), "UIE-" + N);
        click(wh, "Receive empty jerrican");
        assertTrue(errors.get(0).contains("Choose the rum type"), errors.toString());
        resetCapture();
        fx(() -> f.get(0).getOnAction().handle(new javafx.event.ActionEvent()));                 // scanning ends with Enter: look the id up
        assertEquals("Standard", fx(() -> String.valueOf(jerricanRum.getValue())), "the registered rum type is filled in");
        assertTrue(fx(() -> all(wh, Label.class).stream().anyMatch(l -> l.getText() != null && l.getText().startsWith("Metal jerrican") && l.getText().contains("2 seals"))));
        click(wh, "Receive empty jerrican");
        assertEquals(List.of("Jerrican received."), infos);
        assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode = ? AND status = 'EMPTY' AND container_type = 'JERRICAN_METAL' AND rum_type_code = 'STANDARD'", "UIE-" + N));
        assertEquals(18_000.0, scalar("SELECT capacity_ml FROM containers WHERE barcode = ?", "UIE-" + N), 0.001, "capacity comes from the registration");
        shot("receive-empty-jerrican", scene);
        resetCapture();
        Node bulkScreen = open(tabs, "Receive IBC / drum");
        List<TextField> bf = all(bulkScreen, TextField.class);                 // barcode, capacity, volume, top seal, bottom seal
        List<ComboBox> bb = all(bulkScreen, ComboBox.class);                  // container type, rum type
        assertTrue(fx(() -> all(bulkScreen, Button.class).stream().noneMatch(b -> "Receive empty jerrican".equals(b.getText()) || "Refill and seal".equals(b.getText()))), "only its own form");
        shot("receive-ibc", scene);
        @SuppressWarnings("unchecked") ComboBox<Object> bulkType = bb.get(0);
        assertTrue(fx(() -> bulkType.getItems().stream().map(Object::toString).toList().containsAll(List.of("IBC", "Drum"))), "bulk container types come from the master data");
        assertFalse(fx(() -> bulkType.getItems().stream().map(Object::toString).toList().contains("Plastic jerrican")), "jerrican types are not offered for bulk containers");
        fx(() -> bulkType.getSelectionModel().select(bulkType.getItems().stream().filter(x -> x.toString().equals("IBC")).findFirst().orElseThrow()));
        type(bf.get(0), "UIIBC-" + N); type(bf.get(1), "1000"); type(bf.get(2), "800"); /* kg */ type(bf.get(3), "T"); type(bf.get(4), "B");
        @SuppressWarnings("unchecked") ComboBox<Object> bulkRum = bb.get(1);
        fx(() -> bulkRum.getSelectionModel().select(bulkRum.getItems().stream().filter(x -> x.toString().equals("Standard")).findFirst().orElseThrow()));
        click(bulkScreen, "Receive IBC / drum");
        assertEquals(List.of("Container received."), infos);
        resetCapture();

        // refilling: seals are mandatory (metal: two, different ones), and the jerrican cannot be overfilled
        Node refillScreen = open(tabs, "Refill jerrican");
        List<TextField> rf = all(refillScreen, TextField.class);              // jerrican, IBC/drum, rum filled, seal, second seal
        assertTrue(fx(() -> all(refillScreen, Button.class).stream().noneMatch(b -> "Receive empty jerrican".equals(b.getText()) || "Receive IBC / drum".equals(b.getText()))), "only its own form");
        type(rf.get(0), "UIE-" + N); type(rf.get(1), "UIIBC-" + N); type(rf.get(2), "20");
        fx(() -> rf.get(0).getOnAction().handle(new javafx.event.ActionEvent()));
        assertTrue(fx(() -> !rf.get(4).isDisabled()), "a metal jerrican has a second seal");
        click(refillScreen, "Refill and seal");
        assertTrue(errors.get(0).contains("both seal numbers"), errors.toString());
        resetCapture();
        type(rf.get(3), "UIS1-" + N);
        click(refillScreen, "Refill and seal");
        assertTrue(errors.get(0).contains("both seal numbers"), "one seal is not enough for a metal jerrican: " + errors);
        resetCapture();
        type(rf.get(4), "UIS1-" + N);
        click(refillScreen, "Refill and seal");
        assertTrue(errors.get(0).contains("different numbers"), errors.toString());
        resetCapture();
        type(rf.get(4), "UIS2-" + N);
        click(refillScreen, "Refill and seal");                                                       // 20 kg into an 18 kg jerrican
        assertTrue(errors.get(0).contains("Exceeds jerrican capacity"), errors.toString());
        resetCapture();

        // the proper way: the EMPTY jerrican (420 g with its seals) goes on the scale and is tared, then it is filled
        org.jai.fuelmarking.hardware.SimulatedScale refillScale = new org.jai.fuelmarking.hardware.SimulatedScale(System::currentTimeMillis, new Random(4), 300, 1500);
        Ui.useScale(refillScale);
        refillScale.placeOnScale(0.420);
        click(refillScreen, "Tare scale");
        waitUntil("the scale to be tared", () -> refillScale.tareKg() == 0.420);
        refillScale.placeOnScale(1.320);                                                     // 900 g of rum added
        click(refillScreen, "Read scale");
        waitUntil("0.9 kg to be read", () -> "0.9".equals(rf.get(2).getText()));
        shot("refill", scene);
        click(refillScreen, "Refill and seal");
        assertEquals(List.of("Jerrican refilled and sealed."), infos);
        assertEquals(900.0, scalar("SELECT current_volume_ml FROM containers WHERE barcode = ?", "UIE-" + N), 0.001);
        assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode = ? AND status = 'SEALED' AND top_seal = ? AND bottom_seal = ?", "UIE-" + N, "UIS1-" + N, "UIS2-" + N));
        assertEquals(799100.0, scalar("SELECT current_volume_ml FROM containers WHERE barcode = ?", "UIIBC-" + N), 0.001);
        assertEquals(1, count("SELECT count(*) FROM containers j JOIN containers b ON b.id = j.source_container_id WHERE j.barcode = ? AND b.barcode = ?", "UIE-" + N, "UIIBC-" + N),
                "the jerrican remembers which IBC it was filled from");
        assertTrue(fx(() -> rf.get(0).getText().isEmpty() && rf.get(3).getText().isEmpty()), "the refill form is cleared");

        // the warehouse manager reconciles the stock: every IBC and drum here is counted, on the scale or typed
        Node count = open(tabs, "Stock count");
        @SuppressWarnings("unchecked") TableView<StockCountTab.Row> countTable = fx(() -> all(count, TableView.class).get(0));
        assertEquals(List.of("UIIBC-" + N, "UISRC-" + N), fx(() -> countTable.getItems().stream().map(r -> r.container.barcode()).toList()),
                "the IBCs and drums at this site; the jerrican is not counted here");
        resetCapture();
        click(count, "Review & sign");
        assertTrue(errors.get(0).contains("Count UIIBC-" + N + " first"), errors.toString());
        resetCapture();
        fx(() -> countTable.getSelectionModel().select(0));
        refillScale.placeOnScale(799.520);                                                     // 799.1 kg of rum on top of the 0.420 kg tare
        click(count, "Read scale for selected");
        waitUntil("the weighed count", () -> "799.1".equals(countTable.getItems().get(0).counted.get()));
        assertEquals("SCALE", fx(() -> countTable.getItems().get(0).source.get()));
        // typed straight into the row's box, with no Enter pressed: it counts at once and is marked as typed
        fx(() -> { countTable.applyCss(); countTable.layout(); });
        List<TextField> boxes2 = fx(() -> countTable.lookupAll(".text-field").stream().map(n -> (TextField) n).toList());
        assertEquals(2, boxes2.size(), "every row has a box to type the count into");
        assertEquals("799.1", fx(() -> boxes2.get(0).getText()), "the weighed amount shows in the box");
        fx(() -> boxes2.get(1).setText("898.5"));                                           // half a kilo short
        assertEquals("898.5", fx(() -> countTable.getItems().get(1).counted.get()));
        assertEquals("MANUAL", fx(() -> countTable.getItems().get(1).source.get()));
        assertTrue(fx(() -> all(count, Label.class).stream().anyMatch(l -> l.getText() != null && l.getText().contains("Counted 2 of 2") && l.getText().contains("Difference -0.5 kg"))));
        shot("stock-count", scene);
        resetCapture();
        click(count, "Review & sign");
        assertTrue(confirms.get(0).contains("Differences:") && confirms.get(0).contains("UISRC-" + N + ": -0.5 kg") && !confirms.get(0).contains("UIIBC-" + N + ": "), confirms.toString());
        assertEquals(List.of("Stock count recorded."), infos);
        assertEquals(1, count("SELECT count(*) FROM stock_counts WHERE site_id = ? AND total_counted_g = total_system_g - 500", siteA.id()));
        assertEquals(2, count("SELECT count(*) FROM stock_count_lines l JOIN stock_counts s ON s.id = l.stock_count_id WHERE s.site_id = ? AND s.kind = 'WAREHOUSE' AND l.source IN ('SCALE','MANUAL')", siteA.id()));
        assertEquals(799100.0, scalar("SELECT current_volume_ml FROM containers WHERE barcode = ?", "UIIBC-" + N), 0.001, "counting does not change the stock");
        @SuppressWarnings("unchecked") TableView<org.jai.fuelmarking.model.Records.StockCount> countHistory = fx(() -> all(count, TableView.class).get(1));
        assertEquals(1, fx(() -> countHistory.getItems().size()).intValue());
        assertTrue(fx(() -> countTable.getItems().stream().allMatch(r -> r.counted.get().isEmpty())), "a fresh count starts empty");
        Ui.useScale(null);

        // dispatch the refilled jerrican to the other site
        Node tr = open(tabs, "Transfer jerrican");
        @SuppressWarnings("unchecked") ComboBox<Site> dest = fx(() -> all(tr, ComboBox.class).get(0));
        fx(() -> dest.getSelectionModel().select(dest.getItems().stream().filter(s -> s.id().equals(siteB.id())).findFirst().orElseThrow()));
        List<TextField> tf = all(tr, TextField.class);                     // the scanned barcode (no weighing)
        assertEquals(1, tf.size(), "containers are not weighed when they move between sites");
        assertTrue(fx(() -> all(tr, Button.class).stream().noneMatch(b -> b.getText().contains("scale"))), "no scale buttons on the transfer screen");
        resetCapture();
        type(tf.get(0), "NOPE-" + N);
        click(tr, "Add to transfer");
        assertTrue(errors.get(0).contains("Unknown container"), errors.toString());
        resetCapture();
        type(tf.get(0), "UIP-" + N);                                       // registered, but never received: not a container yet
        click(tr, "Add to transfer");
        assertTrue(errors.get(0).contains("Unknown container"), errors.toString());
        resetCapture();
        type(tf.get(0), "UIE-" + N);
        click(tr, "Add to transfer");
        type(tf.get(0), "UIE-" + N);
        click(tr, "Add to transfer");
        assertTrue(errors.get(0).contains("already on the list"), errors.toString());
        resetCapture();
        @SuppressWarnings("unchecked") TableView<Container> outgoing = fx(() -> all(tr, TableView.class).get(0));
        assertEquals(1, outgoing.getItems().size());
        shot("transfer-dispatch", scene);
        click(tr, "Dispatch");
        assertTrue(confirms.get(0).contains("Bravo Site"), confirms.toString());
        assertEquals(1, count("SELECT count(*) FROM transfers WHERE from_site_id = ? AND to_site_id = ? AND status = 'DISPATCHED'", siteA.id(), siteB.id()));
        assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode = ? AND status = 'IN_TRANSIT'", "UIE-" + N));
        signOut(main);

        // the destination's supervisor ticks off what arrived
        org.jai.fuelmarking.model.Records.Shift bShift = new org.jai.fuelmarking.service.ShiftService().start(users.get("supB"), siteB);   // containers are taken in during a shift
        MainView bMain = signIn(siteB, "supB", PIN);
        Node btr = open(tabsOf(bMain), "Receive jerrican");
        @SuppressWarnings("unchecked") ListView<org.jai.fuelmarking.model.Records.Transfer> incoming = fx(() -> lastOf(all(btr, ListView.class)));  // (a dropdown carries a list of its own)
        assertEquals(1, incoming.getItems().size(), "errors: " + errors);
        fx(() -> { bMain.getScene().getRoot().applyCss(); bMain.getScene().getRoot().layout(); });
        String shown = fx(() -> ((ListCell<?>) incoming.lookupAll(".list-cell").iterator().next()).getText());
        assertTrue(shown != null && shown.startsWith("From Alpha Site") && shown.endsWith("1 container(s)") && !shown.contains("Transfer["), "readable label, got: " + shown);
        fx(() -> incoming.getSelectionModel().select(0));
        @SuppressWarnings("unchecked") TableView<TransferTab.Incoming> items = fx(() -> lastOf(all(btr, TableView.class))); // the dispatch table (empties going back) comes first
        assertEquals(1, items.getItems().size());
        assertTrue(fx(() -> items.getItems().get(0).receive.get()), "everything is ticked as arrived to start with");
        assertTrue(fx(() -> all(btr, Button.class).stream().noneMatch(b -> b.getText().contains("scale") || b.getText().contains("Weigh"))), "no weighing when receiving");
        resetCapture();
        fx(() -> items.getItems().get(0).receive.set(false));
        click(btr, "Receive ticked containers");
        assertTrue(errors.get(0).contains("Tick at least one"), errors.toString());
        resetCapture();
        fx(() -> items.getItems().get(0).receive.set(true));
        shot("transfer-receive", bMain.getScene());
        click(btr, "Receive ticked containers");
        assertEquals(List.of("Received 1 container(s)."), infos);
        assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode = ? AND status = 'SEALED' AND site_id = ?", "UIE-" + N, siteB.id()));
        assertEquals(1, count("SELECT count(*) FROM transfers WHERE to_site_id = ? AND status = 'RECEIVED'", siteB.id()));

        // ---- the jerrican is finished: the supervisor opens it, declares it empty (saying what happened to the rum left) and sends it back
        Node bOpen = open(tabsOf(bMain), "Open jerrican");
        resetCapture();
        type(all(bOpen, TextField.class).get(0), "UIE-" + N);
        fx(() -> all(bOpen, TextField.class).get(0).getOnAction().handle(new javafx.event.ActionEvent()));     // scan: the metal jerrican asks for two seals
        assertFalse(fx(() -> all(bOpen, TextField.class).get(2).isDisabled()), "a metal jerrican has a second seal");
        type(all(bOpen, TextField.class).get(1), "UIS1-" + N);
        type(all(bOpen, TextField.class).get(2), "UIS2-" + N);
        click(bOpen, "Seals checked - open jerrican");
        assertEquals(List.of("Jerrican opened."), infos);
        Node bClose = open(tabsOf(bMain), "Declare empty");                      // now in use: finished, so it is declared empty on its own screen
        @SuppressWarnings("unchecked") ListView<Container> openedTable = fx(() -> all(bClose, ListView.class).get(0));
        assertEquals(1, fx(() -> openedTable.getItems().size()).intValue(), "the opened jerrican is listed as finished-able");
        resetCapture();
        fx(() -> openedTable.getSelectionModel().select(0));
        @SuppressWarnings("unchecked") ComboBox<String> leftCause = fx(() -> all(bClose, ComboBox.class).get(0));
        assertFalse(fx(() -> leftCause.isDisabled()), "rum is still shown in it, so the cause is asked for");
        shot("declare-empty", bMain.getScene());
        click(bClose, "Declare empty & sign");
        assertTrue(errors.get(0).contains("still shown in UIE-" + N), errors.toString());
        assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode = ? AND status = 'OPENED'", "UIE-" + N));
        resetCapture();
        fx(() -> leftCause.setValue("SPILLAGE"));
        click(bClose, "Declare empty & sign");
        assertTrue(confirms.get(0).contains("recorded as an incident") && confirms.get(0).contains("spillage"), confirms.toString());
        assertEquals(1, infos.size());
        assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode = ? AND status = 'EMPTY' AND site_id = ?", "UIE-" + N, siteB.id()));
        assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode = ? AND top_seal IS NULL AND bottom_seal IS NULL", "UIE-" + N), "both seals are discarded when it is declared empty");
        assertEquals(2, count("SELECT count(*) FROM discarded_seals WHERE seal IN (?, ?)", "UIS1-" + N, "UIS2-" + N), "and remembered, so they can never be used again");
        assertEquals(1, count("SELECT count(*) FROM incidents WHERE site_id = ? AND incident_type = 'SPILLAGE' AND description LIKE 'Jerrican declared empty%'", siteB.id()));
        assertEquals(0, fx(() -> openedTable.getItems().size()).intValue());

        // send it back: only warehouses are offered, only empty jerricans can be added
        Tx.run(c -> { new org.jai.fuelmarking.dao.MasterDataDao().upsertSite(c, new Site(siteA.id(), siteA.code(), siteA.name(), "WAREHOUSE")); return null; });
        Node bTransfers = open(tabsOf(bMain), "Transfer jerrican");
        @SuppressWarnings("unchecked") ComboBox<Site> returnTo = fx(() -> all(bTransfers, ComboBox.class).get(0));
        assertTrue(fx(() -> returnTo.getItems().stream().map(Site::id).toList()).contains(siteA.id()), "a warehouse can take empties back");
        assertTrue(fx(() -> returnTo.getItems().stream().allMatch(x -> "WAREHOUSE".equals(x.siteType()))), "only warehouses are offered (other tests leave warehouses in the shared database)");
        fx(() -> returnTo.getSelectionModel().select(returnTo.getItems().stream().filter(x -> x.id().equals(siteA.id())).findFirst().orElseThrow()));
        resetCapture();
        type(all(bTransfers, TextField.class).get(0), "UIE-" + N);
        click(bTransfers, "Add to transfer");
        assertTrue(errors.isEmpty(), errors.toString());
        shot("return-empty", bMain.getScene());
        click(bTransfers, "Dispatch");
        assertTrue(confirms.get(0).contains("back to Alpha Site"), confirms.toString());
        assertEquals(List.of("Empty jerricans sent back."), infos);
        assertEquals(1, count("SELECT count(*) FROM transfers WHERE from_site_id = ? AND to_site_id = ? AND kind = 'RETURN' AND status = 'DISPATCHED'", siteB.id(), siteA.id()));
        assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode = ? AND status = 'IN_TRANSIT'", "UIE-" + N));
        signOut(bMain);

        // the warehouse manager takes it in: it is EMPTY, ready for a refill
        MainView whMain = signIn(siteA, "wh", PIN);
        Node whTransfers = open(tabsOf(whMain), "Receive returns");
        @SuppressWarnings("unchecked") ListView<org.jai.fuelmarking.model.Records.Transfer> back = fx(() -> lastOf(all(whTransfers, ListView.class)));
        assertEquals(1, fx(() -> back.getItems().size()).intValue());
        fx(() -> { whMain.getScene().getRoot().applyCss(); whMain.getScene().getRoot().layout(); });
        String backLabel = fx(() -> ((ListCell<?>) back.lookupAll(".list-cell").iterator().next()).getText());
        assertTrue(backLabel != null && backLabel.startsWith("Empty jerricans back from Bravo Site"), "got: " + backLabel);
        resetCapture();
        fx(() -> back.getSelectionModel().select(0));
        click(whTransfers, "Receive ticked containers");
        assertEquals(List.of("Received 1 container(s)."), infos);
        assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode = ? AND status = 'EMPTY' AND site_id = ?", "UIE-" + N, siteA.id()), "back at the warehouse, empty");
        assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode = ? AND top_seal IS NULL AND bottom_seal IS NULL", "UIE-" + N), "without seals: it is sealed again when it is refilled");
        signOut(whMain);
    }

    @Test @Order(7)
    void incidentsAreRecordedFromTheScreen() throws Exception {
        MainView main = signIn(siteA, "dose", PIN);
        Node inc = open(tabsOf(main), "Incidents");
        List<TextField> f = all(inc, TextField.class);                     // quantity, container
        resetCapture();
        click(inc, "Report incident");
        assertTrue(errors.get(0).contains("Quantity must be a number"), errors.toString());
        // an incident says which rum was lost: with no container named, the rum type has to be chosen
        resetCapture();
        type(f.get(0), "3");
        click(inc, "Report incident");
        assertTrue(errors.get(0).contains("Say which rum was lost"), errors.toString());
        resetCapture();
        @SuppressWarnings("unchecked") ComboBox<RumType> rumBox = fx(() -> all(inc, ComboBox.class).get(1));
        assertFalse(fx(() -> rumBox.isDisabled()), "no container named, so the rum type is chosen by hand");
        // with a container named it is the rum that container holds
        type(f.get(0), "12.5"); type(f.get(1), "UIJ-" + N);
        fx(() -> f.get(1).getOnAction().handle(new javafx.event.ActionEvent()));
        assertTrue(fx(() -> rumBox.isDisabled() && rumBox.getValue() != null && rumBox.getValue().code().equals("STANDARD")), "taken from the container");
        fx(() -> all(inc, TextArea.class).get(0).setText("dropped while decanting"));
        click(inc, "Report incident");
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(1, count("SELECT count(*) FROM incidents WHERE site_id = ? AND quantity_ml = 12.5 AND rum_type_code = 'STANDARD'", siteA.id()), "the rum is recorded with the incident");
        assertFalse(fx(() -> rumBox.isDisabled()), "ready for the next one");
        assertEquals(837.5, scalar("SELECT current_volume_ml FROM containers WHERE barcode = ?", "UIJ-" + N), 0.001, "850 g after the reconciliation corrected it, less this 12.5 g");
        @SuppressWarnings("unchecked") TableView<Incident> table = fx(() -> all(inc, TableView.class).get(0));
        assertEquals(2, table.getItems().size(), "this incident and the leakage raised by the shift reconciliation");
        shot("incidents", main.getScene());
        signOut(main);
    }

    @Test @Order(7)
    void readScaleFillsTheWeightWaitsForASteadyReadingAndExplainsProblems() throws Exception {
        org.jai.fuelmarking.hardware.SimulatedScale sim = new org.jai.fuelmarking.hardware.SimulatedScale(System::currentTimeMillis, new Random(3), 400, 1500);
        Ui.useScale(sim);
        System.setProperty("hardware.scale.stable-wait-seconds", "1");
        try {
            MainView main = signIn(siteA, "wh", PIN);
            Node tr = open(tabsOf(main), "Refill jerrican");
            TextField weight = fx(() -> all(tr, TextField.class).get(2));                           // rum filled (kg)
            Button read = button(tr, "Read scale");

            // empty pan
            resetCapture();
            click(tr, "Read scale");
            waitUntil("the empty-pan message", () -> !errors.isEmpty());
            assertTrue(errors.get(0).contains("Nothing to weigh"), errors.toString());
            waitUntil("the button to come back", () -> !read.isDisabled() && "Read scale".equals(read.getText()));

            // a container is put on: the first press waits for the display to settle, then fills the weight
            resetCapture();
            sim.placeOnScale(16.40);
            click(tr, "Read scale");
            assertTrue(fx(() -> read.isDisabled() && "Weighing...".equals(read.getText())), "button shows it is weighing");
            waitUntil("the stable weight", () -> "16.4".equals(weight.getText()));
            assertTrue(errors.isEmpty(), errors.toString());
            shot("refill-read-scale", main.getScene());

            // a reading that never settles
            resetCapture();
            fx(() -> weight.clear());
            sim.setDrifting(true);
            click(tr, "Read scale");
            waitUntil("the not-steady message", () -> !errors.isEmpty());
            assertTrue(errors.get(0).contains("not steady"), errors.toString());
            assertEquals("", fx(() -> weight.getText()), "an unsteady reading must not fill in a weight");

            // overloaded
            resetCapture();
            sim.setDrifting(false);
            sim.setOverload(true);
            click(tr, "Read scale");
            waitUntil("the overload message", () -> !errors.isEmpty());
            assertTrue(errors.get(0).contains("overloaded"), errors.toString());

            // unplugged
            resetCapture();
            sim.setOverload(false);
            sim.setDisconnected(true);
            click(tr, "Read scale");
            waitUntil("the unplugged message", () -> !errors.isEmpty());
            assertTrue(errors.get(0).contains("not responding"), errors.toString());
            waitUntil("the button to be usable again", () -> !read.isDisabled() && "Read scale".equals(read.getText()));
            signOut(main);
        } finally {
            Ui.useScale(null);
            System.clearProperty("hardware.scale.stable-wait-seconds");
        }
    }

    @Test @Order(8)
    void theSimulatorWindowShowsTheScaleAndControlsIt() throws Exception {
        org.jai.fuelmarking.hardware.SimulatedScale sim = new org.jai.fuelmarking.hardware.SimulatedScale(System::currentTimeMillis, new Random(2), 300, 1500);
        javafx.scene.Parent panel = fx(() -> ScaleSimulatorPanel.create(sim));
        Scene scene = fx(() -> { Scene sc = new Scene(panel); Ui.applyTheme(sc); return sc; });
        Label display = fx(() -> all(panel, Label.class).stream().filter(l -> l.getText().endsWith(" kg")).findFirst().orElseThrow());

        click(panel, "Full jerrican 16.40");
        waitUntil("the display to settle on 16.40", () -> "16.40 kg".equals(display.getText()));
        shot("scale-simulator", scene);
        assertEquals(16.40, sim.grossKg());

        click(panel, "Tare");
        waitUntil("the display to read zero after taring", () -> "0.00 kg".equals(display.getText()));
        click(panel, "Remove");
        assertEquals(0.0, sim.grossKg());

        fx(() -> all(panel, CheckBox.class).stream().filter(c -> c.getText().startsWith("Unplugged")).findFirst().orElseThrow().setSelected(true));
        assertTrue(sim.isDisconnected());
        waitUntil("the display to show no response", () -> "-----".equals(display.getText()));
        fx(() -> all(panel, CheckBox.class).stream().filter(c -> c.getText().startsWith("Overloaded")).findFirst().orElseThrow().setSelected(true));
        assertTrue(sim.isOverload());

        resetCapture();
        fx(() -> all(panel, TextField.class).get(0).setText("abc"));
        click(panel, "Place on scale");
        assertEquals(List.of("Enter the weight in kilograms, e.g. 16.40"), errors);
    }

    @Test @Order(9)
    void anIdleTerminalLocksItselfButActivityKeepsItOpen() throws Exception {
        System.setProperty("fuel-marking.ui.idle-lock-seconds", "1");
        try {
            MainView active = signIn(siteA, "sup", PIN);
            CompletableFuture<Boolean> locked = new CompletableFuture<>();
            MainView watched = fx(() -> new MainView(() -> locked.complete(true)));
            fx(() -> new Scene(watched, 1000, 700));
            // keyboard activity every 400 ms keeps the session alive well past the 1 s limit
            for (int i = 0; i < 6; i++) {
                Thread.sleep(400);
                fx(() -> Event.fireEvent(watched, new KeyEvent(KeyEvent.KEY_PRESSED, "a", "a", KeyCode.A, false, false, false, false)));
            }
            assertFalse(locked.isDone(), "activity must reset the idle timer");
            assertTrue(locked.get(5, TimeUnit.SECONDS), "after 1 s without input the session locks");
        } finally {
            System.clearProperty("fuel-marking.ui.idle-lock-seconds");
        }
    }
}
