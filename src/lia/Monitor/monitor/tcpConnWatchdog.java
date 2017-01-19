/*
 * Created on Nov 13, 2003
 * 
 * $Id: tcpConnWatchdog.java 6865 2010-10-10 10:03:16Z ramiro $
 * 
 */
package lia.Monitor.monitor;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import lia.util.Utils;
import lia.util.threads.MonALISAExecutors;

/**
 * 
 * This class is responsible to send Keep Alive messages (ML_PING) over tcpConn
 * if no other messages were sent in between.
 * 
 * The class uses a thread pool and manages itself the KeepAlive tasks, trying to be 
 * as less intrusive as possible. I hope it is bug free, but you never now
 * when things go the other way around :)
 * 
 * @author ramiro
 * 
 */
public final class tcpConnWatchdog {
    
    /** Logger used by this class */
    private static final transient Logger logger = Logger.getLogger("lia.Monitor.Network");

    private static final ScheduledExecutorService executor = MonALISAExecutors.getMLNetworkExecutor();
    
    private static final AtomicLong totalAddedConnections = new AtomicLong(0);
    private static final AtomicLong totalRemovedConnections = new AtomicLong(0);
    private static final AtomicLong keepAliveTasksCount = new AtomicLong(0);
    private static final AtomicLong mlPingSentCount = new AtomicLong(0);

    //K: tcpConnName ... Value: KeepAliveTaskFuture
    private static final ConcurrentMap<tcpConn, KeepAliveTaskFuture> keepAliveTaskFutureMap = new ConcurrentHashMap<tcpConn, KeepAliveTaskFuture>();
    
    static {
        executor.scheduleWithFixedDelay(new CleanUpKeepAliveTask(), 1, 5, TimeUnit.MINUTES);
    }
    
    private static final class KeepAliveTaskFuture {
        final KeepAliveTask kat;
        final ScheduledFuture<?> sf;
        
        KeepAliveTaskFuture(final KeepAliveTask kat, final ScheduledFuture<?> sf) {
            this.kat = kat;
            this.sf = sf;
        }
    }
    
    private static final class CleanUpKeepAliveTask implements Runnable {
        public void run() {
            
            try {
                for(Iterator<KeepAliveTaskFuture> it = keepAliveTaskFutureMap.values().iterator(); it.hasNext();) {
                    final KeepAliveTaskFuture katf = it.next();
                    final tcpConn tc = katf.kat.tc; 
                    if(tc == null || !tc.isConnected() ) {
                        try {
                            if(logger.isLoggable(Level.FINER)) {
                                logger.log(Level.FINER, " [ TCW ] [ checkConns ] cancelling task for: " + tc);
                            }
                            katf.sf.cancel(false);
                        } finally {
                            it.remove();
                        }
                    }
                }
            } catch(Throwable t1) {
                logger.log(Level.WARNING, "tcpConnWatchdog got exception in main loop", t1);
                try {
                    Thread.sleep(1000);
                }catch(Throwable ignore) {}
            }
        }
    }
    
    private final static class KeepAliveTask implements Runnable {
        
        private final tcpConn tc;
        
        public KeepAliveTask(tcpConn tc) {
            this.tc = tc;
        }
        
        public void run() {
            keepAliveTasksCount.incrementAndGet();
            
            try {
                if(tc.isConnected()) {
                    final long now = Utils.nanoNow();
                    
                    if(now > (tc.lastMLPingSendTime.get() + tcpConn.ML_PING_DELAY_NANOS) ) {
                        //Do not send at every iteration ...
                        if(!tc.isSending()) {
                            mlPingSentCount.incrementAndGet();
                            try {
                                tc.directSend(tcpConn.ML_PING_BYTE_MSG);
                            } catch ( Throwable t ) {
                                logger.log(Level.WARNING, "Got exc sending ML_PING_MSG for [ " + tc.runningThread.getName() + " ] the connection is closed", t);
                                tc.close_connection();
                            } 
                        } else {
                            try {
                                tc.checkSendTimeout();
                            } catch(Throwable t) {
                                if(logger.isLoggable(Level.FINE)) {
                                    logger.log(Level.WARNING, " [ TCW ] got exception checking for timeout ... ", t);
                                }
                            }
                            if(logger.isLoggable(Level.FINE)) {
                                logger.log(Level.FINE,"Delaying ML_PING (tc.isSending() == true) message for " + tc.toString());
                            }
                        }
                    } else {
                        if(logger.isLoggable(Level.FINEST)) {
                            logger.log(Level.FINEST, "[ TCW ] Not sending ML_PING for " + tc.runningThread.getName() + " because already sent a message ... ");
                        }
                    }
                }
            } catch (Throwable t ) {
                logger.log(Level.WARNING, "Got exception in KeepAliveTask main loop", t);
            }
        }
        
        public String toString() {
            return "KeepAliveTask for " + ((tc == null)?" null !!!!!!!! ":tc.toString());
        }

    }
    
    public static final Map<String, Double> getMonitoringParams() {
        final Map<String, Double> m = new HashMap<String, Double>();
        m.put("TCW_TotalAdded", Double.valueOf(totalAddedConnections.get()));
        m.put("TCW_TotalKeepAlive", Double.valueOf(keepAliveTaskFutureMap.size()));
        return m;
    }
    
    public static final long totalNumberOfAddedConnections() {
        return totalAddedConnections.get();
    }

    public static final long totalNumberOfRemovedConnections() {
        return totalRemovedConnections.get();
    }
    
    static final void addToWatch(tcpConn tc) {
        if ( tc != null ) {
            final KeepAliveTask kat = new KeepAliveTask(tc);
            final ScheduledFuture<?> sf = executor.scheduleWithFixedDelay(kat, Math.round(Math.random() * tcpConn.ML_PING_DELAY), tcpConn.ML_PING_DELAY, TimeUnit.MILLISECONDS);
            final KeepAliveTaskFuture old = keepAliveTaskFutureMap.putIfAbsent(tc, new KeepAliveTaskFuture(kat, sf));
            if(old != null) {
                //this should not happen!! It is just a cross-check
                logger.log(Level.WARNING, "\n\n [ TCW ] [ addToWatch ] [ HANDLED ] An older KeepAliveTaskFuture for " + tc.runningThread.getName() + " already existed .... The connection will be closed.");
                try {
                    kat.tc.close_connection();
                } catch(Throwable t) {
                    logger.log(Level.WARNING, "\n\n [ TCW ] [ addToWatch ] [ HANDLED ] An older KeepAliveTaskFuture for " + tc.runningThread.getName() + " already existed ....Exception closing the connecion:", t);
                }
            } else {
                totalAddedConnections.incrementAndGet();
            }
        }
    }
    
    static final void remove(tcpConn tc) {
        final KeepAliveTaskFuture old = keepAliveTaskFutureMap.remove(tc);
        if(old == null) {
            if(logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, " [ TCW ] [ remove ] Old KeepAliveTaskFuture is null for " + tc.runningThread.getName());
            }
        } else {
            if(logger.isLoggable(Level.FINER)) {
                logger.log(Level.FINER, " [ TCW ] [ remove ] removing a closed connection " + tc.runningThread.getName());
            }
            old.sf.cancel(false);
        }
    }

}
