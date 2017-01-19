/*
 * $Id: Cache.java 7127 2011-03-14 09:42:53Z costing $
 */
package lia.Monitor.Store;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lia.Monitor.Store.Fast.IDGenerator;
import lia.Monitor.monitor.AppConfig;
import lia.Monitor.monitor.AppConfigChangeListener;
import lia.Monitor.monitor.ExtResult;
import lia.Monitor.monitor.Gresult;
import lia.Monitor.monitor.Result;
import lia.Monitor.monitor.ResultUtils;
import lia.Monitor.monitor.eResult;
import lia.Monitor.monitor.monPredicate;
import lia.util.ntp.NTPDate;
import lia.util.threads.MonALISAExecutors;
import lia.web.utils.Formatare;

/**
 * This class is used in both the service and the repository to keep the last values 
 * for each data series, not older than a given threshold. By default the expiration
 * time for old data is 15 minutes. There is an application configuration parameter,
 * <code>lia.web.Cache.RecentData</code> that controls the expiration time. The value
 * for this parameter is in minutes.
 * 
 * @author costing
 */
public final class Cache {
	/**
	 * Event logger
	 */
	static final Logger	logger = Logger.getLogger("lia.Monitor.Store.Cache");

	private static Map<String, Object>	hmCache	= new HashMap<String, Object>();

	private static volatile long	lRecentData	= 15 * 60 * 1000;
	
	private static volatile long    SNAPSHOT_MAX_LIFETIME = 30 * 1000;
	
	/**
	 * Protects hmCache from concurrent access
	 */
	private static final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

	private static final Lock readLock = rwLock.readLock();
	private static final Lock writeLock = rwLock.writeLock();
	
	private static final ReentrantReadWriteLock configLock = new ReentrantReadWriteLock();
	
	private static final Lock configReadLock = configLock.readLock();
	private static final Lock configWriteLock = configLock.writeLock();
	
    private static ArrayList<monPredicate> alAccept = new ArrayList<monPredicate>();
    private static ArrayList<monPredicate> alReject = new ArrayList<monPredicate>();
    
    private static boolean bFilterOnAdd = false;
	
	static {
		init();
		
		AppConfig.addNotifier(new AppConfigChangeListener(){
				@Override
				public void notifyAppConfigChanged() {
					init();
				}
			}
		);
		
		MonALISAExecutors.getMLStoreExecutor().scheduleWithFixedDelay(new CacheCleanupTask(), lRecentData/60, lRecentData/60, TimeUnit.MILLISECONDS);
	}

	/**
	 * Load configuration from {@link AppConfig}
	 */
	static final void init() {
		configWriteLock.lock();
		
		try {
			lRecentData = AppConfig.geti("lia.web.Cache.RecentData", 15);

			lRecentData = AppConfig.geti("lia.Monitor.Store.Cache.RecentData", (int) lRecentData);
			
			if (lRecentData < 0 || lRecentData > 14400) { // 10 days hard limit ... fix this if you really need to
				lRecentData = 15;
			}

			lRecentData *= 60 * 1000;
			
			// default snapshots are valid for the next 30 seconds (if not deleted sooner by cleanup()
			SNAPSHOT_MAX_LIFETIME = AppConfig.geti("lia.Monitor.Store.Cache.snapshot_max_lifetime", 30) * 1000;
			
			alAccept = toPredArray(AppConfig.getProperty("lia.Monitor.Store.Cache.accept"));
			alReject = toPredArray(AppConfig.getProperty("lia.Monitor.Store.Cache.reject"));
			
			bFilterOnAdd = alAccept.size() > 0 || alReject.size() > 0;
		}
		catch (Exception e) {
			lRecentData = 15 * 60 * 1000;
		}
		finally{
			configWriteLock.unlock();
		}
		
		logger.log(Level.INFO, "[ LatestValuesCache ] lRecentData = " + lRecentData / 1000 + " seconds");
	}
	
	private static final ArrayList<monPredicate> toPredArray(final String s){
		final ArrayList<monPredicate> ret = new ArrayList<monPredicate>();
		
		if (s==null || s.length()==0)
			return ret;
		
		final StringTokenizer st = new StringTokenizer(s, ",");

        while (st.hasMoreTokens()) {
            final monPredicate pred = Formatare.toPred(st.nextToken());

            if (pred != null)
                ret.add(pred);
        }
		
		return ret;
	}

	/**
	 * Statistics function, find out what is the current size of the data cache.
	 * 
	 * @return the size of the data cache.
	 */
	public static int size() {
		readLock.lock();
		
		try{
			return hmCache.size();
		}
		finally{
			readLock.unlock();
		}
	}

