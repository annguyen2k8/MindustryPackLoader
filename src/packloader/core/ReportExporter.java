package packloader.core;

import arc.files.Fi;
import arc.util.serialization.Jval;
import packloader.model.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Serializes report models to JSON and CSV, and provides safe file export helpers.
 * Fully decoupled from UI to allow testing and programmatic report generation.
 */
public class ReportExporter{

    public static ReportModel createFromImportReport(ImportReport report, String sourceName){
        ReportModel model = new ReportModel();
        model.reportType = "import";
        model.sourceName = (sourceName != null && !sourceName.isEmpty()) ? sourceName : "batch";

        if(report == null) return model;

        model.elapsedMillis = report.elapsedMillis;
        model.cancelled = report.cancelled;
        model.summary.total = report.processed();
        model.summary.success = report.totalSuccess;
        model.summary.overwritten = report.totalOverwritten;
        model.summary.skipped = report.totalSkipped;
        model.summary.failed = report.totalFailed;

        for(ImportItemResult res : report.results){
            ReportModel.ReportItem item = new ReportModel.ReportItem();
            if(res.entry != null){
                item.fileName = res.entry.sanitizedFileName;
                item.type = res.entry.type != null ? res.entry.type.name() : "";
                item.sizeBytes = res.entry.size;
                item.sha256 = (res.entry.sha256 != null) ? res.entry.sha256 : "";
                item.source = safeSourceName(res.entry);
            }

            item.status = (res.status != null) ? res.status.name() : "";
            item.result = (res.message != null && !res.message.isEmpty()) ? res.message : item.status;
            item.destination = (res.destinationFile != null) ? res.destinationFile.name() : "";
            item.error = (res.status == ImportItemResult.Status.FAILED && res.message != null) ? res.message : "";

            model.items.add(item);
        }

        return model;
    }

    public static ReportModel createFromDryRunPlan(DryRunPlan plan, String sourceName){
        ReportModel model = new ReportModel();
        model.reportType = "plan";
        model.sourceName = (sourceName != null && !sourceName.isEmpty()) ? sourceName : "batch";

        if(plan == null) return model;

        model.summary.total = plan.items.size;
        model.summary.success = plan.newCount + plan.renameCount;
        model.summary.overwritten = plan.overwriteCount;
        model.summary.skipped = plan.skipCount;
        model.summary.failed = 0;

        for(DryRunPlan.DryRunItem item : plan.items){
            ReportModel.ReportItem ri = new ReportModel.ReportItem();
            if(item.entry != null){
                ri.fileName = item.entry.sanitizedFileName;
                ri.type = item.entry.type != null ? item.entry.type.name() : "";
                ri.sizeBytes = item.entry.size;
                ri.sha256 = (item.entry.sha256 != null) ? item.entry.sha256 : "";
                ri.source = safeSourceName(item.entry);
            }

            ri.status = (item.action != null) ? item.action.name() : "";
            ri.result = (item.action != null) ? item.action.localized() : "";
            if(item.isContentDuplicate && item.contentDuplicateMatch != null && !item.contentDuplicateMatch.isEmpty()){
                ri.result += " (" + item.contentDuplicateMatch + ")";
            }
            ri.destination = (item.plannedTargetName != null) ? item.plannedTargetName : "";
            ri.error = "";

            model.items.add(ri);
        }

        return model;
    }

    /** Extracts a safe source identifier without leaking absolute local filesystem paths. */
    public static String safeSourceName(PackEntry entry){
        if(entry == null) return "";
        if(entry.zipFile != null){
            String zName = entry.zipFile.name();
            return (entry.zipEntryName != null && !entry.zipEntryName.isEmpty()) ?
                zName + "/" + entry.zipEntryName : zName;
        }
        if(entry.sourceFile != null){
            return entry.sourceFile.name();
        }
        if(entry.sourcePath != null && !entry.sourcePath.isEmpty()){
            String s = entry.sourcePath.replace('\\', '/');
            int idx = s.lastIndexOf('/');
            return (idx >= 0 && idx < s.length() - 1) ? s.substring(idx + 1) : s;
        }
        return "";
    }

