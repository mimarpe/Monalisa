package lia.Monitor.modules;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Map;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

import lia.Monitor.monitor.MNode;
import lia.Monitor.monitor.MonModuleInfo;
import lia.Monitor.monitor.Result;
import lia.util.StringFactory;
import lia.util.ntp.NTPDate;

/**
 * @author ML
 */
public class monXrootd extends monGenericUDP {
	private static final long serialVersionUID = 1L;

	/** Logger used by this class */
    private static final transient Logger logger = Logger.getLogger("lia.Monitor.modules.monXrootd");

    /** if not receiving data from one xrd server, after how much time we should remove it */
    private long XRDSERVER_EXPIRE = 2 * 60 * 60 * 1000; 	// 2 hours by default
    
    /** if not receiving data from one DictInfo entry, after how much time we should remove it  */
    private long DICTINFO_EXPIRE = 1 * 60 * 60 * 1000;		// 1 hour by default
    
    static final public String MODULE_NAME = "monXrootd";
    
	static final int XROOTD_MON_OPEN   = 0x80;
	static final int XROOTD_MON_APPID  = 0xa0;
	static final int XROOTD_MON_CLOSE  = 0xc0;
	static final int XROOTD_MON_DISC   = 0xd0;
	static final int XROOTD_MON_WINDOW = 0xe0;
	static final int XROOTD_MON_RWREQ  = 0x80; // and-ed with type => 0
	
	final Hashtable xrdServers = new Hashtable();	// xrootd servers that send data to this module
	long lastDoProcessTime; 	// when was called doProcess last time

	static final String [] transfResTypes = new String[] {"transf_rd_mbytes", "transf_wr_mbytes", "transf_client_ip", "transf_speed"};
	
	static final class XrdServerInfo {
		final String host;		// server's hostname; also key in the xrdServers hashtable
		long lastUpdateTime;	// when I received info about this object
		
		// summarized values
		double rMBytes;		// total MBytes read since last report 		
		double wMBytes;		// total MBytes written since last report
		double rFiles;		// nr of files that were read since last report
		double wFiles;		// nr of files that were written since last report
		double nrOpenFiles;	// number of currently open files
		double nrClients;	// number of currently connected clients
		
		// the hash with connected users and opened files on this server  
		final Hashtable dictMap;	// dictionary map holding DictInfo's
		
		final Map dictUserMap;	// <String username, DictInfo info>
		
		// transfer results that will be reported on doProcess
		final Hashtable transfResults; // contains Result's
		
		public XrdServerInfo(final String hostName){
			this.host = hostName;
			dictMap = new Hashtable();
			dictUserMap = new Hashtable();
			transfResults = new Hashtable();
		}
	}
	
	static final int DI_TYPE_PATH    = 1;	// dictid mapping to a user/path combination
	static final int DI_TYPE_APPINFO = 2;	// dictid mapping to a session user/information combination
	static final int DI_TYPE_LOGIN   = 3;	// dictid mapping to the user login name

	static final class DictInfo {
		long lastUpdateTime;	// when I received info about this object
		int type;				// one of the DI_TYPE_... constants
		String key;				// the key (dictID/stod)
		String user;			// the user for this information
		double userIP;			// user's IP, stored as a double value
		String info;			// information string
		
		public String toString(){
			return "DI["+key+"]="+user+"/"+info;
		}
	}
	
	public monXrootd(){
		super(MODULE_NAME);
		
        // read and written MB rates are per second
		resTypes = new String [] {"srv_conn_clients", "srv_open_files", "srv_rd_mbytes", "srv_wr_mbytes", "srv_rd_files", "srv_wr_files"};
        lastDoProcessTime = NTPDate.currentTimeMillis();
        gPort = 9930;			// default port for Xrootd monitoring UDPs
	}
	
	public MonModuleInfo init(final MNode node, final String arg) {
        this.Node = node;
        init_args(arg);
        info = new MonModuleInfo();
        
		logger.log(Level.INFO, "Initializing with: ListenPort="+gPort+" XrdServerExpire="+XRDSERVER_EXPIRE/1000
				+" DictInfoExpire="+DICTINFO_EXPIRE/1000);

        try {
            udpLS = new GenericUDPListener(gPort, this, null);
        } catch (Throwable tt) {
            logger.log(Level.WARNING, " Cannot create UDPListener !", tt);
        }
        
        isRepetitive = true;
        
        info.ResTypes = resTypes;
        info.name = MODULE_NAME;
		OsName = "linux";

        return info;
	}
	
