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
        return new ImportItemResult(entry, Status.SUCCESS, getLocalizedText("packloader.result.msg.success", dest.name()), dest);
    }

    public static ImportItemResult overwritten(PackEntry entry, Fi dest){
        return new ImportItemResult(entry, Status.OVERWRITTEN, getLocalizedText("packloader.result.msg.overwritten", dest.name()), dest);
    }

    public static ImportItemResult skipped(PackEntry entry, String reason){
        return new ImportItemResult(entry, Status.SKIPPED, reason, null);
    }

    public static ImportItemResult failed(PackEntry entry, String error){
        return new ImportItemResult(entry, Status.FAILED, error, null);
    }
}
