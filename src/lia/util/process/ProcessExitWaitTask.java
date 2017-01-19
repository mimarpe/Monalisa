/*
 * $Id: ProcessExitWaitTask.java 6878 2010-10-12 20:20:16Z ramiro $
 * Created on Oct 10, 2010
 */
package lia.util.process;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author ramiro
 */
class ProcessExitWaitTask implements Runnable {

    static final Logger logger = Logger.getLogger(ProcessExitWaitTask.class.getName());

    final ExternalProcess procWrapper;
    
    ProcessExitWaitTask(ExternalProcess procWrapper) {
        // TODO assert procWrapper != null && procWrapper.p != null
        this.procWrapper = procWrapper;
    }

    @Override
    public void run() {
        int exitStatus = -12;
        final String logPrefix = "ProcessExitWaitTask '" + procWrapper + "'";
//        if(logger.isLoggable(Level.FINER)) {
//            logger.log(Level.FINER, logPrefix + " started. entering barrier.");
//        }

//        if(barrier != null) {
//            for(;;) {
//                if(procWrapper.timedOut.get() || procWrapper.finished.get()) {
//                    break;
//                }
//                
//                try {
//                    barrier.await(10, TimeUnit.SECONDS);
//                    break;
//                } catch (InterruptedException e) {
//                    if(procWrapper.timedOut.get()) {
//                        //normal signaling
//                        if(logger.isLoggable(Level.FINE)) {
//                            logger.log(Level.FINE, " My process " + procWrapper + " timed out. ");
//                        }
//                    } else {
//                        logger.log(Level.WARNING, " Got interrupted exception though process did not timed out ");
//                    }
//                    break;
//                } catch (BrokenBarrierException e) {
//                    logger.log(Level.WARNING, " Got BrokenBarrierException though process did not timed out ");
//                    break;
//                } catch (TimeoutException e) {
//                    if(logger.isLoggable(Level.FINE)) {
//                        logger.log(Level.FINE, " My process " + procWrapper + " timed out. ");
//                    }
//                }
//            }
//        }
        
        if(logger.isLoggable(Level.FINER)) {
            logger.log(Level.FINER, logPrefix + " started. exiting barrier.");
        }
        
        if(procWrapper.timedOut.get() || procWrapper.finished.get()) {
            procWrapper.notifyProcessExit(exitStatus);
            return;
        }
        
        final Process p = procWrapper.p;
        try {
            exitStatus = p.waitFor();
        } catch (InterruptedException ie) {
            if(procWrapper.timedOut.get()) {
                //normal signaling
                if(logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, " My process " + procWrapper + " timed out. ");
                }
            } else {
                logger.log(Level.WARNING, " Got interrupted exception though process did not timed out ");
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING, logPrefix + " got exception waiting for process to exit. Cause: t");
        } finally {
            if(logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, logPrefix + " exits main loop. notify exitStatus: " + exitStatus);
            }
            procWrapper.notifyProcessExit(exitStatus);
        }
    }
}