    void init_args(final String args) {
    	
    	String list = args;
    	
        if (list == null || list.length() == 0) return;
        if(list.startsWith("\""))
        	list = list.substring(1);
        if(list.endsWith("\"") && list.length() > 0)
        	list = list.substring(0, list.length()-1);
        String params[] = list.split("(\\s)*,(\\s)*");
        if (params == null || params.length == 0) return;
        
        for (int i=0; i<params.length; i++) {
            int itmp = params[i].indexOf("ListenPort");
            if (itmp != -1) {
                String tmp = params[i].substring(itmp+"ListenPort".length()).trim();
                int iq = tmp.indexOf("=");
                String port = tmp.substring(iq+1).trim();
                try {
                    gPort = Integer.valueOf(port).intValue();
                } catch(Throwable tt){
                	// ignore
                }
                continue;
            }
            itmp = params[i].indexOf("XrdServerExpire");
            if(itmp != -1){
                String tmp = params[i].substring(itmp+"XrdServerExpire".length()).trim();
                int iq = tmp.indexOf("=");
                String timeout = tmp.substring(iq+1).trim();
                try {
                    XRDSERVER_EXPIRE = Long.valueOf(timeout).longValue() * 1000; // it's in seconds
                } catch(Throwable tt){
                	// ignore
                }
                continue;
            }
            itmp = params[i].indexOf("DictInfoExpire");
            if(itmp != -1){
                String tmp = params[i].substring(itmp+"DictInfoExpire".length()).trim();
                int iq = tmp.indexOf("=");
                String timeout = tmp.substring(iq+1).trim();
                try {
                    DICTINFO_EXPIRE = Long.valueOf(timeout).longValue() * 1000; // it's in seconds
                } catch(Throwable tt){
                	// ignore
                }
                continue;
            }
        }
    }
	
	/**
	 * Helper class that provides functions to read from a data buffer bytes, chars, 
	 * int16s, int32s, uint32, string until a separator, string of given length.
	 * 
	 * Note that it expects that the received data is in network byte order, so for
	 * int16, int32 and uint32 it will do a ntoh(..). 
	 */
	static final class ByteReader {
		final int len;	// length of the data buffer
		int pos; 	// current position in buffer
		final byte[] data; // data buffer
		
		/** initialized with the buffer and its length */
		public ByteReader(final int length, final byte[] bytes){
			this.len = length;
			this.data = bytes;
			this.pos = 0;
		}
		
		/** has more bytes to read */
		public boolean hasMore(){
			return pos < len;
		}
		
		/** read the next byte */
		public byte readByte(){
			return data[pos++];
		}
		
		/** read an unsigned byte */
		public int readUByte(){
			int ff = 0xff;
			byte b = readByte();
			return ff & b;
		}
		
		/** read the next char (1 byte) */
		public char readChar(){
			return (char) data[pos++];
		}
		
		/** read the next int16 */
		public int readInt16(){
			final int b1 = readUByte();
			final int b2 = readUByte();
			return (b1 << 8) | b2;
		}
		
		/** read the next int32 */
		public int readInt32(){
			final int w1 = readInt16();
			final int w2 = readInt16();
			return (w1 << 16) | w2;
		}
		
		/** read the next unsigned int32 */
		public long readUInt32(){
			final long u1 = readInt16();
			final long u2 = readInt16();
			return (u1 << 16) | u2;
		}
		
		/** read the next int64 */
		public long readInt64(){
			final long l1 = readUInt32();
			final long l2 = readUInt32();
			return (l1 << 32) | l2;
		}
		
		/** push back one byte from the buffer */
		public void pushBack(){
			if(pos > 0){
				pos--;
			}
		}
		
