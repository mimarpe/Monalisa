package lia.Monitor.Filters;

import java.util.Collection;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

import lia.Monitor.monitor.AppConfig;
import lia.Monitor.monitor.Result;
import lia.Monitor.monitor.eResult;
import lia.Monitor.monitor.monPredicate;
import lia.util.DataArray;
import lia.util.StringFactory;
import lia.util.Utils;
import lia.util.ntp.NTPDate;

/**
 * Aggregate FDT transfers. 
 * Data is produced by LISA, which runs FDT and generates the monitoring results.
 * 
 * If you use monFDTClient and monFDTServer, to start FDT from ML, use FDTFilter instead!
 * 
 * @author catac
 */
public class LisaFDTFilter extends GenericMLFilter {
	private static final long	serialVersionUID	= 1L;

	/** Logger used by this class */
    private static final transient Logger logger = Logger.getLogger("lia.Monitor.Filters.LisaFDTFilter");

    /** 
     * How often I should send summarized data (given in ml.properties in seconds) 
     * Default: 20 seconds 
     */
    public static final long SLEEP_TIME = AppConfig.getl("lia.Monitor.Filters.LisaFDTFilter.SLEEP_TIME", 20) * 1000; 
    
    /** 
     * Consider a param no longer available after PARAM_EXPIRE time (given in ml.properties in seconds)
     * Default: 35 seconds 
     */
    private static long PARAM_EXPIRE = AppConfig.getl("lia.Monitor.Filters.LisaFDTFilter.PARAM_EXPIRE", 35) * 1000;

    /** The name of this filter */
    public static final String Name = "LisaFDTFilter";

    private Hashtable htLinksWithTransfers; 	// key=linkName(w/o dir), value=hash: key=transfName; value=FDTClient
    private Hashtable htFDTServers;				// key=serverName, value=FDTServer
    
    /**
     * Build the filter with the service name as argument
     * 
     * @param farmName
     */
    public LisaFDTFilter(String farmName) {
        super(farmName);
        
        htLinksWithTransfers = new Hashtable();
        htFDTServers = new Hashtable();
    }
    
	public monPredicate[] getFilterPred() {
		return null;
	}

	public String getName() {
		return Name;
	}

	public long getSleepTime() {
		return SLEEP_TIME;
	}

	synchronized public void notifyResult(Object o) {
		if(o == null)
			return;
		if(o instanceof Result){
			Result r = (Result) o;
			if(r.Module.equals("LisaFDTFilter"))
				return;
			if(r.ClusterName.startsWith("Link_")) {
			    final Result retR = Utils.filterNegativeValues(r, true);
                addFDTClientData(retR);
			}
			if(r.ClusterName.equals("Servers") && (! r.NodeName.equals("_TOTALS_"))) {
                final Result retR = Utils.filterNegativeValues(r, true);
                addServerData(retR);
			}
			
		}else if(o instanceof eResult){
			eResult er = (eResult) o;
			if(er.Module.equals("LisaFDTFilter"))
				return;
		}
	}

// FDTClients & Transfers //////////////////////////////////////////////////////////////

	/**
	 * This class identifies a FDT transfer performed by this client  
	 */
	private static class FDTTransfer {
	
		/**
		 * clientName_serverIP:port
		 */
		String transfName;
		
		/**
		 * just the clientName portion of the transfName
		 */
		String clientName;
		
		/**
		 * Name of the link
		 */
		String linkName;
		
		/**
		 * _IN or _OUT
		 */
		String netDir;
		
		/**
		 * _READ or _WRITE
		 */
		String diskDir;
		
		/**
		 * the rates received for this transfer
		 */
		DataArray rates; 
		
    	/**
    	 * 
    	 */
    	long lastUpdateTime;
		
