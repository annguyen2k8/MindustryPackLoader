package packloader.undo;

import arc.struct.Seq;
import arc.util.serialization.Jval;

/** Represents a persistent batch import transaction containing manifest entries and metadata. */
public class ImportTransaction{
    public final String id;
    public final long timestamp;
    public final String sourceName;
    public final Seq<TransactionEntry> entries = new Seq<>();
    public boolean undone;
    public long totalBackupBytes;

    public ImportTransaction(String id, long timestamp, String sourceName){
        this.id = id;
        this.timestamp = timestamp;
        this.sourceName = sourceName == null ? "" : sourceName;
    }

    public int getNewCount(){
        return entries.count(e -> e.action == TransactionAction.NEW);
    }

    public int getOverwriteCount(){
        return entries.count(e -> e.action == TransactionAction.OVERWRITE);
    }

    public int getRenameCount(){
        return entries.count(e -> e.action == TransactionAction.RENAME);
    }

    public int getSkipCount(){
        return entries.count(e -> e.action == TransactionAction.SKIP);
    }

    public int getActiveItemCount(){
        return entries.count(e -> e.action != TransactionAction.SKIP);
    }

    public Jval toJson(){
        Jval obj = Jval.newObject();
        obj.put("id", id);
        obj.put("timestamp", timestamp);
        obj.put("sourceName", sourceName);
        obj.put("undone", undone);
        obj.put("totalBackupBytes", totalBackupBytes);

        Jval arr = Jval.newArray();
        for(TransactionEntry entry : entries){
            arr.add(entry.toJson());
        }
        obj.put("entries", arr);
        return obj;
    }

    public static ImportTransaction fromJson(Jval json){
        if(json == null) return null;
        String id = json.getString("id", "");
        long ts = json.getLong("timestamp", 0L);
        String src = json.getString("sourceName", "");
        ImportTransaction tx = new ImportTransaction(id, ts, src);
        tx.undone = json.getBool("undone", false);
        tx.totalBackupBytes = json.getLong("totalBackupBytes", 0L);

        Jval arr = json.get("entries");
        if(arr != null && arr.isArray()){
            for(Jval item : arr.asArray()){
                TransactionEntry entry = TransactionEntry.fromJson(item);
                if(entry != null) tx.entries.add(entry);
            }
        }
        return tx;
    }
}