		/** read string until reaching the given separator, or end of buffer.
		 * The output doesn't include the separator */
		public String readStringToSep(final char sep){
			final StringBuilder sb = new StringBuilder();
			while(hasMore()){
				final char c = readChar();
				if(c == sep)
					break;
				sb.append(c);
			}
			return sb.toString();
		}
		
		/** read string of given length, or until end of buffer. */
		public String readStringLen(final int charNo){
			final StringBuilder sb = new StringBuilder();
			for(int i=0; (i<charNo) && hasMore(); i++){
				sb.append(readChar());
			}
			return sb.toString();
		}
		
		/** skip the next given number of chars from the buffer */
		public void skipChars(final int charNo){
			for(int i=0; (i<charNo) && hasMore(); i++){
				readChar();
			}
		}
	}

	/** add the given data to the global data structures; data is reported in MB */
	final void addRWsums(final int rRshift, final int wRshift, final long rTot, final long wTot, final XrdServerInfo xsi, final String key){
			final double read = (rTot << rRshift) / 1024.0 / 1024.0;
			final double written = (wTot << wRshift) / 1024.0 / 1024.0;
			xsi.rMBytes += read;
			xsi.wMBytes += written;
			if(read > 0)
				xsi.rFiles++;
			if(written > 0)
				xsi.wFiles++;
			// we have to build a result for this transfer
			final Result r = new Result(Node.getFarmName(), Node.getClusterName(), xsi.host, MODULE_NAME, transfResTypes);
            r.time = NTPDate.currentTimeMillis();
            r.param[0] = read;
            r.param[1] = written;
            final DictInfo di = (DictInfo) xsi.dictMap.get(key);
            if(di != null){
            	r.param[2] = di.userIP;
            	r.param[3] = 0; // speed. filled at disconnect time
    			xsi.transfResults.put(di.user, r);
            } 
            else{
            	// else we ignore it
                // TODO: change this sometime...
            	logger.log(Level.INFO, "Ignoring Result since din't find the key["+
            			key+"] in the dictMap\n"+r);
            }
	}
	
	static final void touchXSIandDI(final XrdServerInfo xsi, final DictInfo di){
		final long now = NTPDate.currentTimeMillis();
		
		if (xsi!=null)
			xsi.lastUpdateTime = now;
			
		if (di!=null)
			di.lastUpdateTime = now;
	}

	/** update the lastUpdateTime for the given key and the user of that key */
	static final void touchUserForKey(final String key, final XrdServerInfo xsi){
		// update file and user's DictInfo time
		final long now = NTPDate.currentTimeMillis();
		xsi.lastUpdateTime = now;
		
		final DictInfo di = (DictInfo) xsi.dictMap.get(key);
		if(di != null){
			di.lastUpdateTime = now;
			
			/**
			 * findDIforUser would return at most the same DictInfo, so searching for it 
			 * just to update the same field seems redundant
			di = findDIforUser(di.user, xsi);
			if(di != null)
				di.lastUpdateTime = now;
			*/
		}
	}
	
	/** convert the character code to a readable string */
	static final String codeToText(final char c){
		switch (c){
			case 'd':
				return "dictid mapping to a user/path combination";
			case 'i':
				return "dictid mapping to a session user/information combination";
			case 't':
				return "a file or I/O request trace";
			case 'u':
				return "dictid mapping to the user login name";
			default:
				return "UNKNOWN CODE!";
		}
	}

	/** convert the character code to the corresponding int constant */
	static final int codeToType(final char c){
		switch (c){
			case 'd':
				return DI_TYPE_PATH;
			case 'i':
				return DI_TYPE_APPINFO;
			case 'u':
				return DI_TYPE_LOGIN;
			default:
				return 0;
		}
	}
	
	/** search the DictInfo for the given user; if not found, it returns null */
	static final DictInfo findDIforUser(final String user, final XrdServerInfo xsi){
		final DictInfo di = (DictInfo) xsi.dictUserMap.get(user);
		
		if (di!=null && di.type!=DI_TYPE_LOGIN)
			return null;
		
		return di;		
	}
	
