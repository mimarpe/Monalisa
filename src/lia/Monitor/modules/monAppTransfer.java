package lia.Monitor.modules;

import java.util.Iterator;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

import lia.Monitor.Farm.Transfer.ProtocolManager;
import lia.Monitor.monitor.MNode;
import lia.Monitor.monitor.MonModuleInfo;
import lia.Monitor.monitor.MonitoringModule;
import lia.util.DynamicThreadPoll.SchJob;

/**
 * Simple module to get monitoring information from the AppTransfer protocols.
 * 
 * Usage:
 * ^monAppTransfer{ParamTimeout=300,NodeTimeout=300,ClusterTimeout=300}%5
 * 
 * @author catac
 */
public class monAppTransfer extends SchJob implements MonitoringModule {
	/** Logger used by this class */
	public final transient Logger logger = Logger.getLogger(getClass().getName());

	static protected String OsName = "*";
	protected MNode Node;
	protected MonModuleInfo info;
	private String[] sResTypes = new String[0]; // dynamic
	static public String ModuleName = "monAppTransfer";
	private Vector vResults;
	
	public monAppTransfer() {
		vResults = new Vector();
	}
		
	/**
	 * Init the module info and parse the arguments
	 */
	public MonModuleInfo init(MNode node, String args) {
		this.Node = node;
		info = new MonModuleInfo();
		try {
			// don't care about arguments
			info.setState(0);
		} catch (Exception e) {
			info.setState(1);// error
		}
		info.ResTypes = sResTypes;
		info.setName(ModuleName);
		return info;
	}

	public boolean isRepetitive() {
		return true;
	}
	
	public String[] ResTypes() {
		return sResTypes;
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

	public MonModuleInfo getInfo() {
		return info;
	}

	public String getOsName() {
		return OsName;
	}

	public String getTaskName() {
		return info.getName();
	}

	public Object doProcess() throws Exception {
		// return the cache monitoring information received from FDT
		vResults.clear();
		try{
			ProtocolManager.getInstance().getMonitorInfo(vResults);
		}catch(Throwable t){
			logger.log(Level.WARNING, "Failed getting monitor info", t);
		}
		Level logLevel = vResults.size() > 0 ? Level.FINE : Level.FINER;
		if(logger.isLoggable(logLevel)) {
			StringBuilder sbRes = new StringBuilder("Publishing "+vResults.size()+" results from AppTransfer protocols:");
			for(Iterator rit = vResults.iterator(); rit.hasNext(); ) {
				sbRes.append("\n").append(rit.next());
			}
			logger.log(logLevel, sbRes.toString());
		}
		return vResults;
	}
}
