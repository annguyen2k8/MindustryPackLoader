package packloader.model;

import arc.struct.Seq;

public class ImportReport{
    public final Seq<ImportItemResult> results = new Seq<>();
    public int totalSelected;
    public int totalSuccess;
    public int totalOverwritten;
    public int totalSkipped;
    public int totalFailed;
    public long elapsedMillis;

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
