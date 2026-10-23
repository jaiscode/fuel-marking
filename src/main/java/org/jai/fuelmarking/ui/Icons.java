package org.jai.fuelmarking.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

/** Small line-art icons drawn as shapes (no font or image files, so they look the same on every machine). Each fits a 44 x 44 box. */
final class Icons {
    private Icons() {}

    private static SVGPath path(String d) {
        SVGPath p = new SVGPath();
        p.setContent(d);
        p.getStyleClass().add("icon");
        return p;
    }

    /** An empty jerrican: a can with a handle and a cap, and an arrow going in. */
    static Node jerrican() {
        return new Group(path("M12 14 h16 l6 6 v20 a2 2 0 0 1 -2 2 h-18 a2 2 0 0 1 -2 -2 z M12 14 v-4 h8 v4 M22 6 h6 v4 h-6 z M17 24 h12 v12 h-12 z"));
    }

    /** An IBC: a cube in a cage with a tap at the bottom. */
    static Node bulk() {
        return new Group(path("M6 8 h32 v28 h-32 z M6 16 h32 M6 24 h32 M6 32 h32 M14 8 v28 M22 8 v28 M30 8 v28 M18 36 h8 v4 h-8 z M22 40 v4"));
    }

    /** A drop of rum going into a jerrican, and a seal (padlock) beside it. */
    static Node refill() {
        return new Group(path("M16 4 c4 6 8 10 8 15 a8 8 0 0 1 -16 0 c0 -5 4 -9 8 -15 z M26 28 h14 v12 h-14 z M29 28 v-4 a4 4 0 0 1 8 0 v4 M33 33 v3"));
    }

    // ---- navigation icons (the sidebar)

    private static Node icon(String d) { return new Group(path(d)); }

    static Node clock() { return icon("M22 6 a16 16 0 1 0 0.01 0 z M22 14 v9 l7 4"); }

    static Node truck() { return icon("M4 12 h22 v18 h-22 z M26 18 h8 l6 7 v5 h-14 z M12 30 a4 4 0 1 0 0.01 0 M32 30 a4 4 0 1 0 0.01 0"); }

    static Node verified() { return icon("M22 6 a16 16 0 1 0 0.01 0 z M13 23 l7 7 l12 -14"); }

    static Node drop() { return icon("M22 5 c6 8 12 14 12 22 a12 12 0 0 1 -24 0 c0 -8 6 -14 12 -22 z M16 28 a6 6 0 0 0 6 6"); }

    static Node funnel() { return icon("M8 8 h28 l-10 14 v14 h-8 v-14 z"); }

    static Node warning() { return icon("M22 6 l17 30 h-34 z M22 17 v10 M22 31 v2"); }

    static Node arrows() { return icon("M6 16 h30 M28 8 l8 8 l-8 8 M38 32 h-30 M16 24 l-8 8 l8 8"); }

    static Node chart() { return icon("M8 38 h30 M12 38 v-12 M20 38 v-22 M28 38 v-16 M36 38 v-28"); }

    static Node clipboard() { return icon("M12 8 h20 v32 h-20 z M17 5 h10 v6 h-10 z M17 21 h10 M17 28 h10 M17 35 h6"); }

    static Node lockOpen() { return icon("M10 20 h24 v20 h-24 z M16 20 v-6 a6 6 0 0 1 12 0 M22 28 v5"); }

    static Node lock() { return icon("M10 20 h24 v20 h-24 z M14 20 v-6 a8 8 0 0 1 16 0 v6 M22 28 v5"); }

    static Node emptyJerrican() { return icon("M12 14 h16 l6 6 v20 a2 2 0 0 1 -2 2 h-18 a2 2 0 0 1 -2 -2 z M12 14 v-4 h8 v4 M22 6 h6 v4 h-6 z M16 32 h12"); }

    static Node clipboardCheck() { return icon("M12 8 h20 v32 h-20 z M17 5 h10 v6 h-10 z M16 27 l5 5 l9 -11"); }

    static Node inbox() { return icon("M8 28 h8 l2 4 h8 l2 -4 h8 v10 h-28 z M22 6 v18 M15 17 l7 7 l7 -7"); }

    /** The sidebar icon for a tab, by its title; a plain dot for one without its own. */
    static Node forTab(String title) {
        Node n = switch (title) {
            case "Shift" -> clock();
            case "Jerricans" -> jerrican();
            case "Open jerrican" -> lockOpen();
            case "Close jerrican" -> lock();
            case "Declare empty" -> emptyJerrican();
            case "Transfer jerrican" -> arrows();
            case "Receive jerrican" -> inbox();
            case "Receive returns" -> inbox();
            case "Refill jerrican" -> refill();
            case "Receive IBC / drum" -> bulk();
            case "Register truck" -> truck();
            case "Verify" -> verified();
            case "Dose" -> drop();
            case "Dispense" -> funnel();
            case "Incidents" -> warning();
            case "Inventory" -> bulk();
            case "Transfers" -> arrows();
            case "Reports" -> chart();
            case "Stock count" -> clipboard();
            case "Reconcile" -> clipboardCheck();
            case "Receive / refill" -> refill();
            default -> icon("M22 16 a6 6 0 1 0 0.01 0 z");
        };
        javafx.scene.layout.StackPane box = new javafx.scene.layout.StackPane(n);
        n.setScaleX(0.5);
        n.setScaleY(0.5);
        box.setMinSize(24, 24); box.setPrefSize(24, 24); box.setMaxSize(24, 24);      // the 44-unit drawing, shrunk to a 24 px slot
        return box;
    }
}
