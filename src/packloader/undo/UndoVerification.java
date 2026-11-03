package packloader.undo;

import arc.struct.Seq;

/** Verification analysis of a transaction prior to executing an Undo operation. */
public class UndoVerification{

    public enum EntryStatus{
        CAN_DELETE,                 // Newly imported or renamed file ready to be removed
        CAN_RESTORE,                // Overwritten file ready to be restored from backup
        ALREADY_REMOVED,            // File was already removed outside mod
        CONFLICT_MODIFIED,          // File modified by user after import
        CONFLICT_BACKUP_MISSING,    // Backup file missing from persistent storage
        CONFLICT_BACKUP_CORRUPTED,  // Backup file hash mismatch or corrupted
        SECURITY_PATH_ESCAPE,       // File outside managed directories
        SKIP                        // Skipped during import, no action
    }

    public static class VerifiedEntry{
        public final TransactionEntry entry;
        public final EntryStatus status;
        public final String detail;

        public VerifiedEntry(TransactionEntry entry, EntryStatus status, String detail){
            this.entry = entry;
            this.status = status;
            this.detail = detail == null ? "" : detail;
        }

        public boolean isConflict(){
            return status == EntryStatus.CONFLICT_MODIFIED
                || status == EntryStatus.CONFLICT_BACKUP_MISSING
                || status == EntryStatus.CONFLICT_BACKUP_CORRUPTED
                || status == EntryStatus.SECURITY_PATH_ESCAPE;
        }

        public boolean isActionable(){
            return status == EntryStatus.CAN_DELETE || status == EntryStatus.CAN_RESTORE;
        }
    }

    public final ImportTransaction transaction;
    public final Seq<VerifiedEntry> verifiedEntries = new Seq<>();
    public int canDeleteCount = 0;
    public int canRestoreCount = 0;
    public int conflictCount = 0;
    public int skippedCount = 0;

    public UndoVerification(ImportTransaction transaction){
        this.transaction = transaction;
    }

    public void add(VerifiedEntry ve){
        verifiedEntries.add(ve);
        switch(ve.status){
            case CAN_DELETE -> canDeleteCount++;
            case CAN_RESTORE -> canRestoreCount++;
            case CONFLICT_MODIFIED, CONFLICT_BACKUP_MISSING, CONFLICT_BACKUP_CORRUPTED, SECURITY_PATH_ESCAPE -> conflictCount++;
            default -> skippedCount++;
        }
    }

    public boolean canUndoAny(){
        return (canDeleteCount + canRestoreCount) > 0;
    }

    public boolean hasConflicts(){
        return conflictCount > 0;
    }
}
