package packloader.ui;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Color;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import arc.util.Threads;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.FileChooser;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import packloader.core.ReportExporter;
import packloader.model.ReportModel;

import static packloader.util.Localization.getLocalizedText;

public class ExportReportDialog extends BaseDialog{
    private final ReportModel model;

    public interface ConsThrowable<T>{
        void get(T param) throws Throwable;
    }

    public ExportReportDialog(ReportModel model){
        super("plan".equals(model.reportType) ?
            getLocalizedText("packloader.report.dialog.plan.title") :
            getLocalizedText("packloader.report.dialog.title"));
        this.model = model;

        setupUI();
    }

    private void setupUI(){
        cont.clear();

        Table root = new Table();
        root.defaults().pad(6f).growX();

        // Summary Card
        Table summaryCard = new Table(Tex.pane);
        summaryCard.margin(10f);
        summaryCard.defaults().left().pad(3f);

        summaryCard.add(getLocalizedText("packloader.report.summary.title"))
            .color(Pal.accent).style(Styles.outlineLabel).row();

        summaryCard.add(getLocalizedText("packloader.report.summary.total", model.summary.total))
            .color(Color.white).style(Styles.defaultLabel).row();

        summaryCard.add(getLocalizedText("packloader.report.summary.counts",
            model.summary.success, model.summary.overwritten, model.summary.skipped, model.summary.failed))
            .color(Pal.lightishGray).style(Styles.defaultLabel).row();

        root.add(summaryCard).pad(8f).row();

        // Action Options
        Table actions = new Table();
        actions.defaults().pad(5f).minHeight(56f).growX();

        // 1. Export as JSON
        actions.button(getLocalizedText("packloader.report.format.json"), Icon.download, Styles.flatt, () -> {
            String defaultName = ReportExporter.defaultReportFileName(model, "json");
            exportWithChooser(defaultName, "json", dest -> ReportExporter.writeJson(model, dest));
        }).row();

        // 2. Export as CSV
        actions.button(getLocalizedText("packloader.report.format.csv"), Icon.download, Styles.flatt, () -> {
            String defaultName = ReportExporter.defaultReportFileName(model, "csv");
            exportWithChooser(defaultName, "csv", dest -> ReportExporter.writeCsv(model, dest));
        }).row();

        // 3. Copy to Clipboard (JSON)
        actions.button(getLocalizedText("packloader.report.copy.clipboard"), Icon.copy, Styles.flatt, () -> {
            String json = ReportExporter.generateJson(model);
            if(Core.app != null){
                Core.app.setClipboardText(json);
            }
            if(Vars.ui != null){
                Vars.ui.showInfoFade(getLocalizedText("packloader.report.copied"));
            }
        }).row();

        root.add(actions).pad(8f).row();

        cont.add(root).width(Math.min(Core.graphics != null ? Core.graphics.getWidth() * 0.9f : 440f, 440f)).row();

        addCloseButton();
    }

    private void exportWithChooser(String defaultName, String ext, ConsThrowable<Fi> writer){
        try{
            FileChooser.export(defaultName, ext, dest -> {
                Threads.daemon("PackLoader-WriteExport", () -> {
                    try{
                        writer.get(dest);
                        if(Core.app != null){
                            Core.app.post(() -> {
                                if(Vars.ui != null){
                                    Vars.ui.showInfoFade(getLocalizedText("packloader.report.exported", dest.name()));
                                }
                            });
                        }
                    }catch(Throwable t){
                        Log.err("[PackLoader] Failed to write exported report:", t);
                        if(Core.app != null){
                            Core.app.post(() -> {
                                if(Vars.ui != null){
                                    Vars.ui.showErrorMessage(getLocalizedText("packloader.report.error", t.getMessage()));
                                }
                            });
                        }
                    }
                });
            });
        }catch(Throwable t){
            Log.err("[PackLoader] FileChooser.export failed, falling back to data directory:", t);
            saveFallback(defaultName, writer);
        }
    }

    private void saveFallback(String defaultName, ConsThrowable<Fi> writer){
        Threads.daemon("PackLoader-FallbackExport", () -> {
            try{
                Fi dir = (Vars.dataDirectory != null) ? Vars.dataDirectory.child("reports") : Core.files.local("reports");
                dir.mkdirs();
                Fi dest = dir.child(defaultName);
                writer.get(dest);
                if(Core.app != null){
                    Core.app.post(() -> {
                        if(Vars.ui != null){
                            Vars.ui.showInfoFade(getLocalizedText("packloader.report.exported", dest.path()));
                        }
                    });
                }
            }catch(Throwable t){
                Log.err("[PackLoader] Fallback export failed:", t);
                if(Core.app != null){
                    Core.app.post(() -> {
                        if(Vars.ui != null){
                            Vars.ui.showErrorMessage(getLocalizedText("packloader.report.error", t.getMessage()));
                        }
                    });
                }
            }
        });
    }
}
