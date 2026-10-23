package org.jai.fuelmarking.ui;

import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.jai.fuelmarking.hardware.Hardware;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** On-screen signature pad (mouse / touch / stylus). Swap for a hardware pad by implementing Hardware.SignaturePad. */
public class SignaturePadDialog implements Hardware.SignaturePad {

    @Override
    public byte[] capture(String prompt) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Signature");

        Canvas canvas = new Canvas(560, 220);
        GraphicsContext g = canvas.getGraphicsContext2D();
        clear(g, canvas);
        boolean[] drawn = {false};
        byte[][] result = {null};

        canvas.setOnMousePressed(e -> { g.beginPath(); g.moveTo(e.getX(), e.getY()); g.stroke(); });
        canvas.setOnMouseDragged(e -> { g.lineTo(e.getX(), e.getY()); g.stroke(); drawn[0] = true; });

        Button clear = new Button("Clear");
        clear.setOnAction(e -> { clear(g, canvas); drawn[0] = false; });
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> stage.close());
        Button ok = new Button("Confirm signature");
        ok.setDefaultButton(true);
        ok.setOnAction(e -> {
            if (!drawn[0]) { Ui.error("Please sign in the box first."); return; }
            result[0] = toPng(canvas);
            stage.close();
        });

        HBox buttons = new HBox(10, clear, cancel, ok);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox root = new VBox(10, new Label(prompt), canvas, buttons);
        root.setPadding(new Insets(14));
        stage.setScene(new Scene(root));
        stage.showAndWait();
        return result[0];
    }

    private static void clear(GraphicsContext g, Canvas c) {
        g.setFill(Color.WHITE);
        g.fillRect(0, 0, c.getWidth(), c.getHeight());
        g.setStroke(Color.BLACK);
        g.setLineWidth(2.2);
        g.strokeRect(0.5, 0.5, c.getWidth() - 1, c.getHeight() - 1);
    }

    private static byte[] toPng(Canvas canvas) {
        WritableImage img = canvas.snapshot(null, null);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode signature", e);
        }
    }
}