	/**
	 * Get the expiration time for old values.
	 * 
	 * @return the expiration time, in minutes.
	 */
	public static long getTimeout() {
		return lRecentData / (60 * 1000);
	}

	/**
	 * Add an object to the data cache. This object must be an instance of:<br>
	 * <ul>
	 * 	<li>{@link Result}</li>
	 *  <li>{@link eResult}</li>
	 *  <li>{@link ExtResult}</li>
	 *  <li>{@link Collection} (in this case all the elements in this collection are added using the same method</li>
	 * </ul>
	 * Any other object will be discarded.
	 * 
	 * @param o object to add.
	 */
	public static void addToCache(final Object o) {
		if (o == null){
			// bogus call ...
			return;
		}
				
		if (o instanceof Result) {
			addToCache((Result) o);
		} else if (o instanceof eResult) {
			addToCache((eResult) o);
		} else if (o instanceof ExtResult) {
			addToCache((ExtResult) o);
		} else if (o instanceof Collection<?>) {
			Collection<?> col = (Collection<?>) o;
			for (Iterator<?> it = col.iterator(); it.hasNext();) {
				addToCache(it.next());
			}
		}
	}

	/**
	 * Add a {@link Result} object to the data cache.
	 * 
	 * @param result object to add.
	 */
	public static void addToCache(final Result result) {
		if (result == null || result.param_name == null || result.param_name.length < 1)
			return;
		
		configReadLock.lock();
		
		Result r;
		
		try{
			if (bFilterOnAdd){
				r = ResultUtils.firewallResult(result, alAccept, alReject);
				
				if (r==null)
					return;
			}
			else
				r = result;
		}
		finally{
			configReadLock.unlock();
		}

		if (r.param_name.length == 1)
			updateCache(IDGenerator.generateKey(r, 0), r);
		else
			for (int i = 0; i < r.param_name.length && i < r.param.length; i++) {
				final Result rTemp = new Result(r.FarmName, r.ClusterName, r.NodeName, r.Module, null);
				rTemp.time = r.time;
				rTemp.addSet(r.param_name[i], r.param[i]);

				updateCache(IDGenerator.generateKey(r, i), rTemp);
			}
	}

	/**
	 * Add an {@link eResult} object to the data cache.
	 * 
	 * @param result object to add.
	 */
	public static void addToCache(final eResult result) {
		if (result == null || result.param_name == null || result.param_name.length < 1)
			return;

		configReadLock.lock();
		
		eResult r;
		
		try{
			if (bFilterOnAdd){
				r = ResultUtils.firewalleResult(result, alAccept, alReject);
				
				if (r==null)
					return;
			}
			else
				r = result;
		}
		finally{
			configReadLock.unlock();
		}
		
		if (r.param_name.length == 1)
			updateCache(IDGenerator.generateKey(r, 0), r);
		else
			for (int i = 0; i < r.param_name.length && i < r.param.length; i++) {
				final eResult rTemp = new eResult(r.FarmName, r.ClusterName, r.NodeName, r.Module, null);
				rTemp.time = r.time;
				rTemp.addSet(r.param_name[i], r.param[i]);

				updateCache(IDGenerator.generateKey(r, i), rTemp);
			}
	}

	/**
	 * Add an {@link ExtResult} object to the data cache.
	 * 
	 * @param result object to add.
	 */
	public static void addToCache(final ExtResult result) {
		if (result == null || result.param_name == null || result.param_name.length < 1)
			return;

		configReadLock.lock();
		
		ExtResult r;
		
		try{
			if (bFilterOnAdd){
				r = ResultUtils.firewallExtResult(result, alAccept, alReject);
				
				if (r==null)
					return;
			}
			else
				r = result;
		}
		finally{
			configReadLock.unlock();
		}
		
		if (r.param_name.length == 1)
			updateCache(IDGenerator.generateKey(r, 0), r);
		else
			for (int i = 0; i < r.param_name.length && i < r.param.length; i++) {
				ExtResult rTemp = new ExtResult(r.FarmName, r.ClusterName, r.NodeName, r.Module, null);
				rTemp.time = r.time;
				rTemp.extra = r.extra;
				rTemp.addSet(r.param_name[i], r.param[i]);

				updateCache(IDGenerator.generateKey(r, i), rTemp);
			}
	}

