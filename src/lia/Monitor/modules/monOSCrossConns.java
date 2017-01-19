/*
 * $Id: monOSCrossConns.java 6865 2010-10-10 10:03:16Z ramiro $
 */
package lia.Monitor.modules;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Observable;
import java.util.Observer;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import lia.Monitor.Agents.OpticalPath.OpticalCrossConnectLink;
import lia.Monitor.monitor.AttributePublisher;
import lia.Monitor.monitor.MCluster;
import lia.Monitor.monitor.MLAttributePublishers;
import lia.Monitor.monitor.MNode;
import lia.Monitor.monitor.MonModuleInfo;
import lia.Monitor.monitor.MonitoringModule;
import lia.Monitor.monitor.Result;
import lia.Monitor.monitor.cmdExec;
import lia.Monitor.monitor.eResult;
import lia.util.DateFileWatchdog;
import lia.util.ntp.NTPDate;
import lia.util.telnet.OSTelnet;
import lia.util.telnet.OSTelnetFactory;

/**
 * @author ramiro
 * @author Ciprian Dobre
 */
public class monOSCrossConns extends cmdExec implements MonitoringModule, Observer {

    /**
     * 
     */
    private static final long serialVersionUID = 3853069777126780557L;

    /** Logger used by this class */
    private static final transient Logger logger = Logger.getLogger(monOSCrossConns.class.getName());

    public MNode Node;

    static String[] ResTypes = {
        "Port-Conn"
    };

    public MonModuleInfo info;

    public boolean isRepetitive = false;

    String moduleName;

    short switchType;

    OSTelnet osConn;

    HashMap lastConns;

    boolean shouldPublishPortsList = false;

    ArrayList portsList;

    Object portsSync;

    DateFileWatchdog dfw;

    File moduleConfFile;

    boolean firstTime;

    // int portsSendCounter;

    private static final Pattern EQ_PATTERN = Pattern.compile("=");

    private static final Pattern COMMA_PATTERN = Pattern.compile(",");

    ArrayList removeCrossConnsResults;

    private static final AttributePublisher publisher = MLAttributePublishers.getInstance();

    public monOSCrossConns() {
        firstTime = true;
        lastConns = null;
        moduleName = "monOSCrossConns";
        isRepetitive = true;
        portsSync = new Object();
        // portsSendCounter = 0;
        shouldPublishPortsList = false;
    }

