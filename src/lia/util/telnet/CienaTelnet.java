package lia.util.telnet;

import java.util.logging.Level;
import java.util.logging.Logger;

import lia.Monitor.monitor.AppConfig;

/**
 * 
 * 
 * @author ramiro
 * 
 */
public class CienaTelnet extends TL1Telnet {

    /** Logger used by this class */
    private static final transient Logger logger = Logger.getLogger(TL1Telnet.class.getName());
    private static volatile boolean monitorInited;
    //private static CienaTelnet _controlInstance = null;
    private static CienaTelnet _monitorInstance = null;
    private static final String INHIBIT_AUTONOMOUS_MSGS_TL1_CMD = "INH-MSG-ALL:::inh;\n";

    public CienaTelnet(String username, String passwd, String hostName, int port) throws Exception {
        super(username, passwd, hostName, port);
    // TODO Auto-generated constructor stub
    }

    public static CienaTelnet getMonitorInstance() throws Exception {

        if (!monitorInited) {
            synchronized (CienaTelnet.class) {
                if (!monitorInited) {
                    final String username = AppConfig.getProperty("lia.util.telnet.CienaMonitorUsername");
                    if(username == null || username.trim().length() == 0) {
                        throw new Exception("[ CienaTelnet ] CienaMonitorUsername MUST BE != null. (The property lia.util.telnet.CienaMonitorUsername is not set )");
                    }
                    
                    final String passwd = AppConfig.getProperty("lia.util.telnet.CienaMonitorPasswd");
                    if(passwd == null || passwd.trim().length() == 0) {
                        throw new Exception("[ CienaTelnet ] CienaMonitorPasswd MUST BE != null. (The property lia.util.telnet.CienaMonitorPasswd is not set )");
                    }
                    
                    final String hostName = AppConfig.getProperty("lia.util.telnet.CienaMonitorHostname");
                    if(hostName == null || hostName.trim().length() == 0) {
                        throw new Exception("[ CienaTelnet ] CienaMonitorHostname MUST BE != null. (The property lia.util.telnet.CienaMonitorHostname is not set )");
                    }

                    int port = 10201;

                    try {
                        port = AppConfig.geti("lia.util.telnet.CienaMonitorPort", 10201);
                    } catch (Throwable t) {
                        port = 10201;
                    }

                    _monitorInstance = newInstance(username.trim(), passwd.trim(), hostName.trim(), port);

                    monitorInited = true;
                }//if - sync
            }//end sync


        }//if - not sync

        return _monitorInstance;
    }

    /**
     * 
     * @param userName
     * @param passwd
     * @param hostName
     * @return a new instance
     * @throws Exception
     */
    public static CienaTelnet newInstance(final String userName, final String passwd, final String hostName) throws Exception {
        return new CienaTelnet(userName.trim(), passwd.trim(), hostName.trim(), 10201);
    }
    
    /**
     * 
     * @param userName
     * @param passwd
     * @param hostName
     * @param port
     * @return a new instance
     * @throws Exception
     */
    public static CienaTelnet newInstance(final String userName, final String passwd, final String hostName, final int port) throws Exception {
        return new CienaTelnet(userName.trim(), passwd.trim(), hostName.trim(), port);
    }

    @Override
    public void connected() {
        try {
            execCmd(INHIBIT_AUTONOMOUS_MSGS_TL1_CMD, "inh");
            logger.log(Level.INFO, " [ CienaTelnet ] connected; sent " + INHIBIT_AUTONOMOUS_MSGS_TL1_CMD);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "\n\n !!!! [ CienaTelnet ] [ connected ] Got exception trying to disable autonomous messages", t);
        }
    }

    /**
     * @param args
     */
    public static void main(String[] args) throws Exception {
        // TODO Auto-generated method stub
        CienaTelnet cienaTL1Connection = getMonitorInstance();
        for (;;) {
            final StringBuilder sb = cienaTL1Connection.doCmd("rtrv-alm-all:::1;\n", "1");
            System.out.println(sb.toString());
            try {
                Thread.sleep(5 * 1000);
            } catch (Throwable t) {
                //just to test
            }
        }
    }

}
