package packloader.util;

import arc.Core;
import arc.func.Prov;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Small helper for the few Mindustry/Arc APIs that must run on the render thread.
 *
 * <p>Scanning and general file work may run on a daemon worker. Game registries such as
 * {@code Vars.maps} and {@code Vars.schematics} (and anything that creates a Texture) must
 * be touched on the main thread only. In unit tests {@link Core#app} is normally null, so
 * calls execute directly on the calling thread.</p>
 */
public final class MainThread{
    private MainThread(){}

    public static boolean isMainThread(){
        try{
            return Core.app == null || Core.app.isOnMainThread();
        }catch(Throwable ignored){
            return true;
        }
    }

    /** Posts to the main thread when needed; the caller is never blocked. */
    public static void post(Runnable runnable){
        if(runnable == null) return;
        try{
            if(Core.app == null || Core.app.isOnMainThread()){
                runnable.run();
            }else{
                Core.app.post(runnable);
            }
        }catch(Throwable ignored){
            // If the application is shutting down there is nowhere safe to report this.
        }
    }

    /** Runs an action on the main thread and waits for it to finish. */
    public static void run(Runnable runnable){
        if(runnable == null) return;
        if(isMainThread()){
            runnable.run();
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Core.app.post(() -> {
            try{
                runnable.run();
            }catch(Throwable t){
                error.set(t);
            }finally{
                latch.countDown();
            }
        });

        await(latch);
        rethrow(error.get());
    }

    /** Runs a provider on the main thread and returns its result. */
    public static <T> T call(Prov<T> provider){
        if(provider == null) return null;
        if(isMainThread()) return provider.get();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Core.app.post(() -> {
            try{
                result.set(provider.get());
            }catch(Throwable t){
                error.set(t);
            }finally{
                latch.countDown();
            }
        });

        await(latch);
        rethrow(error.get());
        return result.get();
    }

    private static void await(CountDownLatch latch){
        boolean interrupted = false;
        while(true){
            try{
                latch.await();
                break;
            }catch(InterruptedException e){
                interrupted = true;
            }
        }
        if(interrupted) Thread.currentThread().interrupt();
    }

    private static void rethrow(Throwable t){
        if(t == null) return;
        if(t instanceof RuntimeException runtime) throw runtime;
        if(t instanceof Error error) throw error;
        throw new RuntimeException(t);
    }
}
