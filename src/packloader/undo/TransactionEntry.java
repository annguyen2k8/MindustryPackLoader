package packloader.undo;

import arc.util.serialization.Jval;
import packloader.model.PackType;

/** Represents a single file entry in an import transaction with integrity metadata for undo verification. */
public class TransactionEntry{
    public final String originalFileName;
    public final String originalDisplayName;
    public final PackType type;
    public final TransactionAction action;
    public final String destinationPath;
    public final String backupFileName;
    public final long importedFileSize;
    public final String importedSha256;
    public final long backupFileSize;
    public final String backupSha256;
    public boolean undone;

    public TransactionEntry(String originalFileName, String originalDisplayName, PackType type,
                            TransactionAction action, String destinationPath, String backupFileName,
                            long importedFileSize, String importedSha256,
                            long backupFileSize, String backupSha256, boolean undone){
        this.originalFileName = originalFileName == null ? "" : originalFileName;
        this.originalDisplayName = originalDisplayName == null ? "" : originalDisplayName;
        this.type = type;
        this.action = action;
        this.destinationPath = destinationPath == null ? "" : destinationPath;
        this.backupFileName = backupFileName == null ? "" : backupFileName;
        this.importedFileSize = importedFileSize;
        this.importedSha256 = importedSha256 == null ? "" : importedSha256;
        this.backupFileSize = backupFileSize;
        this.backupSha256 = backupSha256 == null ? "" : backupSha256;
        this.undone = undone;
    }

    public Jval toJson(){
        Jval obj = Jval.newObject();
        obj.put("originalFileName", originalFileName);
        obj.put("originalDisplayName", originalDisplayName);
        if(type != null) obj.put("type", type.name());
        if(action != null) obj.put("action", action.name());
        obj.put("destinationPath", destinationPath);
        obj.put("backupFileName", backupFileName);
        obj.put("importedFileSize", importedFileSize);
        obj.put("importedSha256", importedSha256);
        obj.put("backupFileSize", backupFileSize);
        obj.put("backupSha256", backupSha256);
        obj.put("undone", undone);
        return obj;
    }

    public static TransactionEntry fromJson(Jval json){
        if(json == null) return null;
        String origFile = json.getString("originalFileName", "");
        String origDisplay = json.getString("originalDisplayName", "");
        String typeStr = json.getString("type", null);
        PackType type = typeStr != null ? PackType.valueOf(typeStr) : null;
        String actionStr = json.getString("action", "SKIP");
        TransactionAction action = TransactionAction.valueOf(actionStr);
        String dest = json.getString("destinationPath", "");
        String backup = json.getString("backupFileName", "");
        long impSize = json.getLong("importedFileSize", 0L);
        String impHash = json.getString("importedSha256", "");
        long bakSize = json.getLong("backupFileSize", 0L);
        String bakHash = json.getString("backupSha256", "");
        boolean undone = json.getBool("undone", false);
        return new TransactionEntry(origFile, origDisplay, type, action, dest, backup, impSize, impHash, bakSize, bakHash, undone);
    }
}
