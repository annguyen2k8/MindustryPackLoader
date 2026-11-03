package packloader.model;

import arc.files.Fi;

import static packloader.util.Localization.getLocalizedText;

public class ImportItemResult{
    public enum Status{
        SUCCESS("packloader.result.status.success"),
        OVERWRITTEN("packloader.result.status.overwritten"),
        SKIPPED("packloader.result.status.skipped"),
        FAILED("packloader.result.status.failed");

        public final String labelKey;
        Status(String labelKey){
            this.labelKey = labelKey;
        }

        public String localizedLabel(){
            return getLocalizedText(labelKey);
        }
    }

    public final PackEntry entry;
    public final Status status;
    public final String message;
    public final Fi destinationFile;

    public ImportItemResult(PackEntry entry, Status status, String message, Fi destinationFile){
        this.entry = entry;
        this.status = status;
        this.message = message;
        this.destinationFile = destinationFile;
    }

    public static ImportItemResult success(PackEntry entry, Fi dest){
        String msg = (dest != null && !dest.name().equals(entry.sanitizedFileName)) ?
            getLocalizedText("packloader.result.msg.renamed", dest.name()) : "";
        return new ImportItemResult(entry, Status.SUCCESS, msg, dest);
    }

    public static ImportItemResult overwritten(PackEntry entry, Fi dest){
        return new ImportItemResult(entry, Status.OVERWRITTEN, getLocalizedText("packloader.result.msg.overwritten"), dest);
    }

    public static ImportItemResult skipped(PackEntry entry, String reason){
        String msg = (reason == null || reason.isEmpty()) ?
            getLocalizedText("packloader.result.msg.skipped") : reason;
        return new ImportItemResult(entry, Status.SKIPPED, msg, null);
    }

    public static ImportItemResult failed(PackEntry entry, String error){
        return new ImportItemResult(entry, Status.FAILED, error, null);
    }
}
