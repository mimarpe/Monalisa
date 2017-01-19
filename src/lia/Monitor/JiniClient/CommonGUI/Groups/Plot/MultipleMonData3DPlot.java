package lia.Monitor.JiniClient.CommonGUI.Groups.Plot;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.util.Date;
import java.util.HashMap;
import java.util.TimeZone;
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
import lia.Monitor.Plot.IntervalSelector;
import lia.Monitor.Plot.TimeSliderAdjustmentListener;
import lia.Monitor.monitor.LocalDataFarmClient;
import lia.Monitor.monitor.LocalDataFarmProvider;
import lia.Monitor.monitor.Result;
import lia.Monitor.monitor.eResult;
import lia.Monitor.monitor.monPredicate;
import lia.Monitor.tcpClient.MLSerClient;
import lia.util.ntp.NTPDate;
import plot3d.XYLineChart;

public class MultipleMonData3DPlot implements LocalDataFarmClient, MultipleDataPlotter, ActionListener, WindowListener,
        TimeSliderAdjustmentListener {

    /** Logger used by this class */
    private static final Logger logger = Logger.getLogger(MultipleMonData3DPlot.class.getName());

    DataPlotterParent parent;
    LocalDataFarmProvider dataprovider;
    XYLineChart p;
    JMenuItem stringResultsItems;
    JMenuItem mPeriod; // menuitem shown when user
                       // right-clicks the plot
    JMenuItem mnPeriod;
    JMenuItem mTime;
    boolean localTimeZone = false;

    long timeOfLastResult = 1l;
    long timeOfFirstResult = 0l;
    boolean continuous = true;
    String timeZone = null;
    String localTime;
    monPredicate predicate;

    String title = "";
    boolean receivedNewData;
    boolean queryHasResult;
    int receivingData = 0;
    //	Object rdSync = new Object();
    TimerTask ttask;
    Thread tthread;
    boolean closed = false;
    int resultsCount = 0;
    int results = 0;
    TimeUtil timeUtil = new TimeUtil();

    final Object lock = new Object();
    Vector notProcessed = new Vector();

    // cipsm - this frame is used to represent the string values obtained as result
    StringResultPanel stringResultPanel = null;
    JFrame stringResultFrame = null;
    Component plotPanel = null;
    boolean plotResult = false;
    JMenuBar menubar = null;
    boolean multipleFarms = false;

    IntervalSelector timeSelector;

    HashMap currentUnit = null;
    Unit baseUnit = null;

    public MultipleMonData3DPlot(DataPlotterParent parent, rcNode[] selectedNodes, Vector predicates) {

        this.parent = parent;

        // since we're in a History Node Plot, we should have a single
        // predicate
        predicate = (monPredicate) predicates.get(0);
        if ((predicate.parameters == null) || (predicate.parameters.length == 0)) {
            return;
        }
        title = predicate.Node;
        if (predicates.size() > 1) {
            logger.log(Level.WARNING, "More that one predicate in MonDataPlot!");
        }

        continuous = true;
        queryHasResult = false;

        p = new XYLineChart(title, "Service Local Time", " ", " ");
        p.setTitleLabel(title);
        p.setTimeZone(TimeZone.getDefault().getID());

        p.showMe(true);
        p.setTimeoutTime(60 * 1000);

        p.setDataInterval(-1 * predicate.tmin);

        JMenu menu = p.getViewMenu();

        mTime = new JMenuItem("Modify timezone");
        mTime.addActionListener(this);
        mTime.setEnabled(timeZone != null);
        menu.addSeparator();
        menu.add(mTime);

        mPeriod = new JMenuItem("Plot interval");
        mPeriod.addActionListener(this);
        menu.addSeparator();
        menu.add(mPeriod);

        mnPeriod = new JMenuItem("Show plot interval selector");
        mnPeriod.addActionListener(this);
        menu.add(mnPeriod);

        timeOfFirstResult = Long.MAX_VALUE;
        ttask = new TimerTask() {

            @Override
            public void run() {
                Thread.currentThread().setName(" ( ML ) - GroupsPlot - MultipleMonDataPlot Timer Thread");
                update();
            }
        };

        if ((selectedNodes == null) || (selectedNodes.length == 0)) {
            return;
        }
        dataprovider = selectedNodes[0].client;
        for (rcNode selectedNode : selectedNodes) {
            if (selectedNode.client.farm.toString().equals(predicate.Farm)) {
                dataprovider = selectedNode.client;
                break;
            }
        }

        dataprovider.addLocalClient(this, predicate);
        resultsCount++;

        tthread = BackgroundWorker.controlledSchedule(ttask, 4000, 4000);

        p.addWindowListener(this);
        p.startProgressBar(true);
    }

    public void setCurrentUnit(HashMap unit) {
        if ((unit != null) && (unit.size() != 0) && (p != null)) {
            baseUnit = (Unit) unit.get(predicate.parameters[0]);
            p.setZAxisLabel(baseUnit.toString());
            currentUnit = unit;
        }
    }

    public void update() {

        synchronized (lock) {
            try {
                if ((receivingData == 0) && receivedNewData) {
                    receivedNewData = false;
                    p.stopProgressBar();
                    p.updateSeries();
                }
            } catch (Throwable t) {
                logger.log(Level.WARNING, "Error executing", t);
            }
        }
    }

    public boolean testAlive() {

        if (p == null) {
            return false;
        }
        if (p.isVisible()) {
            return true;
        }
        return false;
    }

    private void formStringResultFrame() {
        stringResultFrame = new JFrame("History string results");
        stringResultFrame.getContentPane().setLayout(new BorderLayout());
        stringResultFrame.getContentPane().add(stringResultPanel, BorderLayout.CENTER);
        stringResultFrame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        stringResultFrame.setSize(new Dimension(500, 500));
        stringResultsItems = new JMenuItem("Show string results");
        JMenu menu = p.getViewMenu();
        stringResultsItems.addActionListener(this);
        menu.addSeparator();
        menu.add(stringResultsItems);
        JOptionPane.showMessageDialog(p,
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
            if (!queryHasResult) {
                queryHasResult = true;
                p.stopProgressBar();
                if (timeSelector != null) {
                    long current = NTPDate.currentTimeMillis();
                    timeSelector.setRange(current - (2 * 60 * 60 * 1000), current, current);
                }
                JOptionPane.showMessageDialog(p, "There is no data available for your request!\n"
                        + "Please use 'Plot interval' from 'View' menu\n" + "to select other interval.");
            }
            return;
        }
        if (ro == null) {
            if (logger.isLoggable(Level.FINEST)) {
                logger.log(Level.FINE, "Null result");
            }
            return; // some of the farms don't have this result...
        }
        queryHasResult = true;
        if (ro instanceof Result) {
            if ((stringResultPanel != null) && (plotPanel != null)) { // already added the string results
                p.getContentPane().removeAll();
                p.getContentPane().setLayout(new BorderLayout());
                p.getContentPane().add(plotPanel, BorderLayout.CENTER);
                p.setJMenuBar(menubar);
                p.validate();
                p.repaint();
                plotPanel = null;
                formStringResultFrame();
            }
            Result r = (Result) ro;
            synchronized (lock) {
                receivingData++;
            }
            boolean ret = plotResult(r);
            synchronized (lock) {
                receivingData--;
            }
            if (ret && (results < 300)) {
                synchronized (lock) {
                    results++;
                    if ((results % 30) == 0) {
                        update();
                    }
                }
            }
        } else if (ro instanceof eResult) {
            // 	System.out.println("Got eResult " + ro);
            eResult r = (eResult) ro;
            boolean ret = false;
            if ((r.param != null) && (r.param_name != null)) {
                for (int k = 0; (k < r.param.length) && (k < r.param_name.length); k++) {
                    if (r.param[k] instanceof String) {
                        if (stringResultPanel == null) {
                            stringResultPanel = new StringResultPanel();
                            if (!plotResult) { // directly into the p
                                plotPanel = p.getContentPane().getComponent(0);
                                stringResultPanel.setPreferredSize(plotPanel.getSize());
                                menubar = p.getJMenuBar();
                                p.setJMenuBar(null);
                                p.getContentPane().removeAll();
                                p.getContentPane().setLayout(new BorderLayout());
                                p.getContentPane().add(stringResultPanel, BorderLayout.CENTER);
                                p.validate();
                                p.repaint();
                            } else {
                                formStringResultFrame();
                            }
                        }
                        stringResultPanel.addStringResult(timeUtil.getTime(r.time), r.FarmName, r.ClusterName,
                                r.NodeName, r.Module, r.param_name[k], (String) r.param[k], false);
                        receivedNewData = true;
                        ret = true;
                    } else {
                        logger.warning("Got non string " + r.param[k].toString());
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
            if (vr.size() == 0) {
                return;
            }
            for (int i = 0; i < vr.size(); i++) {
                newFarmResult(client, vr.get(i));
            }
        } else {
            logger.log(Level.WARNING, " Wrong Result type in MonPlot ! " + ro + " >>>>> Class name: " + ro.getClass(),
                    new Object[] { ro });
            return;
        }
    }

    /** called from RCMonPanel to set the local time */
    @Override
    public void setLocalTime(String dd) {
        if (dd != null) {
            try {
                localTime = dd.substring(1, 6);
                // System.out.println("localTime1="+localTime);
                dd = dd.substring(1 + dd.indexOf("("), dd.indexOf(")"));
                timeZone = adjustTimezone(dd);
                mTime.setEnabled(true);
                timeUtil.setTimeZone(timeZone);
                if (!localTimeZone) {
                    p.setTimeZone(timeZone);
                    if (timeSelector != null) {
                        timeSelector.setTimeZone(timeZone);
                    }
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Invalid local time " + dd);
            }
        }
    }

    /** called to add farm name on the title bar */
    @Override
    public void setFarmName(String farmName) {
        p.setTitle(farmName + ": " + title);
    }

    /** called to add the country flag on the title bar */
    @Override
    public void setCountryCode(String cc) {
        p.setCountryCode(cc);
    }

    static String adjustTimezone(String tz) {
        if (tz.equals("CDT") || tz.equals("CST") || tz.equals("EDT") || tz.equals("EST")) {
            return tz + "N";
        }
        if (tz.equals("PKT")) {
            tz = "GMT+5";
        } else if (tz.equals("KST")) {
            tz = "GMT+9";
        }
        return tz;
    }

    @Override
    public boolean stopIt(rcNode node) {
        if (!closed) {
            p.removeWindowListener(this);
            mPeriod.removeActionListener(this);
            dataprovider.deleteLocalClient(this);
            p.dispose();
            if (tthread != null) {
                BackgroundWorker.cancel(tthread);
                tthread = null;
            }
            closed = true;
        }
        return true;
    }

    boolean plotResult(Result r) {

        if (p == null) {
            return false;
        }
        synchronized (p.getTreeLock()) {
            if ((r.param_name == null) || (r.param == null)) {
                return false;
            }
            boolean ret = false;
            if ((r.time < predicate.tmin) || ((predicate.tmax > 0) && (r.time > predicate.tmax))) {
                return false;
            }
            try {
                timeOfLastResult = Math.max(timeOfLastResult, r.time);
                timeOfFirstResult = Math.min(timeOfFirstResult, r.time);
                if (timeSelector != null) {
                    timeSelector.setRange(timeOfFirstResult, timeOfLastResult, NTPDate.currentTimeMillis());
                }
                for (String parameter : predicate.parameters) {
                    for (int j = 0; j < r.param.length; j++) {
                        if (parameter.equals(r.param_name[j])) {
                            if (!Double.isNaN(r.param[j]) && !Double.isInfinite(r.param[j])) {
                                if ((currentUnit != null) && (baseUnit != null) && currentUnit.containsKey(parameter)) {
                                    Unit u = (Unit) currentUnit.get(parameter);
                                    p.addPoint(parameter, r.time, convert(u, r.param[j]));
                                } else {
                                    p.addPoint(parameter, r.time, r.param[j]);
                                }
                                receivedNewData = true;
                                ret = true;
                            } else {
                                logger.warning("Got a NaN result");
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                if (logger.isLoggable(Level.FINEST)) {
                    logger.log(Level.FINE, t.getLocalizedMessage());
                }
            }
            return ret;
        }
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
    public void actionPerformed(ActionEvent e) {

        if (e.getSource().equals(mTime)) {
            if (localTimeZone) {
                if (timeZone != null) {
                    p.setTimeZone(timeZone);
                    if (timeSelector != null) {
                        timeSelector.setTimeZone(timeZone);
                    }
                    p.setXAxisLabel("Service Local Time");
                    localTimeZone = false;
                    p.repaint();
                }
            } else {
                p.setTimeZone(TimeZone.getDefault().getID());
                if (timeSelector != null) {
                    timeSelector.setTimeZone(TimeZone.getDefault().getID());
                }
                p.setXAxisLabel("Local Time");
                localTimeZone = true;
                p.repaint();
            }
            return;
        }

        if (e.getSource().equals(stringResultsItems)) {
            if (!stringResultFrame.isShowing()) {
                stringResultFrame.setVisible(true);
            } else {
                stringResultFrame.toFront();
            }
            return;
        }

        if (e.getSource().equals(mnPeriod)) {
            if (timeSelector == null) {
                timeSelector = new IntervalSelector(timeOfFirstResult, timeOfLastResult, NTPDate.currentTimeMillis());
                if (localTimeZone) {
                    timeSelector.setTimeZone(TimeZone.getDefault().getID());
                } else {
                    timeSelector.setTimeZone(timeZone);
                }
                timeSelector.addAdjustmentListener(this);
            } else {
                timeSelector.setRange(timeOfFirstResult, timeOfLastResult, NTPDate.currentTimeMillis());
            }
            if (mnPeriod.getText().equals("Show plot interval selector")) {
                p.getContentPane().add(timeSelector, BorderLayout.NORTH);
                p.pack();
                p.repaint();
                mnPeriod.setText("Hide plot interval selector");
            } else {
                p.getContentPane().remove(timeSelector);
                p.pack();
                p.repaint();
                mnPeriod.setText("Show plot interval selector");
            }
            return;
        }

        if (e.getSource().equals(mPeriod)) {
            PlotIntervalSelector is = new PlotIntervalSelector(p, timeOfFirstResult, (continuous ? -1
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
                if (dataprovider != null) {
                    dataprovider.deleteLocalClient(this);
                }
                if (timeOfFirstResult < timeOfLastResult) {
                    p.clearAll();
                }
                p.stopProgressBar();
                p.startProgressBar(true);
                if (continuous) {
                    p.setDataInterval(length);
                } else {
                    p.setDataInterval(-1l);
                }
                p.repaint();
                timeOfFirstResult = Long.MAX_VALUE;
                timeOfLastResult = Long.MIN_VALUE;
                if (continuous) {
                    start = -length;
                    logger.log(Level.INFO, "Registering for data on last " + (start / 1000 / 60) + " minutes");
                } else {
                    logger.log(Level.INFO, "Registering for data from =" + new Date(start) + " to=" + new Date(end));
                }
                predicate.tmin = start;
                predicate.tmax = end;
                dataprovider.addLocalClient(this, predicate);
                resultsCount++;
                queryHasResult = false;
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Error requesting data");
            }
        }
    }

    @Override
    public void windowActivated(WindowEvent e) {
    }

    @Override
    public void windowClosed(WindowEvent e) {
        if (p == null) {
            return;
        }
        if (!closed) {
            dataprovider.deleteLocalClient(this);
            p.stopProgressBar();
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
    public void adjustmentValueChanged() {
        if (timeSelector == null) {
            return;
        }
        long start = timeSelector.getMinRange();
        long end = timeSelector.getMaxRange();
        long length = end - start;
        continuous = timeSelector.isContinuous();
        try {
            if (dataprovider != null) {
                dataprovider.deleteLocalClient(this);
            }
            if (timeOfFirstResult < timeOfLastResult) {
                p.clearAll();
            }
            p.stopProgressBar();
            p.startProgressBar(true);
            if (continuous) {
                p.setDataInterval(length);
            } else {
                p.setDataInterval(-1l);
            }
            p.repaint();
            timeOfFirstResult = Long.MAX_VALUE;
            timeOfLastResult = Long.MIN_VALUE;
            if (continuous) {
                start = -length;
                logger.log(Level.INFO, "Registering for data on last " + (start / 1000 / 60) + " minutes");
            } else {
                logger.log(Level.INFO, "Registering for data from =" + new Date(start) + " to=" + new Date(end));
            }
            predicate.tmin = start;
            predicate.tmax = end;
            dataprovider.addLocalClient(this, predicate);
            resultsCount++;
            queryHasResult = false;
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Error requesting data");
        }
    }

}
