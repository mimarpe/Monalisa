package lia.Monitor.modules;

import java.io.BufferedReader;
import java.util.logging.Logger;

public final class VrvsUtil {
    /** Logger Name */
    private static final String COMPONENT = "lia.Monitor.modules";
    /** The Logger */ 
    private static final Logger logger = Logger.getLogger(COMPONENT);

	private final static Object syncConnLocker = new Object();

	/**
	 * 
	 * Synchronized version of TcpCmd. 
	 *  
	 * @param host
	 * @param port
	 * @param cmd
	 * @return
	 */
	public static BufferedReader syncTcpCmd(
		String host,
		int port,
		String cmd) {

		synchronized (syncConnLocker) {

			return MLModulesUtils.TcpCmd(host, port, cmd);

		}

	}//end syncTcpCmd
	
	public static Object getReflLock() {
	    return syncConnLocker;
	}
}