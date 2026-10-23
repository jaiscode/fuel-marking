package org.jai.fuelmarking.ui;

import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.jai.fuelmarking.model.Records.ReportTable;
import org.jai.fuelmarking.service.ReportService;
import org.jai.fuelmarking.service.ReportService.Kind;
import org.jai.fuelmarking.session.Session;

import java.io.File;
import java.time.LocalDate;
import java.util.List;

class ReportsTab extends RefreshableTab {
    private final ReportService service = new ReportService();
    private final ComboBox<Kind> kind = new ComboBox<>();
    private final DatePicker from = new DatePicker(LocalDate.now().withDayOfMonth(1));
    private final DatePicker to = new DatePicker(LocalDate.now());
    private final TableView<List<String>> table = new TableView<>();
    private final Label caption = new Label();
    private ReportTable current;

    ReportsTab() {
        super("Reports");
        kind.getItems().setAll(Kind.values());
        kind.getSelectionModel().selectFirst();
        Button run = new Button("Run");
        run.setDefaultButton(true);
        run.setOnAction(e -> Ui.attempt(this::run));
        Button export = new Button("Export CSV");
        export.setOnAction(e -> Ui.attempt(this::export));
        table.setPlaceholder(new Label("No data for this selection"));
        Label fromLabel = new Label("FROM"), toLabel = new Label("TO"), kindLabel = new Label("REPORT");
        for (Label l : new Label[]{fromLabel, toLabel, kindLabel}) l.getStyleClass().add("kpi-label");
        HBox bar = new HBox(14, new javafx.scene.layout.VBox(4, kindLabel, kind), new javafx.scene.layout.VBox(4, fromLabel, from),
                new javafx.scene.layout.VBox(4, toLabel, to), run, export);
        bar.setAlignment(javafx.geometry.Pos.BOTTOM_LEFT);
        bar.getStyleClass().add("card");
        caption.getStyleClass().add("section-title");
        VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);
        setContent(Ui.page(Ui.header("Reports", "Choose a report and a date range. Export the result as CSV for Excel."), bar, caption, table));
    }

    private void run() {
        Session s = Session.get();
        current = service.run(kind.getValue(), s.site().id(), from.getValue(), to.getValue());
        table.getColumns().clear();
        for (int i = 0; i < current.headers().size(); i++) {
            final int idx = i;
            table.getColumns().add(Ui.col(current.headers().get(i), row -> row.get(idx)));
        }
        table.getItems().setAll(current.rows());
        caption.setText(kind.getValue() + " - " + s.site().name() + " - " + current.rows().size() + " row(s)");
    }

    private void export() {
        if (current == null) throw new IllegalStateException("Run a report first");
        FileChooser fc = new FileChooser();
        fc.setInitialFileName(kind.getValue().name().toLowerCase() + "-" + LocalDate.now() + ".csv");
        File f = fc.showSaveDialog(getTabPane().getScene().getWindow());
        if (f != null) service.exportCsv(current, f.toPath());
    }

    @Override
    void refresh() {}
}