	private static void updateCache(final String sKey, final Object oVal) {
		if (sKey == null)
			return;

		final long lTime = getResultTime(oVal);

		if (lTime < 0)
			return;

		writeLock.lock();
		
		try{
			final Object o = hmCache.get(sKey);

			if ((o == null) || (getResultTime(o) < lTime))
				hmCache.put(sKey, oVal);
		}
		finally{
			writeLock.unlock();
		}
	}

	/**
	 * Given a predicate, this method iterates through the entries in the map and extracts the 
	 * data that match this predicate. Only the first value that matches is returned. See
	 * {link {@link #getObjectsFromHash(Map, monPredicate, Object, boolean)} if you want to
	 * get all the objects that match this filter. The lock, if not null, is used to synchronize 
	 * the access to the map structure. 
	 * 
	 * @param hm data holder
	 * @param pred predicate to filter the data
	 * @param oLock object to synchronize the access to the data. Can be null.
	 * @param bFilterByTime whether or not to apply the time constraints from the predicate. If false, only the
	 * 			name constraints are applied.
	 * @return the first object that matches the criteria
	 */
	public static final Object getObjectFromHash(final Map<String, Object> hm, final monPredicate pred, final Object oLock, final boolean bFilterByTime) {
		final Vector<?> v = getObjectsFromHash(hm, pred, oLock, bFilterByTime);

		if (v.size() > 0) {
			return v.get(0);
		}

		return null;
	}

	/**
	 * Given a predicate, this method iterates through the entries in the map and extracts the 
	 * data that match this predicate. The lock, if not null, is used to synchronize 
	 * the access to the map structure. 
	 *
	 * @param hm data holder
	 * @param pred predicate to filter the data
	 * @param oLock object to synchronize the access to the data. Can be null.
	 * @param bFilterByTime whether or not to apply the time constraints from the predicate. If false, only the
	 * 			name constraints are applied.
	 * @return a Vector with all the values that matched the criteria
	 */
	public static final Vector<Object> getObjectsFromHash(final Map<String, ?> hm, final monPredicate pred, final Object oLock, final boolean bFilterByTime) {
		return getDataSplitter(hm, pred, oLock, bFilterByTime).toVector();
	}
	
	/**
	 * Given a list of predicates, this method iterates through the entries in the map and extracts the 
	 * data that match the criteria. The lock, if not null, is used to synchronize 
	 * the access to the map structure. 
	 *
	 * @param hm data holder
	 * @param p array of predicates used to filter the data
	 * @param oLock object to synchronize the access to the data. Can be null.
	 * @param bFilterByTime whether or not to apply the time constraints from the predicate. If false, only the
	 * 			name constraints are applied.
	 * @return a {@link DataSplitter} object with the filtered data
	 */
	public static final DataSplitter getDataSplitter(final Map<String, ? extends Object> hm, final monPredicate p[], final Object oLock, final boolean bFilterByTime){
		if (p==null || p.length==0)
			return new DataSplitter(1);

		if (oLock==null)
			return getDataSplitter(hm, p, bFilterByTime);
		
		if (oLock instanceof Lock){
			final Lock lock = (Lock) oLock;
			
			lock.lock();
			
			try{
				return getDataSplitter(hm, p, bFilterByTime);
			}
			finally{
				lock.unlock();
			}
		}
		
		if (oLock instanceof Lock){
			// TODO : remove this piece of code once all references to edu. are removed from the code
			final Lock lock = (Lock)oLock;
			
			lock.lock();
			
			try{
				return getDataSplitter(hm, p, bFilterByTime);
			}
			finally{
				lock.unlock();
			}			
		}
		
		synchronized (oLock){
			return getDataSplitter(hm, p, bFilterByTime);
		}
	}
	
	/**
	 * This method assumes that the lock was acquired and it just has to to its best to extract the data
	 * 
	 * @param hm
	 * @param p
	 * @param bFilterByTime
	 * @return
	 * @see #getDataSplitter(Map, monPredicate[], Object, boolean)
	 */
	private static final DataSplitter getDataSplitter(final Map<String, ? extends Object> hm, final monPredicate p[], final boolean bFilterByTime){
		if (p.length==1)
			return getDataSplitter(hm, p[0], bFilterByTime);
		
		final DataSplitter ds = new DataSplitter(p.length);
		
		if (splitterWorkerThreads==null || p.length<3){
			for (int i = 0; i < p.length; i++) {
				ds.add(getDataSplitter(hm, p[i], bFilterByTime), -1);
			}
		}
		else{
			final LinkedBlockingQueue<Runnable> resultsQueue = new LinkedBlockingQueue<Runnable>();
			
			int iJobs = 0;
			
			final int iChunkSize = (p.length / splitterWorkerThreads.size()) + 1;
			
			int iOffset = 0;
			
			while (iOffset < p.length){
				final SplitterResult sr = new SplitterResult(hm, p, iOffset, iOffset+iChunkSize, bFilterByTime);
				
				final Work w = new Work(sr, resultsQueue);
				
				splitterWorkQueue.add(w);
				
				iJobs++;
				
				iOffset += iChunkSize;
			}
			
			for (int i=0; i<iJobs; i++){
				try{
					final SplitterResult sr = (SplitterResult) resultsQueue.take();
				
					ds.add(sr.getResult(), -1);
				}
				catch (InterruptedException ie){
					// ignore
				}
			}
		}
		
		return ds;
	}
	
