package lia.web.servlets.web;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import lazyj.Format;
import lia.Monitor.Store.Cache;
import lia.Monitor.monitor.Result;
import lia.Monitor.monitor.eResult;
import lia.Monitor.monitor.monPredicate;
import lia.web.utils.ServletExtension;
import lia.web.utils.ThreadedPage;

/**
 * @author costing
 * @since Oct 1, 2010
 */
public class Rest extends ServletExtension{
	/**
	 * Logging facility
	 */
	static final transient Logger logger = Logger.getLogger(Rest.class.getCanonicalName());
	
	private static final long serialVersionUID = 1L;
	
	private String sFarm;
	private String sCluster;
	private String sNode;
	private long tmin = -1;
	private long tmax = -1;
	private String sParameter;
	private String sCondition;
	private boolean timeSet = false;
	
	private long lPageStart = 0;
	
	private void parseURL(final String sPath){
		sFarm = sCluster = sNode = sParameter = sCondition = null;
		
		tmin = tmax = -1;
		timeSet = false;
		
		final StringTokenizer st = new StringTokenizer(sPath, "/");
		
		final int cnt = st.countTokens();
		
		if (cnt>=1)
			sFarm = st.nextToken();
		
		if (cnt>=2)
			sCluster = st.nextToken();
		
		if (cnt>=3)
			sNode = st.nextToken();

		if (cnt>=5){
			// are the two parameters numeric ?
			
			final String s4 = st.nextToken();
			final String s5 = st.nextToken();
			
			try{
				final long t1 = Long.parseLong(s4);
				final long t2 = Long.parseLong(s5);
				
				tmin = t1;
				tmax = t2;
				
				timeSet = (t1!=0 && t1!=-1) || (t2!=0 && t2!=-1);
			}
			catch (NumberFormatException nfe){
				// ignore
				sParameter = s4;
				sCondition = s5;
			}
		}
		
		if (st.hasMoreTokens())
			sParameter = st.nextToken();
		
		if (st.hasMoreTokens())
			sCondition = st.nextToken();
	}
	
	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "Farm="+sFarm+"\nCluster="+sCluster+"\nNode="+sNode+"\nParameter="+sParameter+"\ntMin="+tmin+"\ntMax="+tmax+"\ntimeSet="+timeSet+"\nCondition="+sCondition;
	}
	
	private boolean setFields(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
		request = req;
		response = resp;
		
		lPageStart = System.currentTimeMillis();
		
		if (!ThreadedPage.acceptRequest(req, resp))
			return false;
		
		osOut = response.getOutputStream();
		pwOut = new PrintWriter(new OutputStreamWriter(osOut));
			
		parseURL(request.getPathInfo());
		
		return true;
	}
	
	private void endPage(){
		Utils.logRequest("Rest"+request.getPathInfo(), (int) (System.currentTimeMillis() - lPageStart), request, false);
	}
	
	private String getContentType(){
		String sContentType = "text/plain";
		
		String sAccept = request.getHeader("Accept");
		
		if (sAccept!=null){
			sAccept = sAccept.toLowerCase();
			
			if (sAccept.indexOf("application/json")>=0 || sAccept.indexOf("text/json")>=0)
				sContentType = "application/json";
			else
			if (sAccept.indexOf("text/html")>=0)
				sContentType = "text/html";
			else
			if (sAccept.indexOf("text/xml")>=0)
				sContentType = "text/xml";
			else
			if (sAccept.indexOf("text/csv")>=0)
				sContentType = "text/csv";
		}
		
		return sContentType;
	}
	
	/**
	 * Convert any ML object (Result, eResult, List of them) to a fixed form
	 * 
	 * @param o
	 * @param verify condition that the values have to meet (can be null)
	 * @return the list
	 */
	public static List<Map<String, String>> resultsToList(final Object o, final ConditionVerifier verify){
		if (o == null)
			return null;
		
		final List<Map<String, String>> ret = new ArrayList<Map<String, String>>();
		
		if (o instanceof Result){
			final Result r = (Result) o;

			for (int i=0; i<r.param.length; i++){
				if (verify!=null && !verify.matches(r.param[i]))
					continue;
				
				final Map<String, String> m = new LinkedHashMap<String, String>();
			
				m.put("Farm", r.FarmName);
				m.put("Cluster", r.ClusterName);
				m.put("Node", r.NodeName);
				m.put("Parameter", r.param_name[i]);
				m.put("Value", Utils.showDouble(r.param[i]));
				m.put("Timestamp", String.valueOf(r.time));
				
				ret.add(m);
			}
		}
		else
		if (o instanceof eResult){
			final eResult r = (eResult) o;

			for (int i=0; i<r.param.length; i++){
				if (verify!=null && !verify.matches(r.param[i]))
					continue;

				final Map<String, String> m = new LinkedHashMap<String, String>();
			
				m.put("Farm", r.FarmName);
				m.put("Cluster", r.ClusterName);
				m.put("Node", r.NodeName);
				m.put("Parameter", r.param_name[i]);
				m.put("Value", r.param[i].toString());
				m.put("Timestamp", String.valueOf(r.time));
				
				ret.add(m);
			}
		}
		else
		if (o instanceof Collection<?>){
			final Collection<?> c = (Collection<?>) o;
			
			for (Object o2: c){
				final List<Map<String, String>> temp = resultsToList(o2, verify);
				
				if (temp!=null)
					ret.addAll(temp);
			}
		}
		
		return ret;
	}
	
	/**
	 * Convert to CSV
	 * 
	 * @param list
	 * @return CSV output
	 */
	public static String collectionToCSV(final Collection<Map<String,String>> list){
		boolean bFirst = true;
		
		final StringBuilder sb = new StringBuilder();
		
		for (Map<String, String> m: list){
			if (bFirst){
				sb.append('#');
				
				for (String k: m.keySet()){
					if (sb.length()>1)
						sb.append('|');
					
					sb.append(stats.escCSV(k));
				}
				sb.append('\n');
				
				bFirst = false;
			}
			
			boolean b = true;
			
			for (final String v: m.values()){
				if (!b)
					sb.append('|');
				else
					b = false;
				
				sb.append(stats.escCSV(v));
			}
			
			sb.append('\n');
		}
		
		return sb.toString();
	}
	
	/**
	 * Convert to plain text
	 * 
	 * @param list
	 * @return plain text
	 */
	public static String collectionToPlainText(final Collection<Map<String,String>> list){
		final StringBuilder sb = new StringBuilder();
		
		for (Map<String, String> m: list){
			boolean b = true;
			
			for (final String v: m.values()){
				if (!b)
					sb.append('/');
				else
					b = false;
				
				sb.append(v);
			}
			
			sb.append('\n');
		}
				
		return sb.toString();
	}
	
	/**
	 * Convert to JSON
	 * 
	 * @param list
	 * @return JSON output
	 */
	public static String collectionToJSON(final Collection<Map<String, String>> list){
		final StringBuilder sb = new StringBuilder("{\"results\":[");
		
		boolean b = true;
		
		for (final Map<String, String> m: list){
			if (b)
				b = false;
			else
				sb.append(',');
			
			sb.append("\n{");
		
			boolean b2 = true;
			
			for (final Map.Entry<String, String> me: m.entrySet()){
				if (b2)
					b2 = false;
				else
					sb.append(',');
				
				sb.append('"').append(Format.escJS(me.getKey())).append("\":\"").append(Format.escJS(me.getValue())).append('"');
			}
			
			sb.append("}");
		}
		
		sb.append("\n]}");
		
		return sb.toString();
	}
	
	/**
	 * Convert to XML
	 * 
	 * @param list
	 * @return XML output
	 */
	public static String collectionToXML(final Collection<Map<String, String>> list){
		final StringBuilder sb = new StringBuilder("<results>");
		
		for (final Map<String, String> m: list){			
			sb.append("\n<result");
		
			for (final Map.Entry<String, String> me: m.entrySet()){
				sb.append(' ').append(me.getKey()).append("=\"").append(Format.escHtml(me.getValue())).append('"');
			}
			
			sb.append("/>");
		}
		
		sb.append("\n</results>");
		
		return sb.toString();
	}
	
	/**
	 * Convert to HTML
	 * 
	 * @param list
	 * @return HTML output
	 */
	public static String collectionToHTML(final Collection<Map<String,String>> list){
		boolean bFirst = true;
		
		final StringBuilder sb = new StringBuilder("<table>\n");
		
		for (final Map<String, String> m: list){
			if (bFirst){
				sb.append("<thead>\n<tr>\n");
				
				for (final String k: m.keySet()){
					sb.append("<th>").append(Format.escHtml(k)).append("</th>\n");
				}
				
				sb.append("</tr>\n</thead>\n<tbody>");
				
				bFirst = false;
			}
			
			sb.append("\n<tr>");
			
			for (final String v: m.values()){
				sb.append("\n<td>").append(Format.escHtml(v)).append("</td>");
			}
			
			sb.append("\n</tr>");
		}
		
		sb.append("\n</tbody>\n</table>");
		
		return sb.toString();
	}
	
	private static final Comparator<Map<String, String>> resultsComparator = new Comparator<Map<String,String>>() {

		@Override
		public int compare(final Map<String, String> o1, final Map<String, String> o2) {
			int diff = o1.get("Farm").compareToIgnoreCase(o2.get("Farm"));
			
			if (diff!=0)
				return diff;
			
			diff = o1.get("Cluster").compareToIgnoreCase(o2.get("Cluster"));
			
			if (diff!=0)
				return diff;
			
			diff = o1.get("Node").compareToIgnoreCase(o2.get("Node"));
			
			if (diff!=0)
				return diff;
			
			diff = o1.get("Parameter").compareToIgnoreCase(o2.get("Parameter"));
			
			return diff;
		}
		
	};
	
	/**
	 * Convert to String some Results
	 * 
	 * @param results
	 * @param sContentType
	 * @param verify condition that the values must meet (can be null)
	 * @return the string, in a format dependent on the content type
	 */
	public static String resultsToString(final List<Object> results, final String sContentType, final ConditionVerifier verify){
		final List<Map<String, String>> resultsMap = resultsToList(results, verify);
		
		Collections.sort(resultsMap, resultsComparator);
		
		return collectionToString(resultsMap, sContentType);
	}
	
	/**
	 * Convert a collection to string
	 * 
	 * @param c
	 * @param sContentType
	 * @return string, in the format indicated by content type
	 */
	public static String collectionToString(final Collection<Map<String, String>> c, final String sContentType){
		if (sContentType.equals("text/csv"))
			return collectionToCSV(c);
		
		if (sContentType.equals("text/plain"))
			return collectionToPlainText(c);
		
		if (sContentType.equals("application/json"))
			return collectionToJSON(c);
		
		if (sContentType.equals("text/xml"))
			return collectionToXML(c);
		
		if (sContentType.equals("text/html"))
			return collectionToHTML(c);
		
		return null;
		
	}
	
	@Override
	protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
		if (!setFields(req, resp))
			return;
		
		final String sContentType = getContentType();
		
		response.setContentType(sContentType);
		
		if (sParameter!=null){
			final monPredicate pred = new monPredicate(sFarm, sCluster, sNode, tmin, tmax, sParameter.split("[|]"), null);
			
			final boolean bFine = logger.isLoggable(Level.FINE);
			//final boolean bFiner = logger.isLoggable(Level.FINER);
			//final boolean bFinest = logger.isLoggable(Level.FINEST);
			
			if (bFine){
				logger.log(Level.FINE, "Parsed request is : "+this.toString());
				logger.log(Level.FINE, "Predicate is : "+pred);
			}
			
			List<Object> results = Cache.getLastValues(pred);
			
			if (bFine){
				logger.log(Level.FINE, "Matching results from cache: "+results.size());
			}
			
			if (timeSet){
				results = Cache.filterByTime(results, pred);
				
				if (bFine){
					logger.log(Level.FINE, "After time filtering I'm left with: "+results.size());
				}
			}
			
			final ConditionVerifier verify = sCondition!=null ? new ConditionVerifier(sCondition) : null;
			
			pwOut.println(resultsToString(results, sContentType, verify));
			pwOut.flush();
			return;
		}
		
		final monPredicate pred = new monPredicate(sFarm==null ? "*" : sFarm, sCluster==null ? "*" : sCluster, sNode==null ? "*" : sNode, tmin, tmax, new String[]{"*"}, null);
		
		List<Object> results = Cache.getLastValues(pred);
		
		if (timeSet){
			results = Cache.filterByTime(results, pred);
		}
		
		final Map<String, Map<String, String>> browse = new TreeMap<String, Map<String, String>>();
	
		for (final Object o: results){
			String sKey = null;

			final Map<String, String> m = new LinkedHashMap<String, String>();
	
			if (o instanceof Result){
				final Result r = (Result) o;
				
				m.put("Farm", r.FarmName);
				sKey = r.FarmName;
				
				if (sFarm!=null){
					m.put("Cluster", r.ClusterName);
					sKey += "/"+r.ClusterName;
				}
				
				if (sCluster!=null){
					m.put("Node", r.NodeName);
					sKey += "/"+r.NodeName;
				}
				
				if (sNode!=null){
					m.put("Parameter", r.param_name[0]);
					sKey += "/"+r.param_name[0];
				}
			}
			else
			if (o instanceof eResult){
				final eResult r = (eResult) o;
				
				m.put("Farm", r.FarmName);
				sKey = r.FarmName;
				
				if (sFarm!=null){
					m.put("Cluster", r.ClusterName);
					sKey += "/"+r.ClusterName;
				}
				
				if (sCluster!=null){
					m.put("Node", r.NodeName);
					sKey += "/"+r.NodeName;
				}
				
				if (sNode!=null){
					m.put("Parameter", r.param_name[0]);
					sKey += "/"+r.param_name[0];
				}
			}
			
			if (sKey!=null && !browse.containsKey(sKey))
				browse.put(sKey, m);
		}
		
		pwOut.println(collectionToString(browse.values(), getContentType()));
		pwOut.flush();
		
		endPage();
	}
	
	@Override
	protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
		if (!setFields(req, resp))
			return;
		
		endPage();
	}
	
	@Override
	protected void doPut(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
		if (!setFields(req, resp))
			return;
		
		endPage();
	}
	
	@Override
	protected void doDelete(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
		if (!setFields(req, resp))
			return;
		
		endPage();
	}
	
	public static void main(final String[] args) {
		Rest rest = new Rest();
		rest.parseURL("GSI/CLUSTER_GSIAF_Nodes/*/-60000/-1/processes|load5/2:");
		
		System.err.println(rest);
		System.err.println(Arrays.toString(rest.sParameter.split("[|]")));
		
		final Vector<Object> v = new Vector<Object>();
		
		final Result r = new Result();
		r.time = System.currentTimeMillis();
		r.FarmName = "f";
		r.ClusterName = "c";
		r.NodeName = "n";
		r.addSet("p1", 1);
		r.addSet("p2", 2);
		
		v.add(r);
		
		System.err.println(resultsToString(v, "application/json", null));
	}
}