    	/**
    	 * @param transfName
    	 * @param clientName
    	 * @param linkName
    	 * @param direction
    	 */
    	public FDTTransfer(String transfName, String clientName, String linkName, String direction){
    		this.transfName = transfName;
    		this.clientName = clientName;
    		this.linkName = linkName;
    		this.netDir = direction;
    		this.diskDir = direction.equals("_IN") ? "_WRITE" : "_READ";
    		rates = new DataArray();
    		String [] params = {"NET_IN_Mb", "NET_OUT_Mb", "DISK_READ_MB", "DISK_WRITE_MB"};
    		for(int i=0; i<params.length; i++)
    			rates.setParam(params[i], 0);
    	}
    	
		/**
		 * @param r
		 */
		public void addData(Result r){
			lastUpdateTime = NTPDate.currentTimeMillis();
			for(int i=0; i<r.param.length; i++){
				String pName = r.param_name[i];
				double pValue = r.param[i];
				if(pName.equals("DISK_READ_MB"))
					rates.setParam("DISK"+diskDir+"_MB", pValue);
				else if(pName.equals("NET_OUT_Mb"))
					rates.setParam("NET"+netDir+"_Mb", pValue);
				else if(pName.equals("TotalMBytes") || pName.equals("TransferredMBytes")){
					// ignore
				}else{
					if(logger.isLoggable(Level.FINE)){
						logger.log(Level.FINE, "Received unknown transfer parameter: "+pName+" in result:"+r);
					}
				}
			}
		}
		
		/**
		 * @param linkParams
		 * @param htLinkDetails
		 * @return boolean
		 */
		public boolean summarize(DataArray linkParams, Hashtable htLinkDetails){
    		if(logger.isLoggable(Level.FINEST)){
    			logger.log(Level.FINEST, "Summarizing "+toString());
    		}
    		long now = NTPDate.currentTimeMillis();
    		// double timeInterval = SLEEP_TIME / 1000.0d;
    		if(now - lastUpdateTime > PARAM_EXPIRE){
    			logger.log(Level.INFO, "Removing expired "+toString());
				return false;	
    		}
			// add the summary for this client
			DataArray clientParams = (DataArray) htLinkDetails.get(clientName);
			if(clientParams == null){
				clientParams = new DataArray();
				htLinkDetails.put(clientName, clientParams);
			}
			rates.addToDataArray(clientParams);
    		// add the summary for this link
    		rates.addToDataArray(linkParams);
    		// don't reset rates. Keep prev values and if no data comes, they will expire just before the second summary
    		// rates.setToZero();
    		linkParams.addToParam("active_transfers", 1);
    		return true;
		}
		
		public String toString(){
			return "FDTTransfer "+transfName+" on "+linkName+netDir;
		}
	}

	private void addFDTClientData(Result r){
		String linkAndDir = r.ClusterName.substring("Link_".length()).toUpperCase();
		if(! linkAndDir.endsWith("_IN") && ! linkAndDir.endsWith("_OUT")){
			logger.log(Level.WARNING, "The name of the link should end with '_IN' or '_OUT':\n"+r);
			return;
		}
		int idx = linkAndDir.lastIndexOf('_');
		String link = StringFactory.get(linkAndDir.substring(0, idx));
		String direction = StringFactory.get(linkAndDir.substring(idx));
		idx = r.NodeName.indexOf('_');
		if(idx == -1){
			logger.log(Level.WARNING, "transfer node name should be client_server:port\n"+r);
			return;
		}
		String client = StringFactory.get(r.NodeName.substring(0, idx));
		FDTTransfer transfer = findFDTTransfer(link, direction, r.NodeName, client, true);
		transfer.addData(r);
	}
	
	private FDTTransfer findFDTTransfer(String linkName, String direction, String transfName, String clientName, boolean create){
		Hashtable htTransfers = (Hashtable) htLinksWithTransfers.get(linkName);
		if(htTransfers == null){
			htTransfers = new Hashtable();
			htLinksWithTransfers.put(linkName, htTransfers);
		}
		FDTTransfer transfer = (FDTTransfer) htTransfers.get(transfName+direction);
		if(transfer == null){
			transfer = new FDTTransfer(transfName, clientName, linkName, direction);
			htTransfers.put(transfName+direction, transfer);
		}
		return transfer;
	}
	