	private static final class SplitterResult implements Runnable {
		private final Map<String, ? extends Object> hm;
		private final monPredicate[] p;
		private final int iStart;
		private final int iEnd;
		private final boolean bFilterByTime;   
		
		private DataSplitter result = null;
		
		public SplitterResult(final Map<String, ? extends Object> _hm, final monPredicate[] _p, final int _iStart, final int _iEnd, final boolean _bFilterByTime){
			this.hm = _hm;
			this.p = _p;
			this.iStart = _iStart;
			this.iEnd = _iEnd;
			this.bFilterByTime = _bFilterByTime;
		}
		
		@Override
		public void run(){
			result = new DataSplitter(iEnd-iStart+1);
			
			for (int i=iStart; i<iEnd && i<p.length; i++){
				result.add(getDataSplitter(hm, p[i], bFilterByTime), -1);
			}
		}
		
		public DataSplitter getResult(){
			return result;
		}
		
		@Override
		public String toString(){
			return "SplitterResult: "+iStart+" to "+iEnd+" of "+p.length;
		}
	}
	
	/**
	 * Given a predicate, this method iterates through the entries in the map and extracts the 
	 * data that match the filter. The lock, if not null, is used to synchronize 
	 * the access to the map structure. 
	 *
	 * @param hm data holder
	 * @param pred predicate to filter the data
	 * @param oLock object to synchronize the access to the data. Can be null.
	 * @param bFilterByTime whether or not to apply the time constraints from the predicate. If false, only the
	 * 			name constraints are applied.
	 * @return a {@link DataSplitter} object with the filtered data
	 */
	public static final DataSplitter getDataSplitter(final Map<String, ?> hm, final monPredicate pred, final Object oLock, final boolean bFilterByTime) {		
		if (pred==null){
			return new DataSplitter(1);
		}
	
		if (oLock==null){
			return getDataSplitter(hm, pred, bFilterByTime);
		}
		
		if (oLock instanceof Lock){
			final Lock lock = (Lock) oLock;
			
			lock.lock();
			
			try{
				return getDataSplitter(hm, pred, bFilterByTime);
			}
			finally{
				lock.unlock();
			}
		}
		
		synchronized(oLock){
			return getDataSplitter(hm, pred, bFilterByTime);
		}
	}

	/**
	 * This method assumes that the lock was acquired
	 * 
	 * @param hm
	 * @param pred
	 * @param bFilterByTime
	 * @return the splitter
	 * @see #getDataSplitter(Map, monPredicate, Object, boolean)
	 */
	static final DataSplitter getDataSplitter(final Map<String, ?> hm, final monPredicate pred, final boolean bFilterByTime) {
		if (pred.parameters == null || pred.parameters.length <= 1) {
			return getSingleDataSplitter(hm, pred, bFilterByTime);
		}
		
		final monPredicate tpred = TransparentStoreFactory.normalizePredicate(pred); 

		final DataSplitter ds = new DataSplitter(pred.parameters.length);
		
		for (int i = 0; i < pred.parameters.length; i++) {
			tpred.parameters = new String[] { pred.parameters[i] };
			ds.add(getSingleDataSplitter(hm, tpred, bFilterByTime), -1);
		}

		return ds;		
	}
	
