/*
 * Created on Aug 27, 2007
 */
package lia.util.threads;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import lia.Monitor.monitor.AppConfig;
import lia.Monitor.monitor.AppConfigChangeListener;

/**
 * Helper class which accepts different style of executors
 * It may be configured using AppConfig ...
 * 
 * @author ramiro
 */
public final class MLExecutorsFactory {

    /** K: the executor name, V: the executor itself */
    private static final Map<String, MLScheduledThreadPoolExecutor> executorsMap = new TreeMap<String, MLScheduledThreadPoolExecutor>();

    public static final int DEFAULT_CORE_THREADS_COUNT = 1;

    public static final int DEFAULT_MAX_THREADS_COUNT = 50;

    public static final long DEFAULT_TIMEOUT_MINUTES = 2;

    static {
        reloadExecutorsParam();

        AppConfig.addNotifier(new AppConfigChangeListener() {

            @Override
            public void notifyAppConfigChanged() {
                reloadExecutorsParam();
            }

        });
    }

    private static final void reloadExecutorsParam() {
        synchronized (executorsMap) {
            for (Map.Entry<String, MLScheduledThreadPoolExecutor> entry : executorsMap.entrySet()) {
                final String executorName = entry.getKey();
                final MLScheduledThreadPoolExecutor executor = entry.getValue();

                int coreThreads = AppConfig.geti(executorName + ".CORE_POOL_THREADS_COUNT", DEFAULT_CORE_THREADS_COUNT);
                if (coreThreads < 0) {
                    coreThreads = DEFAULT_CORE_THREADS_COUNT;
                }

                int maxThreads = AppConfig.geti(executorName + ".MAX_POOL_THREADS_COUNT", DEFAULT_MAX_THREADS_COUNT);

                if (maxThreads < coreThreads) {
                    maxThreads = coreThreads;
                }

                long timeout = AppConfig.getl(executorName + ".TIMEOUT", DEFAULT_TIMEOUT_MINUTES);

                if (timeout < 1) {
                    timeout = DEFAULT_TIMEOUT_MINUTES;
                }

                executor.setCorePoolSize(coreThreads);
                executor.setMaximumPoolSize(maxThreads);
                executor.setKeepAliveTime(timeout, TimeUnit.MINUTES);
            }
        }
    }

    public static final MLScheduledThreadPoolExecutor getScheduledExecutorService(final String name, int defaultCoreThreads, int defaultMaxThreads, long defaultTimeout) throws Exception {
        MLScheduledThreadPoolExecutor executor = null;

        synchronized (executorsMap) {
            executor = executorsMap.get(name);
            if (executor == null) {
                if (defaultCoreThreads < 0) {
                    defaultCoreThreads = DEFAULT_CORE_THREADS_COUNT;
                }

                int coreThreads = AppConfig.geti(name + ".CORE_POOL_THREADS_COUNT", defaultCoreThreads);
                if (coreThreads < 0) {
                    coreThreads = defaultCoreThreads;
                }

                int maxThreads = AppConfig.geti(name + ".MAX_POOL_THREADS_COUNT", defaultMaxThreads);

                if (maxThreads < coreThreads) {
                    maxThreads = coreThreads;
                }

                if (defaultTimeout < 1) {
                    defaultTimeout = DEFAULT_TIMEOUT_MINUTES;
                }

                long timeout = AppConfig.getl(name + ".TIMEOUT", defaultTimeout);

                if (timeout < 1) {
                    timeout = DEFAULT_TIMEOUT_MINUTES;
                }

                executor = new MLScheduledThreadPoolExecutor(name, maxThreads, timeout, TimeUnit.MINUTES);

                executorsMap.put(name, executor);
            } // if
        }

        return executor;
    }

    public static final ScheduledExecutorService getScheduledExecutorService(final String name) throws Exception {
        return getScheduledExecutorService(name, DEFAULT_CORE_THREADS_COUNT, DEFAULT_MAX_THREADS_COUNT, DEFAULT_TIMEOUT_MINUTES);
    }

    public static Map<String, MLScheduledThreadPoolExecutor> getExecutors() {
        synchronized (executorsMap) {
            return new HashMap<String, MLScheduledThreadPoolExecutor>(executorsMap);
        }
    }

    private static final class SafeRunnable implements Runnable {
        private final String name;
        private final Logger logger;
        private final Runnable command;
        
        /**
         * @param name
         * @param logger
         */
        public SafeRunnable(Runnable command, String name, Logger logger) {
            this.name = name;
            this.logger = logger;
            this.command = command;
        }

        @Override
        public void run() {
            try {
                command.run();
            } catch(Throwable t) {
                if(logger != null) {
                    logger.log(Level.WARNING, " [ HANDLED ] ThPool worker " + name + " got exception ", t);
                }
            }
        }
        
    }
    /**
     * @param command
     * @param logger
     * @return
     */
    public static final Runnable safeRunnable(final Runnable command, final String name, final Logger logger) {
        return new SafeRunnable(command, name, logger);
    }
}
