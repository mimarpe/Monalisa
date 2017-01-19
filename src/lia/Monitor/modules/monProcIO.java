/*
 * $Id: monProcIO.java 7207 2011-12-01 13:22:12Z ramiro $
 */
package lia.Monitor.modules;

import java.io.File;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import lia.Monitor.monitor.AttributePublisher;
import lia.Monitor.monitor.MLAttributePublishers;
import lia.Monitor.monitor.MNode;
import lia.Monitor.monitor.MonModuleInfo;
import lia.Monitor.monitor.Result;
import lia.util.Utils;
import lia.util.ntp.NTPDate;
import lia.util.threads.MonALISAExecutors;

/**
 * @author Iosif Legrand
 * @author ramiro
 */
public class monProcIO extends monProcReader {

    /**
     * @since ML 1.5.4
     */
    private static final long serialVersionUID = 8686620657252295224L;

    /** Logger used by this class */
    static final transient Logger logger = Logger.getLogger(monProcIO.class.getName());

    private static final class InterfaceStat {

        final String intfName;

        BigInteger inBytes;

        BigInteger outBytes;

        boolean bNoErr;

        InterfaceStat(final String intfName) {
            this.intfName = intfName;
        }

        @Override
        public String toString() {
            return "InterfaceStat [intfName=" + intfName + ", inBytes=" + inBytes + ", outBytes=" + outBytes + ", bNoErr=" + bNoErr + "]";
        }

    }

    /**
     * module name
     */
    static public String ModuleName = "monProcIO";

    /**
     * parameter names
     */
    static public String[] ResTypes = null;

    /**
     * os name
     */
    static public String OsName = "linux";

    private static final Pattern SPACE_PATTERN = Pattern.compile("(\\s)+");

    private final Map<String, InterfaceStat> interfacesMap = new HashMap<String, InterfaceStat>();

    private final AtomicLong last_measured = new AtomicLong(0);

    /**
     * Total traffic in
     */
    final AtomicReference<Double> totalIN = new AtomicReference<Double>();

    /**
     * Total traffic out
     */
    final AtomicReference<Double> totalOUT = new AtomicReference<Double>();

    static private AtomicBoolean alreadyStarted = new AtomicBoolean(false);

    /**
     * publisher
     */
    static final AttributePublisher publisher = MLAttributePublishers.getInstance();

    private void initPublishTimer() {
        final Runnable ttAttribUpdate = new Runnable() {

            public void run() {
                try {
                    final Double dIn = totalIN.get();
                    if (dIn != null) {
                        publisher.publish("Total_IN", dIn);
                    }

                    final Double dOut = totalOUT.get();
                    if (dOut != null) {
                        publisher.publish("Total_OUT", dOut);
                    }
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "[ monProcIO ] LUS Publisher: Got Exception", t);
                }
            }
        };

