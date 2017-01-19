package lia.Monitor.JiniClient.CommonGUI.Groups.Plot;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TimerTask;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.WindowConstants;

import lia.Monitor.GUIs.Unit;
import lia.Monitor.JiniClient.CommonGUI.BackgroundWorker;
import lia.Monitor.JiniClient.CommonGUI.StringResultPanel;
import lia.Monitor.JiniClient.CommonGUI.TimeUtil;
import lia.Monitor.JiniClient.CommonGUI.rcNode;
import lia.Monitor.monitor.LocalDataFarmClient;
import lia.Monitor.monitor.LocalDataFarmProvider;
import lia.Monitor.monitor.Result;
import lia.Monitor.monitor.eResult;
import lia.Monitor.monitor.monPredicate;
import lia.Monitor.tcpClient.MLSerClient;
import lia.util.ntp.NTPDate;
import plot.BarChartOpt;

public class MultipleBarMonDataPlotClus implements LocalDataFarmClient, MultipleDataPlotter, ComponentListener,
        WindowListener, ActionListener {

    /** Logger used by this class */
    private static final Logger logger = Logger.getLogger(MultipleBarMonDataPlotClus.class.getName());

    DataPlotterParent parent;
    Vector dataproviders;
    rcNode[] selectedNodes = null;
    BarChartOpt bco;
    String title;
    boolean receivedNewData;
    boolean queryHasResults;
    boolean addClusterName;
    TimerTask ttask;
    Thread tthread;
    boolean closed = false;
    Vector predicates;
    String[] parameters;
    JMenuItem mPeriod;
    JMenuItem stringResultsItems;
    long timeOfLastResult;
    long timeOfFirstResult;
    boolean continuous = true;
    String timeZone;
    String localTime;
    int resultsCount = 0;
    int results = 0;
    TimeUtil timeUtil = new TimeUtil();

    static final Object lock = new Object();

    // cipsm - this frame is used to represent the string values obtained as result
    StringResultPanel stringResultPanel = null;
    JFrame stringResultFrame = null;
    Component plotPanel = null;
    boolean plotResult = false;
    JMenuBar menubar = null;
    boolean multipleFarms = false;

    Vector notProcessed = new Vector();

    private long startTimer = 0l;
    private boolean startTimerFirst = true;

    HashMap currentUnit = null;
    Unit baseUnit = null;

    public MultipleBarMonDataPlotClus(DataPlotterParent parent, rcNode[] selectedNodes, Vector predicates,
            Vector clusters, HashMap yAxisUnit) {

        this.parent = parent;
        this.predicates = predicates;
        this.selectedNodes = selectedNodes;

        title = (String) clusters.get(0);
        for (int i = 1; i < clusters.size(); i++) {
            title += ", " + (String) clusters.get(i);
        }
        addClusterName = clusters.size() > 1;
        parameters = ((monPredicate) predicates.get(0)).parameters;

        if ((parameters == null) || (parameters.length == 0)) {
            return;
        }

        if ((yAxisUnit != null) && (yAxisUnit.size() != 0)) {
            baseUnit = (Unit) yAxisUnit.get(parameters[0]);
            currentUnit = yAxisUnit;
        }

        bco = new BarChartOpt("RealTime profile", title, false, false, "", (baseUnit != null) ? baseUnit.toString()
                : null);
        bco.setVisible(true);
        bco.setTimeoutTime(60 * 1000);
        bco.changeDepth(3, 4);
        JMenu menu = bco.getViewMenu();
        mPeriod = new JMenuItem("Plot interval");
        mPeriod.addActionListener(this);
        menu.addSeparator();
        menu.add(mPeriod);
        timeOfFirstResult = Long.MAX_VALUE;

        ttask = new TimerTask() {
            @Override
            public void run() {
                Thread.currentThread().setName(" ( ML ) - GroupsPlot - MultipleBarMonDataPlotClus Timer Thread");
                update();
            }
        };
        queryHasResults = false;
        dataproviders = new Vector();
        //  register as a listener for these predicates
        for (Iterator pit = predicates.iterator(); pit.hasNext();) {
            monPredicate pred = (monPredicate) pit.next();
            sendPredicate(pred);
        }
        multipleFarms = (dataproviders.size() > 1);
        startTimer = NTPDate.currentTimeMillis();
        startTimerFirst = true;
        tthread = BackgroundWorker.controlledSchedule(ttask, 500, 500);
        bco.addComponentListener(this);
        bco.addWindowListener(this);
        bco.startProgressBar(true);
    }

    public void update() {

        synchronized (lock) {
            try {
                if (receivedNewData) {
                    receivedNewData = false;
                    bco.setLowerMargin(0.5 / (1.0 + bco.nodes.size()));
                    bco.setUpperMargin(0.5 / (1.0 + bco.nodes.size()));
                    bco.setCurrentTime(timeOfLastResult);
                    bco.stopProgressBar();
                    bco.rupdate();
                }
            } catch (Throwable t) {
                logger.log(Level.WARNING, "Error executing", t);
            }
        }
    }

    protected void addDataProvider(LocalDataFarmProvider provider) {

        boolean found = false;
        for (Iterator it = dataproviders.iterator(); it.hasNext();) {
            LocalDataFarmProvider p = (LocalDataFarmProvider) it.next();
            if (p.equals(provider)) {
                found = true;
                break;
            }
        }
        if (!found) {
            dataproviders.add(provider);
        }
    }

    protected void sendPredicate(monPredicate predicate) {

        if (selectedNodes != null) {
            for (rcNode selectedNode : selectedNodes) {
                if (selectedNode.client.farm.toString().equals(predicate.Farm)) {
                    selectedNode.client.addLocalClient(this, predicate);
                    addDataProvider(selectedNode.client);
                    resultsCount++;
                    break;
                }
            }
        }
    }

    public boolean testAlive() {

        if (bco == null) {
            return false;
        }
        if (bco.isVisible()) {
            return true;
        }
        return false;
    }

    private void formStringResultFrame() {

        stringResultFrame = new JFrame("Realtime string results");
        stringResultFrame.getContentPane().setLayout(new BorderLayout());
        stringResultFrame.getContentPane().add(stringResultPanel, BorderLayout.CENTER);
        stringResultFrame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        stringResultFrame.setSize(new Dimension(500, 500));
        stringResultsItems = new JMenuItem("Show string results");
        JMenu menu = bco.getViewMenu();
        stringResultsItems.addActionListener(this);
        menu.addSeparator();
        menu.add(stringResultsItems);
        JOptionPane.showMessageDialog(bco,
                "There is string data available also... please see View->Show string results menu item.");
    }

    @Override
    public void newFarmResult(MLSerClient client, Object ro) {
        if (!testAlive()) {
            if (!closed) {
                notProcessed.add(new Object[] { client, ro });
            }
            return;
        }

        while (notProcessed.size() != 0) {
            Object[] o = (Object[]) notProcessed.remove(0);
            newFarmResult((MLSerClient) o[0], o[1]);
        }

        resultsCount--;
        if ((ro == null) && (resultsCount <= 0)) {
            if (!queryHasResults) {
                bco.stopProgressBar();
                queryHasResults = true;
                JOptionPane.showMessageDialog(bco, "There is no data available for your request!");
            }
            return;
        }
        if (ro == null) {
            if (logger.isLoggable(Level.FINEST)) {
                logger.log(Level.FINE, "Got a null result");
            }
            return; // some of the farms don't have this result...
        }
        queryHasResults = true;
        if (ro instanceof Result) {
            Result r = (Result) ro;
            boolean ret = plotResult(r);
            if ((stringResultPanel != null) && (plotPanel != null)) { // already added the string results
                bco.getContentPane().removeAll();
                bco.getContentPane().setLayout(new BorderLayout());
                bco.getContentPane().add(plotPanel, BorderLayout.CENTER);
                bco.setJMenuBar(menubar);
                bco.validate();
                bco.repaint();
                plotPanel = null;
                formStringResultFrame();
            }
            plotResult = true;
            if (ret && (results < 300)) {
                synchronized (lock) {
                    results++;
                    if ((results % 30) == 0) {
                        update();
                    }
                }
            }
        } else if (ro instanceof eResult) {
            eResult r = (eResult) ro;
            boolean ret = false;
            if ((r.param != null) && (r.param_name != null)) {
                for (int k = 0; (k < r.param.length) && (k < r.param_name.length); k++) {
                    if (r.param[k] instanceof String) {
                        if (stringResultPanel == null) {
                            stringResultPanel = new StringResultPanel();
                            if (!plotResult) { // directly into the bco
                                plotPanel = bco.getContentPane().getComponent(0);
                                stringResultPanel.setPreferredSize(plotPanel.getSize());
                                menubar = bco.getJMenuBar();
                                bco.setJMenuBar(null);
                                bco.getContentPane().removeAll();
                                bco.getContentPane().setLayout(new BorderLayout());
                                bco.getContentPane().add(stringResultPanel, BorderLayout.CENTER);
                                bco.validate();
                                bco.repaint();
                            } else {
                                formStringResultFrame();
                            }
                        }
                        timeUtil.setTimeZone(timeZone);
                        stringResultPanel.addStringResult(timeUtil.getTime(r.time), r.FarmName, r.ClusterName,
                                r.NodeName, r.Module, r.param_name[k], (String) r.param[k], true);
                        receivedNewData = true;
                        ret = true;
                        if (startTimerFirst) {
                            long now = NTPDate.currentTimeMillis();
                            if ((now - startTimer) > 10000) {
                                BackgroundWorker.cancel(tthread);
                                tthread = BackgroundWorker.controlledSchedule(ttask, 4000, 4000);
                                startTimerFirst = false;
                            }
                        }
                    } else {
                        System.out.println("Got non string " + r.param[k].toString());
                    }
                }
            }
            if (ret && (results < 5000)) {
                synchronized (lock) {
                    results++;
                    if ((results % 30) == 0) {
                        update();
                    }
                }
            }
        } else if (ro instanceof Vector) {
            Vector vr = (Vector) ro;
            for (int i = 0; i < vr.size(); i++) {
                newFarmResult(client, vr.elementAt(i));
            }
        } else {
            logger.log(Level.WARNING, "Wrong Result type in MonPlot ! ");
            return;
        }
    }

    /** called from RCMonPanel to set the local time */
    @Override
    public void setLocalTime(String dd) {
        if (dd != null) {
            try {
                localTime = dd.substring(1, 6);
                dd = dd.substring(1 + dd.indexOf("("), dd.indexOf(")"));
                timeZone = MultipleMonDataPlot.adjustTimezone(dd);
                bco.setTimeZone(timeZone);
                timeUtil.setTimeZone(timeZone);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Invalid local time");
            }
        }
    }

    /** called to add farm name on the title bar */
    @Override
    public void setFarmName(String farmName) {
        bco.setTitle(farmName + ": " + title);
    }

    /** called to add the country flag on the title bar */
    @Override
    public void setCountryCode(String cc) {
        bco.setCountryCode(cc);
    }

    @Override
    public boolean stopIt(rcNode node) {
        if (!closed) {
            if ((dataproviders.size() <= 1) || (node == null)) {
                if (bco != null) {
                    bco.removeComponentListener(this);
                    bco.removeWindowListener(this);
                    bco.dispose();
                }
                if (stringResultsItems != null) {
                    stringResultsItems.removeActionListener(this);
                }
                if (stringResultFrame != null) {
                    stringResultFrame.dispose();
                }
                if ((dataproviders != null) && (dataproviders.size() != 0)) {
                    for (int i = 0; i < dataproviders.size(); i++) {
                        LocalDataFarmProvider prov = (LocalDataFarmProvider) dataproviders.get(i);
                        prov.deleteLocalClient(this);
                    }
                }
                if (tthread != null) {
                    BackgroundWorker.cancel(tthread);
                    tthread = null;
                }
                closed = true;
                return true;
            }
            dataproviders.remove(node.client);
            node.client.deleteLocalClient(this);
            if (stringResultPanel != null) {
                stringResultPanel.removeFarm(node);
            }
            try {
                bco.removeNode(node.client.farm.getName() + "/");
            } catch (Exception e) {
                logger.warning(e.getLocalizedMessage());
            }
            return false;
        }
        return true;
    }

    boolean plotResult(Result r) {

        if ((bco == null) || (r.param_name == null) || (r.param == null)) {
            return false;
        }

        boolean ret = false;
        try {
            timeOfLastResult = Math.max(timeOfLastResult, r.time);
            timeOfFirstResult = Math.min(timeOfFirstResult, r.time);
            String nn = null;

            if (multipleFarms) {
                nn = addClusterName ? r.FarmName + "/" + r.ClusterName + "/" + r.NodeName : r.FarmName + "/"
                        + r.NodeName;
            } else {
                nn = addClusterName ? r.ClusterName + "/" + r.NodeName : r.NodeName;
            }

            for (String parameter : parameters) {
                for (int j = 0; j < r.param.length; j++) {
                    if (parameter.equals(r.param_name[j])) {
                        if (!Double.isNaN(r.param[j]) && !Double.isInfinite(r.param[j])) {
                            if ((currentUnit != null) && (baseUnit != null) && currentUnit.containsKey(r.param_name[j])) {
                                Unit u = (Unit) currentUnit.get(r.param_name[j]);
                                bco.addPoint(nn, r.param_name[j], convert(u, r.param[j]));
                            } else {
                                bco.addPoint(nn, r.param_name[j], r.param[j]);
                            }
                            receivedNewData = true;
                            ret = true;
                            if (startTimerFirst) {
                                long now = NTPDate.currentTimeMillis();
                                if ((now - startTimer) > 10000) {
                                    BackgroundWorker.cancel(tthread);
                                    tthread = BackgroundWorker.controlledSchedule(ttask, 4000, 4000);
                                    startTimerFirst = false;
                                }
                            }
                        } else {
                            logger.warning("Got a NaN result");
                        }
                    }
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
            if (logger.isLoggable(Level.FINEST)) {
                logger.log(Level.FINE, t.getLocalizedMessage());
            }
        }
        return ret;
    }

    private double convert(Unit u, double val) {
        if (u == null) {
            return val;
        }
        long diffTimeMultiplicator = 1l;
        if ((baseUnit.lTimeMultiplier != 0l) && (u.lTimeMultiplier != 0l)) {
            diffTimeMultiplicator = baseUnit.lTimeMultiplier / u.lTimeMultiplier;
        }
        if (diffTimeMultiplicator == 0l) {
            diffTimeMultiplicator = 1l;
        }
        long diffUnitMultiplicator = 1l;
        if ((baseUnit.lUnitMultiplier != 0l) && (u.lUnitMultiplier != 0l)) {
            diffUnitMultiplicator = baseUnit.lUnitMultiplier / u.lUnitMultiplier;
        }
        if (diffUnitMultiplicator == 0l) {
            diffUnitMultiplicator = 1l;
        }
        val = (val * diffTimeMultiplicator) / diffUnitMultiplicator;
        return val;
    }

    @Override
    public void componentHidden(ComponentEvent e) {
    }

    @Override
    public void componentMoved(ComponentEvent e) {
    }

    @Override
    public void componentResized(ComponentEvent e) {

        try {
            Dimension d = bco.getSize();
            bco.setDimension(d);
        } catch (Exception ex) {
        }
    }

    @Override
    public void componentShown(ComponentEvent e) {
    }

    @Override
    public void windowActivated(WindowEvent e) {
    }

    @Override
    public void windowClosed(WindowEvent e) {

        if (bco == null) {
            return;
        }
        if (!closed) {
            for (Iterator it = dataproviders.iterator(); it.hasNext();) {
                LocalDataFarmProvider provider = (LocalDataFarmProvider) it.next();
                provider.deleteLocalClient(this);
            }
            parent.stopPlot(this);
        }
    }

    @Override
    public void windowClosing(WindowEvent e) {
    }

    @Override
    public void windowDeactivated(WindowEvent e) {
    }

    @Override
    public void windowDeiconified(WindowEvent e) {
    }

    @Override
    public void windowIconified(WindowEvent e) {
    }

    @Override
    public void windowOpened(WindowEvent e) {
    }

    @Override
    public void actionPerformed(ActionEvent e) {

        if (e.getSource() == stringResultsItems) {
            if (!stringResultFrame.isShowing()) {
                stringResultFrame.setVisible(true);
            } else {
                stringResultFrame.toFront();
            }
            return;
        }

        if (e.getSource() == mPeriod) {
            PlotIntervalSelector is = new PlotIntervalSelector(bco, timeOfFirstResult, (continuous ? -1
                    : timeOfLastResult), localTime, timeZone);
            is.setVisible(true);
            if (!is.closedOK()) {
                is.dispose();
                is = null;
                return;
            }
            long start = is.getStartTime();
            long end = is.getEndTime();
            long length = is.getIntervalLength();
            is.dispose();
            is = null;
            continuous = (end == -1);
            try {
                for (Iterator it = dataproviders.iterator(); it.hasNext();) {
                    LocalDataFarmProvider provider = (LocalDataFarmProvider) it.next();
                    provider.deleteLocalClient(this);
                }
                bco.clear();
                bco.setCurrentTime(-1);
                bco.stopProgressBar();
                bco.startProgressBar(true);
                bco.rupdate();
                timeOfFirstResult = Long.MAX_VALUE;
                timeOfLastResult = Long.MIN_VALUE;
                if (continuous) {
                    start = -length;
                    //            	System.out.println("start = "+start+" end="+end);
                } else {
                    //            	System.out.println("start ="+new Date(start)+" end="+new
                    // Date(end));
                }
                queryHasResults = false;
                dataproviders = new Vector();
                // register as a listener for these predicates
                for (Iterator pit = predicates.iterator(); pit.hasNext();) {
                    monPredicate pred = (monPredicate) pit.next();
                    pred.tmin = start;
                    pred.tmax = end;
                    sendPredicate(pred);
                }
                startTimer = NTPDate.currentTimeMillis();
                startTimerFirst = true;
                BackgroundWorker.cancel(tthread);
                tthread = BackgroundWorker.controlledSchedule(ttask, 500, 500);
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Error requesting data");
            }
        }
    }
}