	/** convert the user string (such as aliprod.20565:13@lxb1628) to an IP 
	 * (of lxb1628) stored as a double */
	static final double getUserIP(final String user){
		if(user == null)
			return 0;
		int pa = user.indexOf('@');
		if(pa == -1)
			return 0;
		final String host = user.substring(pa+1);
		try{
			final InetAddress ia = InetAddress.getByName(host);
			final byte [] addr = ia.getAddress();
			return ((0xffL & addr[3]) << 24) | ((0xffL & addr[2]) << 16) | ((0xffL & addr[1]) << 8) | (0xffL & addr[0]);
		}catch(UnknownHostException ex){
			if(logger.isLoggable(Level.FINE))
				logger.log(Level.FINE, "Unknown host address for "+user);
			return 0;
		}
	}
	
	/** read a map message */
	static final void readMapMessage(final ByteReader br, final long stod, final int type, final XrdServerInfo xsi){
		final long dictid = br.readUInt32();
		String infoString = br.readStringLen(10000);
		final int idx = infoString.indexOf('\n');
		final String user = (idx != -1 ? infoString.substring(0, idx) : infoString);
		infoString = (idx != -1 ? infoString.substring(idx+1) : null);
		//info = info.replace('\n', ' ');
		if(logger.isLoggable(Level.FINE))
			logger.log(Level.FINE, "Reading Map Message: dictID="+dictid+" user="+user+" info="+infoString);
		final DictInfo di = new DictInfo();
		di.user = StringFactory.get(user);
		di.userIP = getUserIP(user);
		di.info = infoString;
		di.key = ""+dictid+"/"+stod;
		di.type = type;
		xsi.dictMap.put(di.key, di);
		
		if (di.user!=null && di.type == DI_TYPE_LOGIN)
			xsi.dictUserMap.put(di.user, di);
		
		touchXSIandDI(xsi, di);
		
		//touchUserForKey(di.key, xsi);
	}
	
