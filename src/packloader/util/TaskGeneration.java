package packloader.util;

/**
 * Monotonic token used to discard results from stale asynchronous scans/imports.
 * A newer task invalidates every token issued before it.
 */
public final class TaskGeneration{
    private int generation;

    public synchronized int begin(){
        return ++generation;
    }

    public synchronized boolean isCurrent(int token){
        return token == generation;
    }

    public synchronized void cancel(){
        generation++;
    }

    public synchronized int current(){
        return generation;
    }
}