	private void summarizeLinks(Collection rez){
		Hashtable htLinkDetails = new Hashtable(); // key=LisaName; value=DataArray with its params
		DataArray linkSummary = new DataArray();
		DataArray globalSummary = new DataArray();
		String [] params = {"NET_IN_Mb", "NET_OUT_Mb", "DISK_READ_MB", "DISK_WRITE_MB", "active_transfers"};
		for(int i=0; i<params.length; i++)
			linkSummary.setParam(params[i], 0);
		globalSummary.setAsDataArray(linkSummary);
		globalSummary.setParam("active_links", 0);
		for(Iterator lit = htLinksWithTransfers.entrySet().iterator(); lit.hasNext(); ){
			Map.Entry lme = (Map.Entry) lit.next();
			String linkName = (String) lme.getKey();
			Hashtable htTransfers = (Hashtable) lme.getValue();
			if(htTransfers.size() == 0){
				lit.remove();
				continue;
			}
			htLinkDetails.clear();
			linkSummary.setToZero();
			for(Iterator tit = htTransfers.values().iterator(); tit.hasNext(); ){
				FDTTransfer transfer = (FDTTransfer) tit.next();
				if(! transfer.summarize(linkSummary, htLinkDetails))
					tit.remove();
			}
			for(Iterator cit = htLinkDetails.entrySet().iterator(); cit.hasNext(); ){
				Map.Entry cme = (Map.Entry) cit.next();
				String clientName = (String) cme.getKey();
				DataArray clientParams = (DataArray) cme.getValue();
				addRezFromDA(rez, "LinkDetails_"+linkName, clientName, clientParams);
			}
			addRezFromDA(rez, "LinkDetails_"+linkName, "_TOTALS_", linkSummary);
			linkSummary.addToDataArray(globalSummary);
			addRezFromDA(rez, "Links_Summary", linkName, linkSummary);
			globalSummary.addToParam("active_links", 1);
		}
		addRezFromDA(rez, "Links_Summary", "_TOTALS_", globalSummary);
	}
	
// FTDServers //////////////////////////////////////////////////////////////////////////

	private static class FDTServer {
		/**
		 * 
		 */
		String serverName;
		
		/**
		 * the rates received for this server
		 */
		DataArray rates;
		
		/**
		 * the running params for this server
		 */
		DataArray params; 
		
    	/**
    	 * 
    	 */
    	long lastUpdateTime;
		
		/**
		 * @param serverName
		 */
		public FDTServer(String serverName) {
			this.serverName = serverName;
			rates = new DataArray();
			params = new DataArray();
		}
		
		/**
		 * @param r
		 */
		public void addData(Result r){
			lastUpdateTime = NTPDate.currentTimeMillis();
			for(int i=0; i<r.param.length; i++){
				String pName = r.param_name[i];
				double pValue = r.param[i];
				if(pName.equals("DISK_WRITE_MB") || pName.equals("NET_IN_Mb"))
					rates.setParam(pName, pValue);
				else if(pName.equals("CLIENTS_NO"))
					params.setParam(pName, pValue);
				else{
					if(logger.isLoggable(Level.FINE)){
						logger.log(Level.FINE, "Received unknown server parameter: "+pName+" in result:"+r);
					}
				}
			}
		}
		
		/**
		 * @param globalParams
		 * @return boolean
		 */
		public boolean summarize(DataArray globalParams){
    		if(logger.isLoggable(Level.FINEST)){
    			logger.log(Level.FINEST, "Summarizing "+toString());
    		}
    		long now = NTPDate.currentTimeMillis();
    		// double timeInterval = SLEEP_TIME / 1000.0d;
    		if(now - lastUpdateTime > PARAM_EXPIRE){
    			logger.log(Level.INFO, "Removing expired "+toString());
				return false;	
    		}
    		// add the summary for this link
    		rates.addToDataArray(globalParams);
    		// don't reset rates. Keep the prev values and expire the params just before the second summary
    		// rates.setToZero();
    		params.addToDataArray(globalParams);
    		globalParams.addToParam("active_servers", 1);
    		return true;
		}
		
