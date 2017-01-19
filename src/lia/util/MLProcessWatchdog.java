/*
 * $Id: MLProcessWatchdog.java 6865 2010-10-10 10:03:16Z ramiro $
 */
package lia.util;

import java.util.Date;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import lia.Monitor.monitor.ShutdownReceiver;
import lia.util.ntp.NTPDate;
import lia.util.threads.MonALISAExecutors;

/**
 * Helper Class to 'kill' MLProcess-es that stopped responding
 * 
 * @author ramiro
 */
class MLProcessWatchdog implements ShutdownReceiver {

    /** Logger used by this class */
    private static final transient Logger logger = Logger.getLogger(MLProcessWatchdog.class.getName());

    private static final MLProcessWatchdog _thisInstance = new MLProcessWatchdog();
    private static final Object VOID_VALUE = new Object();
    
    private final Hashtable htProc = new Hashtable();
    private final AtomicBoolean isShutDown = new AtomicBoolean(false);
    
    private class MLProcessKillTask implements Runnable {
        private final Process p;
        
        public MLProcessKillTask(Process p) {
            this.p = p;
        }
        
        public void run() {
            if (logger.isLoggable(Level.FINER)) {
                logger.log(Level.FINER, "[ MLProcessWatchdog ] Removing p [ " + p + " ] "); 
            }
            
            htProc.remove(p);
            
            try {
                p.destroy();
            } catch(Throwable t) {
                if(logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, " [ MLProcessWatchdog ] Got exception killing p [ " + p + " ] ", t);
                }
            }
        }
    }
    
    private MLProcessWatchdog() {
        ShutdownManager.getInstance().addModule(this);
    }

    static final MLProcessWatchdog getInstance() {
        return _thisInstance;
    }
    
    public void add(Process p, long stopTime) {
        if (p != null) {
            if (logger.isLoggable(Level.FINEST)) {
                logger.log(Level.FINEST, "[ MLProcessWatchdog ] Adding to Watchdog [ " + p + " ] Watch until [ " + new Date(stopTime) + " ]");
            }
            if(isShutDown.get()) {
                if (logger.isLoggable(Level.FINEST)) {
                    logger.log(Level.FINEST, "[ MLProcessWatchdog ] ML IS IN SHUTDOWN HOOK THE PROCESS [ " + p + " ] will be ignored ... ");
                }
                return;
            }
            htProc.put(p, VOID_VALUE);
            MonALISAExecutors.getMLHelperExecutor().schedule(new MLProcessKillTask(p), stopTime - NTPDate.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }
    }

	public void Shutdown() {
	    isShutDown.set(true);
		try{
		    synchronized(htProc) {
	            for(Iterator peit = htProc.keySet().iterator(); peit.hasNext(); ){
	                final Process p = (Process) peit.next();
	                if(logger.isLoggable(Level.FINE)){
	                	logger.fine("Destroying unfinished child process: "+p);
	                }
	                try{
	                    p.destroy();
	                }catch(Throwable t){
	                    logger.log(Level.WARNING, "Failed to destroy process during ML shutdown: " + p, t);
	                }
	            }
		    }
		}catch(Throwable t){
			logger.log(Level.WARNING, "Failed to destroy all running sub-processes during ML shutdown.", t);
		}
	}
}