        MonALISAExecutors.getMLHelperExecutor().scheduleWithFixedDelay(ttAttribUpdate, 40, 2 * 60, TimeUnit.SECONDS);
        logger.log(Level.INFO, "[ monProcIO ]Attributes Update thread shcheduled");
    }

    /**
     * @throws Exception
     */
    public monProcIO() throws Exception {
        super(ModuleName);
        File f = new File("/proc/net/dev");
        if (!f.exists() || !f.canRead()) {
            throw new MLModuleInstantiationException("Cannot read /proc/net/dev");
        }
        PROC_FILE_NAMES = new String[] {
            "/proc/net/dev"
        };

        info.name = ModuleName;
        isRepetitive = true;
        try {
            resetReaders();
        } catch (Throwable t) {
            logger.log(Level.WARNING, " [ monProcIO ] Failed  for " + PROC_FILE_NAMES[0], t);
            return;
        }

        if (alreadyStarted.compareAndSet(false, true)) {
            initPublishTimer();
        }
    }

    @Override
    public String[] ResTypes() {
        return info.ResTypes;
    }

    public String getOsName() {
        return OsName;
    }

    @Override
    protected Object processProcModule() throws Exception {

        final SortedSet<String> currentInterfaces = new TreeSet<String>();
        final long sTime = Utils.nanoNow();

        final Result res = new Result();
        res.FarmName = Node.getFarmName();
        res.ClusterName = Node.getClusterName();
        res.NodeName = Node.getName();
        res.Module = ModuleName;

        double sumIn = 0;
        double sumOut = 0;

        try {
            String theLine = null;
            resetReaders();
            final long nanoTimeStamp = Utils.nanoNow();
            final double factor = (nanoTimeStamp - last_measured.get()) / (8D * 1000D);
            last_measured.set(nanoTimeStamp);

            for (;;) {
                String lin = bufferedReaders[0].readLine();
                if (lin == null) {
                    break;
                }
                lin = lin.trim();
                if (lin.indexOf("lo") == -1 && lin.indexOf(":") > 0) {
                    boolean bNoErr = false;
                    InterfaceStat iStat = null;
                    BigInteger cIn = BigInteger.ZERO;
                    BigInteger cOut = BigInteger.ZERO;

                    try {
                        theLine = lin;

                        int k1 = theLine.indexOf(":");
                        final String iName = theLine.substring(0, k1).trim();

                        boolean bCount = false;
                        try {
                            final NetworkInterface ni = NetworkInterface.getByName(iName);
                            if(ni != null) {
                                bCount = !ni.isLoopback() && !ni.isPointToPoint() && !ni.isVirtual();
                            } else {
                                if(logger.isLoggable(Level.FINER)) {
                                    logger.log(Level.FINER, "[monProcIO] Unable to determine net interface for name '" + iName + "'. Will count for totals.");
                                }
                                bCount = true;
                            }
                        }catch(Throwable t) {
                            if(logger.isLoggable(Level.FINE)) {
                                logger.log(Level.FINE, "[monProcIO] Unable to determine if '" + iName + "' is virtual. Will count for totals. Cause: ", t);
                            }
                            bCount = true;
                        }
                        
                        if(logger.isLoggable(Level.FINE)) {
                            logger.log(Level.FINE, "Interface '" + iName + "' will " + ((bCount)?"":" NOT ") + " count to total traffic");
                        }
                        
                        iStat = interfacesMap.get(iName);
                        currentInterfaces.add(iName);
                        final boolean bIsNew = (iStat == null);
                        if (bIsNew) {
                            logger.log(Level.INFO, " [ monProcIO ] Interface " + iName + " added");
                            iStat = new InterfaceStat(iName);
                            interfacesMap.put(iName, iStat);
                        }

                        theLine = (theLine.substring(k1 + 1)).trim();
                        final String[] tokens = SPACE_PATTERN.split(theLine);

                        // /////////
                        // IN
                        // /////////
                        cIn = new BigInteger(tokens[0]);
                        if (!bIsNew && iStat.bNoErr) {
                            final double tVal = cIn.subtract(iStat.inBytes).longValue() / factor;
                            if (tVal >= 0) {
                                res.addSet(iName + "_IN", tVal);
                                if(bCount) {
                                    sumIn += tVal;
                                }
                            } else {
                                logger.log(Level.INFO, " [ monProcIO ] Interface restarted or INPUT counter overflow for: " + iName
                                        + "; old counter: " + iStat.inBytes + " new counter: " + cIn);
                            }
                        }

                        // /////////////
                        // OUT
                        // /////////////
                        cOut = new BigInteger(tokens[8]);

                        if (!bIsNew && iStat.bNoErr) {
                            final double tVal = cOut.subtract(iStat.outBytes).longValue() / factor;
                            if (tVal >= 0) {
                                if(bCount) {
                                    sumOut += tVal;
                                }
                                res.addSet(iName + "_OUT", tVal);
                            } else {
                                logger.log(Level.INFO, " [ monProcIO ] Interface restarted or OUTPUT counter overflow for: " + iName
                                        + "; old counter: " + iStat.outBytes + " new counter: " + cOut);
                            }
                        }

                        // /////////////
                        // ERRS
                        // /////////////
                        res.addSet(iName + "_ERRS", Long.valueOf(tokens[2]).doubleValue() + Long.valueOf(tokens[10]).doubleValue());
                        res.addSet(iName + "_COLLS", Long.valueOf(tokens[13]).doubleValue());

                        bNoErr = true;
                    } catch (Throwable t) {
                        logger.log(Level.WARNING, " [ monProcIO ] Got exception parsing /proc/net/dev. Line: " + lin);
                    } finally {
                        if (bNoErr) {
                            iStat.inBytes = cIn;
                            iStat.outBytes = cOut;
                        }
                        if (iStat != null) {
                            iStat.bNoErr = bNoErr;
                        }
                    }

                }
            }// for(;;)

            // check for removed interfaces
            for (Iterator<String> it = interfacesMap.keySet().iterator(); it.hasNext();) {
                String iName = it.next();

                if (!currentInterfaces.contains(iName)) {
                    logger.log(Level.INFO, " [ monProcIO ] Interface " + iName + " was removed. No longer in /proc/net/dev");
                    it.remove();
                }
            }

            // cannot use Double.valueOf() yet ...not in 1.4
            this.totalIN.set(Double.valueOf(sumIn));
            this.totalOUT.set(Double.valueOf(sumOut));

            res.time = NTPDate.currentTimeMillis();

            //
            // WARNING. Traffic may be counted twice for some interfaces ... e.g. bonding, 
            // isVirtual() may work.
            //
            res.addSet("TOTAL_NET_IN", sumIn);
            res.addSet("TOTAL_NET_OUT", sumOut);

            if (res.param == null || res.param.length == 0 || res.param_name == null || res.param_name.length == 0) {
                logger.log(Level.INFO, " [ monProcIO ] Not returning results ... NO param or param_name returned");
                return null;
            }

            final LinkedList<Result> l = new LinkedList<Result>();
            l.add(res);
            return l;

        } finally {
            if (logger.isLoggable(Level.FINER)) {
                StringBuilder sb = new StringBuilder();
                sb.append("[ monProcIO ] dt = [ ").append(TimeUnit.NANOSECONDS.toMillis(Utils.nanoNow() - sTime)).append(" ] ms");
                if (logger.isLoggable(Level.FINEST)) {
                    sb.append(" Returning: ").append(res).append("\n");
                }
                logger.log(Level.FINER, sb.toString());
            }
        }
    }

    public MonModuleInfo getInfo() {
        return info;
    }

    /**
     * @param args
     * @throws Exception
     */
    static public void main(String[] args) throws Exception {
        String host = "localhost"; // args[0] ;
        monProcIO aa = new monProcIO();
        String ad = null;
        logger.setLevel(Level.ALL);
        try {
            ad = InetAddress.getByName(host).getHostAddress();
        } catch (Exception e) {
            System.out.println(" Can not get ip for node " + e);
            System.exit(-1);
        }

        aa.init(new MNode(host, ad, null, null), null, null);

        for (;;) {
            try {

                Thread.sleep(5000);
                final long sTime = Utils.nanoNow();
                final Object bb = aa.doProcess();
                final long dtNanos = Utils.nanoNow() - sTime;

                System.out.println(" dtNanos: " + dtNanos + "; dtMillis: " + TimeUnit.NANOSECONDS.toMillis(dtNanos) + "; returning: " + bb);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }

    }
}
