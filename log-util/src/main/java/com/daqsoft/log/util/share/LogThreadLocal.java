package com.daqsoft.log.util.share;

import java.util.Optional;

/**
 * Manage log content thread local
 */
public class LogThreadLocal {
    //Inheritable Thread local for sub thread
    private static final InheritableThreadLocal<ThreadSemaphore> threadLocal
            = new InheritableThreadLocal<>();
    public static Optional<ThreadSemaphore> getThreadSemaphore(){
        return Optional.ofNullable(threadLocal.get());
    }
    public static void setThreadSemaphore(ThreadSemaphore threadSemaphore ){
        threadLocal.set(threadSemaphore);
    }
}