    public static String generateJson(ReportModel model){
        if(model == null) return "{}";

        Jval root = Jval.newObject();
        root.add("timestamp", model.timestamp != null ? model.timestamp : "");
        root.add("reportType", model.reportType != null ? model.reportType : "");
        root.add("sourceName", model.sourceName != null ? model.sourceName : "");
        root.add("elapsedMillis", Jval.valueOf(model.elapsedMillis));
        root.put("cancelled", model.cancelled);

        Jval summary = Jval.newObject();
        summary.add("total", Jval.valueOf(model.summary != null ? model.summary.total : 0));
        summary.add("success", Jval.valueOf(model.summary != null ? model.summary.success : 0));
        summary.add("overwritten", Jval.valueOf(model.summary != null ? model.summary.overwritten : 0));
        summary.add("skipped", Jval.valueOf(model.summary != null ? model.summary.skipped : 0));
        summary.add("failed", Jval.valueOf(model.summary != null ? model.summary.failed : 0));
        root.add("summary", summary);

        Jval items = Jval.newArray();
        if(model.items != null){
            for(ReportModel.ReportItem item : model.items){
                Jval it = Jval.newObject();
                it.add("fileName", item.fileName != null ? item.fileName : "");
                it.add("type", item.type != null ? item.type : "");
                it.add("status", item.status != null ? item.status : "");
                it.add("result", item.result != null ? item.result : "");
                it.add("destination", item.destination != null ? item.destination : "");
                it.add("sizeBytes", Jval.valueOf(item.sizeBytes));
                it.add("sha256", item.sha256 != null ? item.sha256 : "");
                it.add("source", item.source != null ? item.source : "");
                it.add("error", item.error != null ? item.error : "");
                items.add(it);
            }
        }
        root.add("items", items);

        return root.toString(Jval.Jformat.formatted);
    }

    public static String generateCsv(ReportModel model){
        StringBuilder sb = new StringBuilder();
        // UTF-8 BOM for Microsoft Excel / spreadsheet compatibility
        sb.append('\uFEFF');
        sb.append("fileName,type,status,result,destination,sizeBytes,sha256,source,error\r\n");

        if(model != null && model.items != null){
            for(ReportModel.ReportItem item : model.items){
                sb.append(escapeCsv(item.fileName)).append(',')
                  .append(escapeCsv(item.type)).append(',')
                  .append(escapeCsv(item.status)).append(',')
                  .append(escapeCsv(item.result)).append(',')
                  .append(escapeCsv(item.destination)).append(',')
                  .append(item.sizeBytes).append(',')
                  .append(escapeCsv(item.sha256)).append(',')
                  .append(escapeCsv(item.source)).append(',')
                  .append(escapeCsv(item.error)).append("\r\n");
            }
        }
        return sb.toString();
    }

    public static String escapeCsv(String val){
        if(val == null || val.isEmpty()) return "";
        boolean needsQuotes = false;
        for(int i = 0; i < val.length(); i++){
            char c = val.charAt(i);
            if(c == ',' || c == '"' || c == '\n' || c == '\r'){
                needsQuotes = true;
                break;
            }
        }
        if(!needsQuotes) return val;
        return "\"" + val.replace("\"", "\"\"") + "\"";
    }

    public static void writeJson(ReportModel model, Fi dest) throws IOException{
        if(dest == null) throw new IllegalArgumentException("Destination file cannot be null");
        if(dest.parent() != null && !dest.parent().exists()){
            dest.parent().mkdirs();
        }
        byte[] bytes = generateJson(model).getBytes(StandardCharsets.UTF_8);
        dest.writeBytes(bytes);
    }

    public static void writeCsv(ReportModel model, Fi dest) throws IOException{
        if(dest == null) throw new IllegalArgumentException("Destination file cannot be null");
        if(dest.parent() != null && !dest.parent().exists()){
            dest.parent().mkdirs();
        }
        byte[] bytes = generateCsv(model).getBytes(StandardCharsets.UTF_8);
        dest.writeBytes(bytes);
    }

    public static String defaultReportFileName(ReportModel model, String extension){
        String tag = "report";
        if(model != null && model.sourceName != null && !model.sourceName.isEmpty()){
            tag = model.sourceName.replaceAll("[^a-zA-Z0-9_-]", "_");
            if(tag.isEmpty()) tag = "report";
        }
        String type = (model != null && model.reportType != null) ? model.reportType : "import";
        String timeStr = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return "packloader-" + type + "-" + tag + "-" + timeStr + "." + extension;
    }
}