	/** read trace messages */
	final void readTraceMessage(final ByteReader br, final long stod, final XrdServerInfo xsi){
		if(logger.isLoggable(Level.FINE))
			logger.log(Level.FINE, "Reading Trace Message");
		
		while(br.hasMore()){
			final int type = br.readUByte();
			String key;
			DictInfo di;
			switch(type){
			case XROOTD_MON_APPID:	// Application provided marker
				br.skipChars(3); // skip 3 bytes;
				final String appid = br.readStringLen(12);
				for(int i=appid.length(); i<12; i++){
					br.readByte(); // ignore the rest; make sure that we read the full record
				}
				//System.out.println("AppID = " + appid);
				//TODO: don't know how to handle and what to do with it; ignore for now...
				break;
			case XROOTD_MON_CLOSE: // File has been closed
				final int rRshift = br.readUByte();
				final int wRshift = br.readUByte();
				br.skipChars(1);
				final long rTot = br.readUInt32();
				final long wTot = br.readUInt32();
				long dictid = br.readUInt32();
				key = ""+dictid+"/"+stod;
				di = (DictInfo) xsi.dictMap.get(key);
				if(logger.isLoggable(Level.FINE))
					logger.log(Level.FINE, "CLOSE: rShift=" + rRshift + " wShift=" + wRshift + " rTot=" + rTot + " wTot=" + wTot
										 + " dictID=" + dictid + " ref=" + di);
				addRWsums(rRshift, wRshift, rTot, wTot, xsi, key);
				
				touchXSIandDI(xsi, di);
				
				//touchUserForKey(key, xsi);
				di = (DictInfo) xsi.dictMap.remove(key); // removed key
				
				if (di!=null && di.user!=null && di.type==DI_TYPE_LOGIN)
					xsi.dictUserMap.remove(di.user);
				
				break;
			case XROOTD_MON_DISC: // Client has disconnected
				br.skipChars(7);
				final long seconds = br.readUInt32();
				dictid = br.readUInt32();
				key = ""+dictid+"/"+stod;
				di = (DictInfo) xsi.dictMap.get(key);
				if(logger.isLoggable(Level.FINE))
					logger.log(Level.FINE, "DISCONNECT: after "+seconds+" sec dictID="+dictid
										 + " ref=" + di);
				// set the time for the transfer done by this user.
				// the idea is that with xcp, there will be one transfer / user connection
				// so we can use the connection time as the transfer time...
				// TODO: find a better way to do this
				if(di != null && seconds > 0){
					// di refers to the user
					// find all results belonging to this user
					for(Enumeration entk = xsi.transfResults.keys(); entk.hasMoreElements(); ){
						final String user = (String) entk.nextElement();
						final DictInfo udi = findDIforUser(user, xsi);
						if(udi != null && udi.equals(di)){
							final Result r = (Result) xsi.transfResults.get(user);
							r.param[3] = (r.param[0] + r.param[1]) / seconds; // in MB/s							
						}
					}
				}
				di = (DictInfo) xsi.dictMap.remove(key); // removed key
				
				if (di!=null && di.user!=null && di.type==DI_TYPE_LOGIN)
					xsi.dictUserMap.remove(di.user);
				
				break;
			case XROOTD_MON_OPEN: // File has been opened
				br.skipChars(11);
				dictid = br.readUInt32();
				key = ""+dictid+"/"+stod;
				di = (DictInfo) xsi.dictMap.get(key);
				if(logger.isLoggable(Level.FINE))
					logger.log(Level.FINE, "OPEN: dictID="+dictid + " ref=" + di);
				
				touchXSIandDI(xsi, di);
				
				//touchUserForKey(key, xsi);
				break;
			case XROOTD_MON_WINDOW: // Window timing mark
				br.skipChars(7);
				final long lastWend = br.readUInt32();
				final long thisWstart = br.readUInt32();
				if(logger.isLoggable(Level.FINER))
					logger.log(Level.FINER, "WINDOW: lastEND: "+new Date(lastWend * 1000) 
										  + " thisSTART: "+new Date(thisWstart * 1000));
				// TODO: does it worth using this information? 
				break;
			default:
				if((type & XROOTD_MON_RWREQ) == 0){
				  	// Read or write request
					br.pushBack(); // push back the type byte
					final long offset = br.readInt64();
					int rwSize = br.readInt32(); // SIGNED!
					String rwType;
					if(rwSize >= 0){
						rwType = "READ";
					}else{
						rwType = "WRITE";
						rwSize *= -1;
					}
					dictid = br.readUInt32();
					if(logger.isLoggable(Level.FINEST))
						logger.log(Level.FINEST, rwType+": offset=" + offset + " length=" + rwSize
							+" dictID=" + dictid + " ref=" + xsi.dictMap.get(""+dictid+"/"+stod));
					// TODO: find a better way to use this data
					// because for now, it's unusable, as it is
				}else{
					logger.log(Level.INFO, "UNKNOWN Trace message!");
					br.skipChars(7);
				}
			}
		}
	}
	
	public void notifyData(final int len, final byte[] data, final InetAddress source) {
		if(logger.isLoggable(Level.FINE))
			logger.log(Level.FINE, "================================\n" +
					"Received packet of size "+len+" from "
					+source.getCanonicalHostName()+" ["+source.getHostAddress()+"] at "+new Date());

		final ByteReader br = new ByteReader(len, data);
		final char code = br.readChar();
		final int pseq = br.readUByte();
		final int plen = br.readInt16();
		final long stod = br.readUInt32();
		if(logger.isLoggable(Level.FINER))
			logger.log(Level.FINER, "Reading Header:"
						+ " code=" + code + " -> Follows "+codeToText(code)
						+ " pseq=" + pseq
						+ " plen=" + plen
						+ " stod=" + stod + "=" + new Date(1000 * stod));
//		try{
//			String fName = "/tmp/monXrootd."+pseq;
//			FileOutputStream file = new FileOutputStream(fName);
//			file.write(data, 0, len);
//			file.close();
//			System.out.println("Wrote binary packet in "+fName+"\n");
//		}catch(IOException ex){
//			ex.printStackTrace();
//		}
		final String serverHost = source.getCanonicalHostName();
		XrdServerInfo xsi = (XrdServerInfo) xrdServers.get(serverHost);
		if(xsi == null){
			xsi = new XrdServerInfo(serverHost);
			xrdServers.put(serverHost, xsi);
		}
		synchronized (xsi) {
			if(code == 't'){
				readTraceMessage(br, stod, xsi);
			}else{
				readMapMessage(br, stod, codeToType(code), xsi);
			}
		}
		if(logger.isLoggable(Level.FINE))
			logger.log(Level.FINE, "^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^");
	}