	/**
	 * This method applies the time constraints from a predicate on an object.
	 * It assumes that the {@link monPredicate#tmin} and {@link monPredicate#tmax}
	 * fields have negative values, relative to the current time. The data to filter
	 * can be one of the following : {@link Result}, {@link eResult}, {@link ExtResult}, or
	 * a {@link Collection}, in this case the method will iterate through all the entries
	 * and apply the filter to each of them.
	 * 
	 * @param o object to filter
	 * @param pred filter
	 * @return an ArrayList with the filtered data, maybe empty but never null.
	 */
	public static final ArrayList<Object> filterByTime(final Object o, final monPredicate pred) {
		final long lNow = NTPDate.currentTimeMillis();
		
		final long lMin = lNow + pred.tmin; 
		final long lMax = lNow + pred.tmax;

		final ArrayList<Object> alRez = new ArrayList<Object>();

		if (o instanceof Collection<?>) {
			final Iterator<?> it = ((Collection<?>) o).iterator();

			Object oResult;
			long lTime;
			while (it.hasNext()) {
				oResult = it.next();

				lTime = getResultTime(oResult);

				if (lTime > 0 && lTime >= lMin && lTime <= lMax)
					alRez.add(oResult);
			}
		} else {
			final long lTime = getResultTime(o);

			if (lTime > 0 && lTime >= lMin && lTime <= lMax)
				alRez.add(o);
		}

		return alRez;
	}
	
	private static final DataSplitter getSingleDataSplitter(final Map<String, ?> hm, final monPredicate pred, final boolean bFilterByTime) {
        if(logger.isLoggable(Level.FINEST)) {
            logger.log(Level.FINEST, "getSingleDataSplitter for "+pred+" from "+hm.size()+", filter by time = "+bFilterByTime);
        }
		
		String sFarm = pred.Farm != null ? pred.Farm : "";
		String sCluster = pred.Cluster != null ? pred.Cluster : "";
		String sNode = pred.Node != null ? pred.Node : "";
		String sFunction = pred.parameters != null && pred.parameters.length > 0 ? pred.parameters[0] : "";

		final DataSplitter ds;
		
		if (sFarm.length() > 0 && sFarm.indexOf('*') < 0 && sFarm.indexOf('%') < 0 && 
			sCluster.length() > 0 && sCluster.indexOf('*') < 0 && sCluster.indexOf('%') < 0 && 
			sNode.length() > 0 && sNode.indexOf('*') < 0 && sNode.indexOf('%') < 0 && 
			sFunction.length() > 0 && sFunction.indexOf('*') < 0 && sFunction.indexOf('%') < 0) 
		{
			// no wildcards, everything is clear
			final String sKey = IDGenerator.generateKey(sFarm, sCluster, sNode, sFunction);

			ds = new DataSplitter(1);
			
			if(logger.isLoggable(Level.FINEST)) {
				logger.log(Level.FINEST, "Single data, key is : "+sKey);
			}

			if (sKey == null)
				return ds;

			final Object o = hm.get(sKey);
			
			if(logger.isLoggable(Level.FINEST)) {
				logger.log(Level.FINEST, "Hash query returned : "+o);
			}
			
			if (o==null)
				return ds;

			final Vector<Object> v;
			
			if (bFilterByTime) {
				v = new Vector<Object>(filterByTime(o, pred));
			}
			else {
				if (o instanceof Collection<?>){
					v = new Vector<Object>((Collection<?>) o);
				}
				else{
					v = new Vector<Object>(1);
					v.add(o);
				}
			}
			
            if(logger.isLoggable(Level.FINEST)) {
                logger.log(Level.FINEST, "added value for single series : "+v.size());
            }
			
			ds.addSingleSeries(v, -1);
		} 
		else {
			// I have no good guess about the size of the result, but the map will scale its size quickly anyway ...
			ds = new DataSplitter();
			
			// some wildcards, need to iterate through the cache to see what matches
			if (sFarm.length() == 0 || sFarm.equals("*") || sFarm.equals("%")){
				sFarm = ".+";
			}
			else{
				sFarm = Formatare.replace(sFarm, "*", ".*");
				sFarm = Formatare.replace(sFarm, "%", ".*");
				if (sFarm.equals(".*"))
					sFarm = ".+";
			}
				
			if (sCluster.length() == 0 || sCluster.equals("*") || sCluster.equals("%")){
				sCluster = ".+";
			}
			else{
				sCluster = Formatare.replace(sCluster, "*", ".*");
				sCluster = Formatare.replace(sCluster, "%", ".*");
				if (sCluster.equals(".*"))
					sCluster = ".+";
			}

			if (sNode.length() == 0 || sNode.equals("*") || sNode.equals("%")){
				sNode = ".+";
			}
			else{
				sNode = Formatare.replace(sNode, "*", ".*");
				sNode = Formatare.replace(sNode, "%", ".*");			
				if (sNode.equals(".*"))
					sNode = ".+";
			}

			if (sFunction.length() == 0 || sFunction.equals("*") || sFunction.equals("%")){
				sFunction = ".+";
			}
			else{
				sFunction = Formatare.replace(sFunction, "*", ".*");
				sFunction = Formatare.replace(sFunction, "%", ".*");
				if (sFunction.equals(".*"))
					sFunction = ".+";
			}

			final String sID = IDGenerator.generateKey(sFarm, sCluster, sNode, sFunction);

			filterToDS(hm, sID, ds, bFilterByTime, pred);
		}

		return ds;
	}
	
