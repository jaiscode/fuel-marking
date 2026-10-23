package org.jai.fuelmarking.ui;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.jai.fuelmarking.model.Records.RumType;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** Every rum type has its own colour; this shows it as a small square beside the name so rum can be told apart at a glance. */
final class RumSwatch {
    private RumSwatch() {}

    static Rectangle square(String color) {
        Rectangle r = new Rectangle(16, 16);
        r.setArcWidth(4); r.setArcHeight(4);
        r.setStroke(Color.web("#00000040"));
        try { r.setFill(Color.web(color == null ? "#808080" : color)); } catch (IllegalArgumentException e) { r.setFill(Color.GRAY); }
        return r;
    }

    static Node withText(String color, String text) {
        Label label = new Label(text);
        label.setTextFill(Color.web("#222222"));      // a label inside a drop-down cell would otherwise take a washed-out colour
        HBox box = new HBox(8, square(color), label);
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return box;
    }

    /** A rum-type drop-down whose entries (and the closed box) show the colour. */
    static void style(ComboBox<RumType> combo) {
        combo.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(RumType t, boolean empty) {
                super.updateItem(t, empty);
                setText(null);
                setGraphic(empty || t == null ? null : withText(t.color(), t.name()));
            }
        });
        combo.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(RumType t, boolean empty) {
                super.updateItem(t, empty);
                setText(empty || t == null ? combo.getPromptText() : null);
                setGraphic(empty || t == null ? null : withText(t.color(), t.name()));
            }
        });
    }

    /** A table column showing a rum type code with its colour; the code -> colour map is read each time a cell is drawn. */
    static <T> void column(TableColumn<T, String> col, Function<T, String> code, Supplier<Map<String, String>> colors) {
        col.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                T row = empty || getTableRow() == null ? null : getTableRow().getItem();
                String rum = row == null ? null : code.apply(row);
                setText(null);
                setGraphic(rum == null || rum.isEmpty() ? null : withText(colors.get().get(rum), rum));
            }
        });
    }
}
