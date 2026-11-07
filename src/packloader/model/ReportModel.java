package packloader.model;

import arc.struct.Seq;
import java.time.Instant;

/**
 * Decoupled data model representing an exportable report (either post-import or pre-import plan).
 * Independent of UI components so it can be serialized to JSON, CSV, or inspected by automated tools.
 */
public class ReportModel{

    public static class Summary{
        public int total;
        public int success;
        public int overwritten;
        public int skipped;
        public int failed;
    }

    public static class ReportItem{
        public String fileName = "";
        public String type = "";       // "MAP" or "SCHEMATIC"
        public String status = "";     // "SUCCESS", "OVERWRITTEN", "SKIPPED", "FAILED", "NEW", "RENAME"
        public String result = "";     // Detailed human-readable action description or reason
        public String destination = "";// Relative destination filename if imported/planned
        public long sizeBytes = 0;     // File size in bytes
        public String sha256 = "";     // Content hash if available
        public String source = "";     // Safe source filename or zip entry (no sensitive user paths)
        public String error = "";      // Error message if failed, empty otherwise
    }

    public String timestamp;
    public String reportType = "import"; // "import", "plan", or "scan"
    public String sourceName = "";
    public long elapsedMillis = 0;
    public boolean cancelled = false;
    public final Summary summary = new Summary();
    public final Seq<ReportItem> items = new Seq<>();

    public ReportModel(){
        this.timestamp = Instant.now().toString();
    }
}