	/**
	 * Check if this object matches
	 * 
	 * @param sKey
	 * @param o
	 * @param sLargestPart
	 * @param bFilterByTime
	 * @param p
	 * @param pred
	 * @param ds
	 */
	static final void filterData(final String sKey, final Object o, final String sLargestPart, final boolean bFilterByTime, final Pattern p, final monPredicate pred, final DataSplitter ds){
		if (o==null)
			return;
		
		if (sLargestPart.length() <= 1 || sKey.indexOf(sLargestPart) >= 0) { // try to avoid regexp matching
			final Matcher m = p.matcher(sKey);
			
			if (m.matches()) {
				Vector<Object> v;
				
				if (bFilterByTime) {
					v = new Vector<Object>(filterByTime(o, pred));
				}
				else {
					if (o instanceof Collection<?>){
						v = new Vector<Object>((Collection<?>) o);
					}
					else{
						v = new Vector<Object>(1);
						v.add(o);
					}
				}
				
				ds.addSingleSeries(v, -1);
			}
		}
	}
	
	private static final class FilterResult implements Runnable {
		private final ArrayList<String> alKeys;
		private final ArrayList<Object> alValues;
		private final int iOffset;
		private final int iLimit;
		private final String sLargestPart;
		private final boolean bFilterByTime;
		private final monPredicate pred;
		private final Pattern p;
		
		private DataSplitter ds = null;
		
		public FilterResult(final ArrayList<String> keys, final ArrayList<Object> values, final int offset, final int limit, final String largestPart, final boolean filterByTime, final monPredicate filterPred, final Pattern pattern){
			this.alKeys = keys;
			this.alValues = values;
			this.iOffset = offset;
			this.iLimit = limit;
			this.sLargestPart = largestPart;
			this.bFilterByTime = filterByTime;
			this.pred = filterPred;
			this.p = pattern;
		}
		
		@Override
		public void run(){
			final int count = Math.min(alKeys.size() - iOffset - 1, iLimit);
			
			ds = new DataSplitter();
			
			//System.err.println("FilterResult("+iOffset+","+count+") / "+alKeys.size());
			
			for (int i=count; i>=0; i--){
				filterData(alKeys.get(iOffset+i), alValues.get(iOffset+i), sLargestPart, bFilterByTime, p, pred, ds);
			}
		}
		
		public DataSplitter getResult(){
			return ds;
		}
		
		@Override
		public String toString(){
			return "FilterResult: OFFSET "+iOffset+", LIMIT "+iLimit+", TOTAL "+alKeys.size();
		}
	}
	
	private static class Work {
		public final Runnable work;
		public final LinkedBlockingQueue<Runnable> resultsQueue;
		
		public Work(final Runnable rWork, final LinkedBlockingQueue<Runnable> lbqResultsQueue) {
			this.work = rWork;
			this.resultsQueue = lbqResultsQueue;
		}
	}
	
	/**
	 * Work queue
	 */
	static final LinkedBlockingQueue<Work> cacheWorkQueue = new LinkedBlockingQueue<Work>();
	
	/**
	 * Work queue for the data splitter
	 */
	static final LinkedBlockingQueue<Work> splitterWorkQueue = new LinkedBlockingQueue<Work>();
	
	private static final class Worker extends Thread {
		private final LinkedBlockingQueue<Work> workQueue;
		
		public Worker(final LinkedBlockingQueue<Work> queue){
			super("lia.monitor.Store.Cache.Worker");
			setDaemon(true);
			
			this.workQueue = queue;
		}
		
		@Override
		public void run(){
			while (true){
				Work work;
				
				try {
					work = workQueue.take();
					
					setName("lia.monitor.Store.Cache.Worker: "+work.work.toString());
					
					try{
						work.work.run();
					}
					finally{
						work.resultsQueue.add(work.work);
					}
					
					setName("lia.monitor.Store.Cache.Worker: IDLE");
				}
				catch (Throwable t) {
					System.err.println("Work produced error : "+t);
					t.printStackTrace();
				}
			}
		}
	}
	
	/**
	 * All workers that were created
	 */
	static final ArrayList<Worker> workerThreads;
	
	/**
	 * The splitter threads
	 */
	static final ArrayList<Worker> splitterWorkerThreads;
	
