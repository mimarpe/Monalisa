
package lia.Monitor.ClientsFarmProxy.AgentsPlatform;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

import lia.Monitor.Agents.MSTBestEdge;
import lia.Monitor.JiniSerFarmMon.MLLUSHelper;
import lia.Monitor.monitor.ABPingEntry;
import lia.Monitor.monitor.ABPingMeasurement;
import lia.Monitor.monitor.MFarm;
import lia.Monitor.monitor.MonitorClient;
import lia.Monitor.monitor.Result;
import lia.util.ntp.NTPDate;
import net.jini.core.entry.Entry;
import net.jini.core.lookup.ServiceItem;


public class MSTAgentsMonitorClient implements MonitorClient {
	
	public static final long REFRESHER_TIME = 120000; // 1 min
	
	/** Logger name */
	private static final transient String COMPONENT = "lia.Monitor.ClientsFarmProxy";
	/** Logger used by this class */
	private static final transient Logger logger = Logger.getLogger(COMPONENT);
	
	MLLUSHelper lusHelper ;
	Hashtable measurements ;
	Hashtable mapping ;
	Refresher refresher;
	
	private static MSTAgentsMonitorClient _thisInstance = null;
	
	class MapSID {
		String SID;
		long time;
		
		public MapSID (String SID) {
			this.SID = SID ;
			time = NTPDate.currentTimeMillis();
		} // MapSID
		
		public void setTime (long time) {
			this.time = time ;
		} // setTime
		
	} // MapSID
	
	class Refresher extends Thread {
		
		boolean hasToRun = true;
		
		public void run () {
			while (hasToRun) {
				synchronized (mapping) {
					for (Enumeration e = mapping.keys();e.hasMoreElements();) {
						String key = (String)e.nextElement();
						MapSID map = (MapSID) mapping.get (key) ;
						if (NTPDate.currentTimeMillis()-map.time>=REFRESHER_TIME) {							
							mapping.remove(key);
							measurements.remove (map.SID);
						} else { // verify if the SID remained the same
							ServiceItem[] services = lusHelper.getServices();
							if (services!=null) {
								int alive = 0;
								for (int i=0;i<services.length;i++) {
									if (map.SID.equals(services[i].serviceID.toString())) {
										alive = 1;
										break;
									}//if
								} // for
								if (alive==0) {							
								mapping.remove(key);
								measurements.remove (map.SID);
								} // if
							} // if
						} // if - else 
					}//for 
				} // synchronized
				try {
					Thread.sleep (60000);
				} catch (Exception e){}
			} //run
		} // run
		
	} // Refresher
	
	public static final MSTAgentsMonitorClient getInstance() {
		if (_thisInstance == null){
			_thisInstance = new MSTAgentsMonitorClient();
			
			logger.log(Level.INFO, "MSTAgentsMonitorClient ADDED!");
		} // if
		
		return _thisInstance;
	}
	
	public MSTAgentsMonitorClient () {
		lusHelper = MLLUSHelper.getInstance();
		measurements = new Hashtable ();
		mapping = new Hashtable ();
		refresher = new Refresher ();
		refresher.start();
	} // AgentsMonitorClient
	
	
	// gets the best metric for all farms except the ones given in the excepts vector
	public MSTBestEdge getBestEdge (String metric, Vector excepts) {
		MSTBestEdge rez = null;
		double best=-1;
		
		// get the best measurement for RTime metric
		if (metric.equals ("RTime")) {
			synchronized (measurements) {
				for (Enumeration e = measurements.keys();e.hasMoreElements();) {
					String farmSID = (String)e.nextElement();
					if (excepts ==null || !excepts.contains(farmSID)) {
						ABPingMeasurement abpm = (ABPingMeasurement)measurements.get (e.nextElement());
						double rtime = abpm.RTime;
						if (best==-1 || best>rtime) {
							best = rtime;
							rez = new MSTBestEdge (farmSID, Double.valueOf(best));
						} // if
					} //if	
				} // for
			} // synchronized
		} // if
		
		// get the best measurement for RTT metric
		if (metric.equals ("RTT")) {		
			synchronized (measurements) {			
				for (Enumeration e = measurements.keys();e.hasMoreElements();) {
					String farmSID = (String)e.nextElement();					
					if (excepts==null || !excepts.contains(farmSID)) {
						ABPingMeasurement abpm = (ABPingMeasurement)measurements.get (farmSID);
						double rtt = abpm.RTT;
						if (best==-1 || best>rtt) {
							best = rtt;
							rez = new MSTBestEdge (farmSID, Double.valueOf(best));							
						} // if
					} //if	
				} // for
			} // synchronized
		} // if
		
		return rez ; 
		
	} // getBestEdge
	
	public void notifyResult ( Object res , int pid)  throws java.rmi.RemoteException {
		
	} // notifyResult
	
	public void newConfig( MFarm f )  throws java.rmi.RemoteException{
		
	} // new Config

	private String getSIDMapping (String nodeName) {
		String sid = null;
		
		ServiceItem[] si = lusHelper.getServices();
		if (si == null)
			return null;
		for (int i=0;i<si.length;i++) {
			Entry [] entry = si[i].attributeSets;
			for (int j=0;j<entry.length;j++) {
				if (entry[j] instanceof ABPingEntry) {
					if (nodeName.equals (((ABPingEntry)entry[j]).IPAddress) || nodeName.equals (((ABPingEntry)entry[j]).FullHostName) ) {
						return si[i].serviceID.toString();
					} // if
				} // if
			} // for
		} // for
		
		return null;
		
	} // getSIDMapping
	
	private void setResult (Result r) {
		String nodeName = r.NodeName;
		if (nodeName == null)
			return;
		
		double RTime ;
		double RTT ;
		double Jitter ;
		double PacketLoss ;
		
		int index=-1;
		
		index = r.getIndex( "RTime") ;
		RTime = r.param[index];		
		
		index = r.getIndex( "RTT") ;
		RTT = r.param[index];		
		
		index = r.getIndex( "Jitter") ;
		Jitter = r.param[index];		
		
		index = r.getIndex( "PacketLoss") ;
		PacketLoss = r.param[index];		
		
		synchronized (mapping) {
			MapSID m = (MapSID)mapping.get(nodeName);
			
			if (m!=null) {
				if (PacketLoss<1)
					m.setTime (NTPDate.currentTimeMillis());
				
				String sid = m.SID;
				
				ABPingMeasurement abpm = new ABPingMeasurement (RTime, RTT, Jitter, PacketLoss);

				measurements.put( sid, abpm);
			} else {
				
				String sid = getSIDMapping (r.NodeName);
				
				if (sid!=null) {
					mapping.put (r.NodeName, new MapSID(sid));
					
					ABPingMeasurement abpm = new ABPingMeasurement (RTime, RTT, Jitter, PacketLoss);					
					measurements.put( sid, abpm);
				} // if sid != null
				
			} // if - else
			
		} // synchronized

	} // setResult
	
	
	public void notifyResult ( Object res , String filter)  throws java.rmi.RemoteException {
		
		if (res!=null ) {
			
			for (int i=0;i<((Vector)res).size();i++) {
				Object r = ((Vector)res).elementAt (i);
				if (r instanceof Result) {
					Result rr = (Result) r ;
					
					setResult(rr);
					
				} // if	
					
			} // for
			
		} else {
			logger.log(Level.WARNING, "AgentsMonitorClient -------> strange ABPing for MST ");
		} // if - else
		
	} // notifyResult
	
} // AgentsMonitorClient
