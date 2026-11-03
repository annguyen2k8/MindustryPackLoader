package packloader.model;

import arc.struct.Seq;
import packloader.undo.ImportTransaction;

public class ImportReport{
    public final Seq<ImportItemResult> results = new Seq<>();
    public ImportTransaction transaction;
    public int totalSelected;
    public int totalSuccess;
    public int totalOverwritten;
    public int totalSkipped;
    public int totalFailed;
    public long elapsedMillis;
    /** True when the user cancelled before every selected entry was processed. */
    public boolean cancelled;

    /** Number of entries that produced a result, including skipped and failed ones. */
    public int processed(){
        return results.size;
    }

    public void addResult(ImportItemResult result){
        results.add(result);
        switch(result.status){
            case SUCCESS -> totalSuccess++;
            case OVERWRITTEN -> totalOverwritten++;
            case SKIPPED -> totalSkipped++;
            case FAILED -> totalFailed++;
        }
    }

    public boolean hasFailures(){
        return totalFailed > 0;
    }

    public boolean hasSuccess(){
        return (totalSuccess + totalOverwritten) > 0;
    }

}