    public MonModuleInfo init(MNode Node, String arg) {
        this.Node = Node;
        logger.log(Level.INFO, "monOSCrossConns: farmName=" + Node.getFarmName() + " clusterName= " + Node.getClusterName() + " nodeName=" + Node.getName() + " arg = " + arg);
        if (arg.startsWith("\"")) {
            arg = arg.substring(1);
        }
        if (arg.endsWith("\"")) {
            arg = arg.substring(0, arg.length() - 1);
        }
        String[] args = arg.split("(\\s)*;(\\s)*");
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String argT = args[i].trim();
                if (argT.startsWith("SwitchType")) {
                    String switchName = argT.split("(\\s)*=(\\s)*")[1];
                    logger.log(Level.INFO, "monOSCrossConns: swType = " + switchName);
                    switchType = OSTelnet.getType(switchName);
                } else if (argT.startsWith("PortMap")) {
                    try {
                        File f = new File(argT.split("(\\s)*=(\\s)*")[1].trim());
                        if (f.exists() && f.canRead()) {
                            dfw = DateFileWatchdog.getInstance(f, 2 * 1000);
                            dfw.addObserver(this);
                            moduleConfFile = f;
                        } else {
                            logger.log(Level.WARNING, " File for PortMap = " + f.toString() + " cannot be read!");
                        }
                    } catch (Throwable t) {
                        logger.log(Level.WARNING, " Got exc in init", t);
                        dfw = null;
                        moduleConfFile = null;
                    }
                } else if (argT.equalsIgnoreCase("shouldPublishPortsList")) {
                    shouldPublishPortsList = true;
                }
            }
        }

        info = new MonModuleInfo();
        info.name = moduleName;
        info.ResTypes = ResTypes;
        reloadConf();
        return info;
    }

    private void reloadConf() {
        ArrayList newPortsList = new ArrayList();
        if (moduleConfFile == null)
            return;

        try {
            int lineCount = 0;// just for debugging...
            BufferedReader br = new BufferedReader(new FileReader(moduleConfFile));
            for (String line = br.readLine(); line != null; line = br.readLine()) {
                if (line.trim().startsWith("#"))
                    continue; // ignore possible comments
                lineCount++;
                try {
                    String[] linePorts = line.split("(\\s)*,(\\s)*");
                    if (linePorts == null || linePorts.length == 0)
                        continue;
                    for (int i = 0; i < linePorts.length; i++) {
                        String port = linePorts[i].trim();
                        if (port.length() > 0) {
                            newPortsList.add(port);
                        }
                    }// end for - linePorts
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "Got exception parsing line " + lineCount + ": " + line, t);
                }
            }// end for - line
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Got exception parsing the conf file", t);
        }

        synchronized (portsSync) {
            if (newPortsList != null && !firstTime) {
                try {
                    publisher.publish("OS_PortMap", newPortsList);
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "Could not export ports list");
                }
            }
            portsList = newPortsList;
        }
    }

    private Vector getCrossConnects() {
        switch (switchType) {
            case (OSTelnet.CALIENT): {
                return getCrossConnectsCalient();
            }
            case (OSTelnet.GLIMMERGLASS): {
                return getCrossConnectsGlimmer();
            }
        }
        return null;
    }

    private String getRealGLPort(String port) {
        if (port == null)
            return null;
        if (port.indexOf("100") != -1) {
            return port.substring(3);
        }
        if (port.indexOf("200") != -1) {
            return port.substring(3);
        }
        return null;
    }

    private void fillPortsListGlimmer() {
        ArrayList newPortList = new ArrayList();

        try {
            // TODO - do it faster
            String[] lines = osConn.execCmdAndGet(monOSPortsPower.GLIMMER_TL1_CMD_PORT_POWER, OSTelnet.PPOWER_CTAG);
            String line;
            for (int linIt = 0; linIt < lines.length; linIt++) {
                line = lines[linIt];
                try {
                    String trimmedLine = line.trim();
                    if (logger.isLoggable(Level.FINEST)) {
                        logger.log(Level.FINEST, " Parsing (trimmed)line " + trimmedLine);
                    }
                    if (trimmedLine.indexOf("PORTPOWER=") != -1) {
                        String[] tk = trimmedLine.substring(1, trimmedLine.length() - 1).split("PORTPOWER="); // also
                                                                                                              // remove
                                                                                                              // "" from
                                                                                                              // the
                                                                                                              // beginning
                                                                                                              // and the
                                                                                                              // end of
                                                                                                              // line
                        String strPort = tk[0].split("PORTID=")[1].split(",")[0].substring(3);
                        newPortList.add(strPort);
                        if (logger.isLoggable(Level.FINEST)) {
                            logger.log(Level.FINEST, " Adding to newPortList [ " + strPort + " ]");
                        }
                    }
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "Got exception parsing line [" + line + "]", t);
                }
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING, " Got exception reading from command's buffered stream", t);
        }
        if (newPortList.size() > 0) {
            synchronized (portsSync) {
                portsList = newPortList;
            }
        }

        if (portsList == null) {
            logger.log(Level.INFO, " Set initial failed");
        } else {
            logger.log(Level.INFO, " Set initial PortList.size(" + portsList.size() + ") := " + portsList);
        }
    }

    private Vector getCrossConnectsGlimmer() {
        if (portsList == null) {
            fillPortsListGlimmer();
            if (portsList == null)
                return null;
        }
        Vector retv = new Vector();
        HashMap cLinks = new HashMap();

        try {

            // <rtrv-crs-fiber::all:1;
            // rtrv-crs-fiber::all:1;
            //
            // o01gva.datatag.org 05-01-31 17:44:22
            // M 1 COMPLD
            // "GGN:IPORTID=10001,IPORTNAME=,OPORTID=0,OPORTNAME=,CONNID=0,CONNSTATE=single,CONNCAUSE=none,INPWR=-49.162,OUTPWR=0.000,PWRLOSS=0.000,CONNLOCK=0,CONNLOCKUSER="
            // "GGN:IPORTID=10002,IPORTNAME=,OPORTID=20015,OPORTNAME=,CONNID=0,CONNSTATE=fault,CONNCAUSE=initial_connection_timer_expired,INPWR=-48.549,OUTPWR=-49.354,PWRLOSS=0.836,CONNLOCK=0,CONNLOCKUSER="
            // "GGN:IPORTID=10003,IPORTNAME=,OPORTID=0,OPORTNAME=,CONNID=0,CONNSTATE=single,CONNCAUSE=none,INPWR=-49.094,OUTPWR=0.000,PWRLOSS=0.000,CONNLOCK=0,CONNLOCKUSER="

            // TODO - Do it faster
            String[] lines = osConn.execCmdAndGet("rtrv-crs-fiber::all:" + OSTelnet.CCONN_CTAG + OSTelnet.TL1_FINISH_CMD, OSTelnet.CCONN_CTAG);
            String line;
            for (int linIt = 0; linIt < lines.length; linIt++) {
                line = lines[linIt].trim();
                if (!line.startsWith("\"") || !line.endsWith("\"") || line.length() < 6)
                    continue;
                String cline = line.substring(5, line.length() - 1);
                if (cline == null)
                    continue;
                cline = cline.trim();
                if (cline.length() > 0) {
                    // stripped should be something like the line below:
                    // stripped=
                    // IPORTID=10002,IPORTNAME=,OPORTID=20015,OPORTNAME=,CONNID=0,CONNSTATE=fault,CONNCAUSE=initial_connection_timer_expired,INPWR=-48.549,OUTPWR=-49.354,PWRLOSS=0.836,CONNLOCK=0,CONNLOCKUSER=
                    //
                    // or for the new glimmer at SC08
                    //
                    //"GGN:IPORTID=10040,IPORTNAME=,IPORTCOMMENT=,OPORTID=20041,OPORTNAME=,OPORTCOMMENT=,INPWR=-49.796,OUTPWR=-49.883,PWRLOSS=NA,CONNID=0,CONNNAME=,CONNSTATE=fault,CONNCAUSE=input_loss_of_light,CONNLOCK=0,CONNLOCKUSER=admin"

                    String[] tmpSplit = COMMA_PATTERN.split(cline);

                    final int tSlen = tmpSplit.length;

                    String rsPort = null;
                    String rdPort = null;
                    boolean ccOk = false;

                    boolean bDestPortChecked = false;
                    
                    for (int i = 0; i < tSlen; i++) {

                        final String[] sVals = EQ_PATTERN.split(tmpSplit[i]);

                        if (sVals == null || sVals.length < 2)
                            continue;

                        if (rsPort == null && sVals[0].equals("IPORTID")) {
                            // assert rsPort == null
                            rsPort = getRealGLPort(sVals[1]);
                            if (rsPort == null) {
                                break;
                            }
                        }

                        if (rdPort == null && sVals[0].equals("OPORTID")) {
                            bDestPortChecked =  true;
                            rdPort = getRealGLPort(sVals[1]);
                            if (rdPort == null) {
                                break;
                            }
                        }

                        if (sVals[0].equals("CONNSTATE")) {
                            if (sVals[1].equals("steady")) {
                                ccOk = true;
                            }

                            break;
                        }
                    }

                    if (bDestPortChecked && rsPort == null && rdPort == null) {
                        logger.log(Level.WARNING, " Got null ports for line " + cline);
                        continue;
                    }

                    if (rsPort == null || rdPort == null) {
                        continue;
                    }

                    if (!portsList.contains(rsPort) || !portsList.contains(rdPort)) {
                        continue;
                    }

                    String key = rsPort + " - " + rdPort;
                    Result rez = new Result();
                    rez.FarmName = Node.getFarmName();
                    rez.ClusterName = Node.getClusterName();
                    rez.Module = moduleName;
                    rez.NodeName = key;
                    rez.time = NTPDate.currentTimeMillis();

                    if (ccOk) {
                        rez.addSet("Status", OpticalCrossConnectLink.OK);
                        cLinks.put(key, Integer.valueOf(OpticalCrossConnectLink.OK));
                    } else {
                        rez.addSet("Status", OpticalCrossConnectLink.ERROR);
                        cLinks.put(key, Integer.valueOf(OpticalCrossConnectLink.ERROR));
                    }
                    retv.add(rez);
                }
            }

        } catch (Throwable t) {
            logger.log(Level.WARNING, "monOSCrossConns: Got Exception while parsing ...", t);
        }

        ArrayList al = computeDiff(cLinks);

        if (al != null) {
            for (Enumeration en = retv.elements(); en.hasMoreElements();) {
                Object pr = en.nextElement();
                if (pr instanceof Result) {
                    Result r = (Result) pr;
                    for (int i = 0; i < al.size(); i++) {
                        Object orr = al.get(i);
                        if (orr instanceof Result) {
                            Result rr = (Result) orr;
                            if (rr.NodeName.equals(r.NodeName)) {
                                retv.remove(pr);
                            }
                        }
                    }
                }
            }

            retv.addAll(al);
        }

        lastConns = cLinks;

        return retv;
    }

    private Vector getCrossConnectsCalient() {
        Vector retv = new Vector();
        HashMap cLinks = new HashMap();

        try {

            String[] lines = osConn.execCmdAndGet("rtrv-crs::,:" + OSTelnet.CCONN_CTAG + "::,,;", OSTelnet.CCONN_CTAG);
            String line;
            for (int linIt = 0; linIt < lines.length; linIt++) {
                line = lines[linIt].trim();
                if (!line.startsWith("\"") || !line.endsWith("\""))
                    continue;
                String cline = line.substring(1, line.length() - 1);
                if (cline == null)
                    continue;
                cline = cline.trim();
                int len1 = cline.length();
                if (len1 > 0) {
                    // stripped should be something like the line below:
                    // stripped=
                    // "10.13a.1-10.13a.2:SRCPORT=10.13a.1,DSTPORT=10.13a.2,GRPNAME=TESTGROUP,CONNNAME=13A.1_TO_13A.2,CONNTYPE=2WAY,AS=IS,OS=IS,OC=OK,PS=UPR,AL=CL,MATRIXUSED=31.1"
                    String stripped = cline;
                    String[] secondSplitS = stripped.trim().split(":");
                    if (secondSplitS == null || secondSplitS.length < 2) {
                        continue;
                    }
                    // String connID = secondSplitS[0];

                    // tmpSplit should look like smth like the lines below:
                    // String[] tmpSplit= String[11]
                    // String [0]= "SRCPORT=10.13a.1"
                    // String [1]= "DSTPORT=10.13a.2"
                    // String [2]= "GRPNAME=TESTGROUP"
                    // String [3]= "CONNNAME=13A.1_TO_13A.2"
                    // String [4]= "CONNTYPE=2WAY"
                    // String [5]= "AS=IS"
                    // String [6]= "OS=IS"
                    // String [7]= "OC=OK"
                    // String [8]= "PS=UPR"
                    // String [9]= "AL=CL"
                    // String [10]= "MATRIXUSED=31.1"
                    String[] tmpSplit = secondSplitS[1].split(",");
                    if (tmpSplit.length != 11)
                        continue;

                    String srcPortS = tmpSplit[0].split("=")[1];
                    String dstPortS = tmpSplit[1].split("=")[1];
                    String key = null;
                    key = srcPortS + " - " + dstPortS;
                    String revKey = dstPortS + " - " + srcPortS;

                    if (portsList != null && portsList.contains(srcPortS) && portsList.contains(dstPortS)) {
                        Result rez = new Result();
                        rez.FarmName = Node.getFarmName();
                        rez.ClusterName = Node.getClusterName();
                        rez.Module = moduleName;

                        rez.NodeName = key;

                        rez.time = NTPDate.currentTimeMillis();
                        if (tmpSplit[7].indexOf("OK") != -1) {
                            rez.addSet("Status", OpticalCrossConnectLink.OK);
                            cLinks.put(key, Integer.valueOf(OpticalCrossConnectLink.OK));
                        } else {
                            rez.addSet("Status", OpticalCrossConnectLink.ERROR);
                            cLinks.put(key, Integer.valueOf(OpticalCrossConnectLink.ERROR));
                        }
                        retv.add(rez);

                        if (tmpSplit[4] != null && tmpSplit[4].indexOf("2WAY") != -1) {
                            rez = new Result();
                            rez.FarmName = Node.getFarmName();
                            rez.ClusterName = Node.getClusterName();
                            rez.Module = moduleName;

                            rez.NodeName = revKey;

                            rez.time = NTPDate.currentTimeMillis();
                            if (tmpSplit[7].indexOf("OK") != -1) {
                                rez.addSet("Status", OpticalCrossConnectLink.OK);
                                cLinks.put(revKey, Integer.valueOf(OpticalCrossConnectLink.OK));
                            } else {
                                rez.addSet("Status", OpticalCrossConnectLink.ERROR);
                                cLinks.put(revKey, Integer.valueOf(OpticalCrossConnectLink.ERROR));
                            }
                            retv.add(rez);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING, "monOSCrossConns: Got Exception while parsing ...", t);
        }

        ArrayList al = computeDiff(cLinks);

        if (al != null) {
            for (Enumeration en = retv.elements(); en.hasMoreElements();) {
                Object pr = en.nextElement();
                if (pr instanceof Result) {
                    Result r = (Result) pr;
                    for (int i = 0; i < al.size(); i++) {
                        Object orr = al.get(i);
                        if (orr instanceof Result) {
                            Result rr = (Result) orr;
                            if (rr.NodeName.equals(r.NodeName)) {
                                retv.remove(pr);
                            }
                        }
                    }
                }
            }
            retv.addAll(al);
        }

        lastConns = cLinks;
        if (logger.isLoggable(Level.FINER)) {
            logger.log(Level.FINER, "monOSCrossConns returning " + retv.size());
        }
        return retv;
    }

    private ArrayList computeDiff(HashMap cLinks) {
        if (lastConns == null)
            return null;
        ArrayList retv = new ArrayList();
        for (Iterator it = lastConns.keySet().iterator(); it.hasNext();) {
            String oldKey = (String) it.next();
            if (!cLinks.containsKey(oldKey)) {
                logger.log(Level.INFO, "\n\nmonOSCC removing + [ " + oldKey + " ]");
                cLinks.remove(oldKey);
                Result r = new Result();
                r.FarmName = Node.getFarmName();
                r.ClusterName = Node.getClusterName();
                r.Module = moduleName;
                r.NodeName = oldKey;
                r.time = NTPDate.currentTimeMillis();
                r.addSet("Status", OpticalCrossConnectLink.REMOVED);
                retv.add(r);

                eResult rez = new eResult();
                rez.FarmName = Node.getFarmName();
                rez.ClusterName = Node.getClusterName();
                rez.Module = moduleName;
                rez.NodeName = oldKey;
                rez.time = NTPDate.currentTimeMillis();
                rez.param = null;
                rez.param_name = null;
                retv.add(rez);
            }
        }
        return retv;
    }

    public Object doProcess() throws Exception {
        long sTime = System.currentTimeMillis();
        Vector v = null;
        try {
            if (firstTime) {
                firstTime = false;
                if (portsList != null) {
                    try {
                        publisher.publish("OS_PortMap", portsList);
                    } catch (Throwable t) {
                        logger.log(Level.WARNING, "Could not export ports list");
                    }
                }
            }

            if (osConn == null) {
                osConn = OSTelnetFactory.getMonitorInstance(switchType);
            }

            if (osConn != null) {
                v = getCrossConnects();
            }

            // if(portsSendCounter++ % 10 == 0) {
            // portsSendCounter = 0;
            // if(v == null) {
            // v = new Vector();
            // }
            // //THIS IS A HACK!!!! - GMLE is not republished from proxy!!
            // //The problem is with Calient ( high load ... ) so no real way to get the power on all ports
            // if(shouldPublishPortsList) {
            // synchronized(portsSync) {
            // if(portsList != null && portsList.size() > 0) {
            // eResult er = new eResult();
            // er.FarmName = Node.getFarmName();
            // er.ClusterName = "OS_Ports";
            // er.Module = "monOSPortsPower";
            // er.NodeName = (String)portsList.get(0) + "_In";
            // er.addSet("Port-Power", portsList);
            // v.add(er);
            // if(logger.isLoggable(Level.FINER)) {
            // logger.log(Level.FINER, " Sending eResult with port list" + er.toString());
            // }
            // }
            // }//sync
            // }//if
            // }

            if (logger.isLoggable(Level.FINEST)) {
                if (v == null) {
                    logger.log(Level.FINEST, " monOSPortPower returning null Vector");
                } else {
                    logger.log(Level.FINEST, " monOSPortPower returning\n" + v.toString() + "\n");
                }
            }
            return v;
        } finally {
            if (logger.isLoggable(Level.FINE)) {
                StringBuilder sb = new StringBuilder(8192);
                sb.append(" [ monOSCrossConns ] dt= [ ").append(System.currentTimeMillis() - sTime).append(" ] ms \n");
                if (logger.isLoggable(Level.FINEST)) {
                    sb.append(" returning ").append(v).append("\n");
                }
                logger.log(Level.FINE, sb.toString());
            }
        }
    }

    public MonModuleInfo getInfo() {
        return info;
    }

    public String[] ResTypes() {
        return ResTypes;
    }

    public String getOsName() {
        return "linux";
    }

    public MNode getNode() {
        return Node;
    }

    public String getClusterName() {
        return Node.getClusterName();
    }

    public String getFarmName() {
        return Node.getFarmName();
    }

    public String getTaskName() {
        return moduleName;
    }

    public boolean isRepetitive() {
        return isRepetitive;
    }

    public boolean stop() {
        logger.log(Level.INFO, " monSys300CMap stop() Request . SHOULD NOT!!!");
        return true;
    }

    static public void main(String[] args) {

        monOSCrossConns aa = new monOSCrossConns();
        String ad = null;
        String host = null;
        try {
            host = (InetAddress.getLocalHost()).getHostName();
            ad = InetAddress.getByName(host).getHostAddress();
        } catch (Exception e) {
            System.out.println(" Can not get ip for node " + e);
            System.exit(-1);
        }
        System.out.println("Using hostname= " + host + " IPaddress=" + ad);
        aa.init(new MNode(host, ad, new MCluster("CMap", null), null), "SwitchType=Glimmerglass");

        try {
            for (int k = 0; k < 10000; k++) {
                Vector bb = (Vector) aa.doProcess();
                for (int q = 0; q < bb.size(); q++)
                    System.out.println(bb.get(q));
                System.out.println("-------- sleeeping ----------");
                Thread.sleep(5000);
                System.out.println("-------- doProcess-ing --------- k=" + k);
            }
        } catch (Exception e) {
            System.out.println(" failed to process !!!");
        }
    }

    public void update(Observable o, Object arg) {
        if (o != null && o.equals(dfw)) {// just extra check
            reloadConf();
        }
    }

}