		public String toString(){
			return "FDTServer "+serverName;
		}
	}

	private void addServerData(Result r){
		FDTServer server = (FDTServer) htFDTServers.get(r.NodeName);
		if(server == null){
			server = new FDTServer(r.NodeName);
			htFDTServers.put(r.NodeName, server);
		}
		server.addData(r);
	}
	
	private void summarizeServers(Collection rez){
		DataArray globalSummary = new DataArray();
		String [] params = {"NET_IN_Mb", "DISK_WRITE_MB", "CLIENTS_NO", "active_servers"};
		for(int i=0; i<params.length; i++)
			globalSummary.setParam(params[i], 0);
		for(Iterator sit = htFDTServers.values().iterator(); sit.hasNext(); ){
			FDTServer server = (FDTServer) sit.next();
			if(! server.summarize(globalSummary))
				sit.remove();
		}
		addRezFromDA(rez, "Servers", "_TOTALS_", globalSummary);
	}
////////////////////////////////////////////////////////////////////////////////////////

	/** 
	 * Build a Result from the given DataArray into the appropriate cluster/node; 
	 * also returns the result.
	 * If the given DataArray is null, it returns a expire result for that node
	 * If nodeName is null, it returns the expire result for that cluster
	 */
	private Result addRezFromDA(Collection vrez, String clusterName, String nodeName, DataArray da){
		if(nodeName == null){
			eResult er = new eResult(farm.name, clusterName, null, "LisaFDTFilter", null);
			er.time = NTPDate.currentTimeMillis();
			vrez.add(er);
			return null;
		}
		if(da == null){
			eResult er = new eResult(farm.name, clusterName, nodeName, "LisaFDTFilter", null);
            er.time = NTPDate.currentTimeMillis();
            vrez.add(er);
            return null;
		}
		
		if(da.size() != 0){
			Result rez = new Result(farm.name, clusterName, nodeName, "monXDRUDP", da.getParameters());
			for(int i=0; i<rez.param_name.length; i++)
				rez.param[i] = da.getParam(rez.param_name[i]);
			
			rez.time = NTPDate.currentTimeMillis();
			vrez.add(rez);
			return rez;
		}else{
			return null;
		}
	}

	private static void reloadConfig(){
		PARAM_EXPIRE = AppConfig.getl("lia.Monitor.Filters.LisaFDTFilter.PARAM_EXPIRE", PARAM_EXPIRE/1000) * 1000;
	}
	
	synchronized public Object expressResults() {
		logger.log(Level.FINE, "expressResults was called");
        
		reloadConfig();
		
		Collection rez=new LinkedList();
		
		try{
			summarizeLinks(rez);
		}catch(Throwable t){ logger.log(Level.WARNING, "Error doing summarizeLinks()", t); }

		try{
			summarizeServers(rez);
		}catch(Throwable t){ logger.log(Level.WARNING, "Error doing summarizeServers()", t); }
		
		if(logger.isLoggable(Level.FINEST)){
			StringBuilder sb = new StringBuilder();
			for(Iterator rit = rez.iterator(); rit.hasNext(); ){
				Result r = (Result) rit.next();
				sb.append(r.toString()+"\n");
			}
			logger.log(Level.FINEST, "Summarised results are: "+sb.toString());
		}
		
		final Vector returnV = new Vector(rez.size());
		for(Iterator it = rez.iterator(); it.hasNext();) {
		    final Object ro = it.next();
		    if(ro instanceof Result) {
		        returnV.add(Utils.filterNegativeValues((Result)ro, true));
		    } else {
		        returnV.add(ro);
		    }
		    
		}
		return returnV;
	}

}