	static{
		final int iCores = Runtime.getRuntime().availableProcessors();
		
		int iCPUs = AppConfig.geti("lia.Monitor.Store.Cache.executors", iCores);
		
		int iSplitterCPUs = AppConfig.geti("lia.Monitor.Store.Cache.splitter_executors", iCores);
		
		logger.log(Level.INFO, "lia.Monitor.Store.Cache.executors="+iCPUs);
		logger.log(Level.INFO, "lia.Monitor.Store.Cache.splitter_executors="+iCPUs);
		
		if (iCPUs<=1){
			workerThreads = null;
		}
		else{
			workerThreads = new ArrayList<Worker>(iCPUs);
			
			for (int i=0; i<iCPUs; i++){
				final Worker w = new Worker(cacheWorkQueue);
				
				workerThreads.add(w);
				w.start();
			}
		}
		
		if (iSplitterCPUs<=1){
			splitterWorkerThreads = null;
		}
		else{
			splitterWorkerThreads = new ArrayList<Worker>(iSplitterCPUs);
			
			for (int i=0; i<iSplitterCPUs; i++){
				final Worker w = new Worker(splitterWorkQueue);
				
				splitterWorkerThreads.add(w);
				w.start();
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	private static final void filterToDS(final Map<String, ?> hm, final String sID, final DataSplitter ds, final boolean bFilterByTime, final monPredicate pred){
		final Pattern p = Pattern.compile("^" + sID + "$");

		String sLargestPart = "";

		final String[] parts = sID.split("\\.(\\+|\\*)");

		for (int i = 0; i < parts.length; i++) {
			if (parts[i].length() > sLargestPart.length())
				sLargestPart = parts[i];
		}
		
		if (hm.size()<200 || workerThreads==null){
			//System.err.println("Working singlethreaded");
			
			final Iterator<?> it = hm.entrySet().iterator();

			while (it.hasNext()) {
				final Map.Entry<String, ?> entry = (Map.Entry<String, ?>) it.next();

				filterData(entry.getKey(), entry.getValue(), sLargestPart, bFilterByTime, p, pred, ds);
			}
			
			return;
		}
		
		// it's worth doing it on threads
		final ArrayList<String> keys = new ArrayList<String>(hm.size());
		final ArrayList<Object> values = new ArrayList<Object>(hm.size());
		
		final Iterator<?> it = hm.entrySet().iterator();
		
		while (it.hasNext()){
			final Map.Entry<String, ?> entry = (Map.Entry<String, ?>) it.next();
			keys.add(entry.getKey());
			values.add(entry.getValue());
		}
		
		final int iChunks = workerThreads.size();
		
		final int iChunkSize = (keys.size() / iChunks) + 1;
		
		//System.err.println("Parallel "+iChunks+" chunks ("+iChunkSize+" elements each) on "+iCPUs+" cpus");
		
		final LinkedBlockingQueue<Runnable> results = new LinkedBlockingQueue<Runnable>();
		
		for (int i=0; i<iChunks; i++){
			final FilterResult fr = new FilterResult(keys, values, i*iChunkSize, iChunkSize, sLargestPart, bFilterByTime, pred, p);

			final Work w = new Work(fr, results);
			
			cacheWorkQueue.add(w);
		}
		
		for (int i=0; i<iChunks; i++){
			try {
				final FilterResult fr = (FilterResult) results.take();
				
				ds.add(fr.getResult(), -1);
			}
			catch (InterruptedException e) {
				// ignore
			}
		}
	}

	/**
	 * Get the last received value that matches a predicate. If multiple series
	 * match this predicate, only one of them is returned, without any guarantees of consistency
	 * between calls.
	 * 
	 * @param pred filter
	 * @return the object extracted from the cache, maybe null if no value was found.
	 */
	public static final Object getLastValue(final monPredicate pred) {
		return getObjectFromHash(hmCache, pred, readLock, false);
	}

	/**
	 * Get the last received values that match a predicate. 
	 * 
	 * @param pred filter
	 * @return a Vector with the values
	 */
	public static final Vector<Object> getLastValues(final monPredicate pred) {
		return getObjectsFromHash(hmCache, pred, readLock, false);
	}

	/**
	 * Get a dump of all cached values.
	 * 
	 * @return an ArrayList with all the cached objects
	 */
	public static final ArrayList<Object> getLastValues() {
		readLock.lock();
		
		try{
			return new ArrayList<Object>(hmCache.values());
		}
		finally{
			readLock.unlock();
		}
	}
	
	private static Map<String, Object> snapshot = null;
	private static long lSnapshotTimestamp = 0;
	
	/**
	 * Get a snapshot of the cache
	 * 
	 * @return snapshot
	 * @see #getDataSplitter(Map, monPredicate, Object, boolean)
	 * @see #getDataSplitter(Map, monPredicate[], Object, boolean)
	 * @see #getObjectFromHash(Map, monPredicate, Object, boolean)
	 * @see #getObjectsFromHash(Map, monPredicate, Object, boolean)
	 */
	public static final Map<String, Object> getSnapshot(){
		final long lNow = System.currentTimeMillis();
		
		Map<String, Object> ret = snapshot;
				
		if (ret!=null && (lNow - lSnapshotTimestamp < SNAPSHOT_MAX_LIFETIME))
			return ret;
		
		readLock.lock();
		
		try{
			ret = snapshot = new HashMap<String, Object>(hmCache);
			lSnapshotTimestamp = lNow;
		}
		finally{
			readLock.unlock();
		}
		
		return ret;
	}
	
	private static long lDeletedEntries = 0;

	/**
	 * Cache cleanup / removal of expired entries
	 */
	static final void cleanup() {
		writeLock.lock();
		
		snapshot = null;
		
		try{
			try {
				final long lDel = NTPDate.currentTimeMillis() - lRecentData;

				final Iterator<String> it = hmCache.keySet().iterator();

				while (it.hasNext())
					if (getResultTime(hmCache.get(it.next())) < lDel){
						lDeletedEntries++;
						it.remove();
					}
			} catch (Exception e) {
				System.err.println("exception in cleanup : " + e + " : " + e.getMessage());
				e.printStackTrace();
			}
			
			if (lDeletedEntries > 50000 && lDeletedEntries > hmCache.size()*10L){
				hmCache = new HashMap<String, Object>(hmCache);
				lDeletedEntries = 0;
			}
		}
		finally{
			writeLock.unlock();
		}
	}

	/**
	 * For any type of Result-like object, get the value of the time field.
	 * 
	 * @param o an Result, eResult, ExtResult or Gresult instance
	 * @return the time field, or -1 if the object is not of one of the accepted types
	 */
	public static final long getResultTime(final Object o) {
		if (o == null)
			return -1;

		if (o instanceof Result)
			return ((Result) o).time;

		if (o instanceof eResult)
			return ((eResult) o).time;

		if (o instanceof ExtResult)
			return ((ExtResult) o).time;

		if (o instanceof Gresult)
			return ((Gresult) o).time;

		return -1;
	}

	/**
	 * Async cleanup operation
	 * 
	 * @author costing
	 */
	static final class CacheCleanupTask implements Runnable {

		/**
		 * Periodic action, to clean the cache of expired values
		 */
		@Override
		public void run() {
		    try {
	            cleanup();
		    }catch(Throwable t) {
		        logger.log(Level.WARNING, " [ CacheCleanupTask ] got exception: ", t);
		    }
		}

	}

	/**
	 * Get the last known double value for a given predicate. If the predicate does not define time constraints
	 * or they are (-1, -1) then do not apply any time restrictions. If the predicate defines tmin and/or tmax
	 * then also filter the values by the time constraint.
	 * 
	 * @param sPred
	 * @return last known value, or null if it is not known
	 */
	public static final Double getDoubleValue(final String sPred){
		final monPredicate pred = Formatare.toPred(sPred);
		
		Collection<?> c = getLastValues(pred);
		
		if (pred.tmin!=-1 || pred.tmax!=-1)
			c = filterByTime(c, pred);

		final Iterator<?> it = c.iterator();
		
		while (it.hasNext()){
			final Object o = it.next();
		
			if (o instanceof Result)
				return Double.valueOf(((Result) o).param[0]);
		}
		
		return null;
	}
	
	/**
	 * Get the last known object value for a given predicate. If the predicate does not define time constraints
	 * or they are (-1, -1) then do not apply any time restrictions. If the predicate defines tmin and/or tmax
	 * then also filter the values by the time constraint.
	 * 
	 * @param sPred
	 * @return last known value, or null if it is not known
	 */
	public static final Object getObjectValue(final String sPred){
		final monPredicate pred = Formatare.toPred(sPred);
		
		Collection<?> c = getLastValues(pred);
		
		if (pred.tmin!=-1 || pred.tmax!=-1)
			c = filterByTime(c, pred);

		final Iterator<?> it = c.iterator();
		
		while (it.hasNext()){
			final Object o = it.next();
		
			if (o instanceof eResult)
				return ((eResult) o).param[0];
		}
		
		return null;		
	}
}