	/** build a result vector with the current data */
	public Object doProcess() throws Exception {
		final Vector vrez = new Vector();
		
		final long now = NTPDate.currentTimeMillis();
//		long timeInterval = (now - lastDoProcessTime) / 1000;
		
		for(Enumeration ksen = xrdServers.keys(); ksen.hasMoreElements(); ){
			final String host = (String) ksen.nextElement();
			final XrdServerInfo xsi = (XrdServerInfo) xrdServers.get(host);
			synchronized (xsi) {
				// add all transfer results
				vrez.addAll(xsi.transfResults.values());
				xsi.transfResults.clear();
				// summarize the server's 
				xsi.nrClients = 0;
				xsi.nrOpenFiles = 0;
				if(now - xsi.lastUpdateTime > XRDSERVER_EXPIRE){
					xrdServers.remove(host);
					// remove this host from config
					if(logger.isLoggable(Level.FINE))
						logger.log(Level.FINE, "Info about Xrootd server ["+host+"] expired! Removing it..");
					Result r = new Result(Node.getFarmName(), Node.getClusterName(), host, MODULE_NAME, new String [] {});
	                r.time = now;
	                vrez.add(r);
				}else{
					for(Enumeration kdien = xsi.dictMap.keys(); kdien.hasMoreElements(); ){
						final String kdi = (String) kdien.nextElement();
						final DictInfo di = (DictInfo) xsi.dictMap.get(kdi);
						if(now - di.lastUpdateTime > DICTINFO_EXPIRE){
							xsi.dictMap.remove(kdi);
							
							if (di.user!=null && di.type==DI_TYPE_LOGIN)
								xsi.dictUserMap.remove(di.user);
						}
						else{
							if(di.type == DI_TYPE_LOGIN)
								xsi.nrClients++;
							if(di.type == DI_TYPE_PATH)
								xsi.nrOpenFiles++;
						}
					}
					final Result r = new Result(Node.getFarmName(), Node.getClusterName(), host, MODULE_NAME, resTypes);
	                r.time = now;
	                r.param[0] = xsi.nrClients;
	                r.param[1] = xsi.nrOpenFiles;
	                r.param[2] = xsi.rMBytes; // / timeInterval
	                r.param[3] = xsi.wMBytes; // / timeInterval
	                r.param[4] = xsi.rFiles;  // / timeInterval
	                r.param[5] = xsi.wFiles;  // / timeInterval
					vrez.add(r);
				}
				xsi.rMBytes = xsi.wMBytes = 0;
				xsi.rFiles = xsi.wFiles = 0;				
			}
		}
		lastDoProcessTime = now;
		return vrez;
	}
	
	public static void main(String[] args) {
		String host = "localhost" ; //args[0] ;
	
        monXrootd aa = new monXrootd();
        String ad = null ;
        try {
            ad = InetAddress.getByName( host ).getHostAddress();
        } catch ( Exception e ) {
            System.out.println ( " Can not get ip for node " + e );
            System.exit(-1);
        }
        //Logger.getLogger("lia.Monitor.modules.monXrootd").setLevel(Level.FINEST);
//        System.out.println("lev="+Logger.getLogger("lia.Monitor.modules.monXrootd").getLevel());
        
        aa.init( new MNode (host ,ad,  null, null), "\"ListenPort=9932,XrdServerExpire=300,DictInfoExpire=150\"");
        
        for(;;) {
            try { 
                Object bb = aa.doProcess();
                try {
                    Thread.sleep(1 * 1000);
                } catch ( Exception e1 ){
                	// ignore
                }
                
                if ( bb != null && bb instanceof Vector ){
                    Vector res = (Vector)bb;
                    if ( res.size() > 0 ) {
                        System.out.println("Got a Vector with " + res.size() +" results");
                        for ( int i = 0; i < res.size(); i++) {
                            System.out.println(" { "+ i + " } >>> " + res.elementAt(i));
                        }
                    }
                }
            } catch ( Exception e ) {
            	// ignore
            }    
        }   
	}
}
