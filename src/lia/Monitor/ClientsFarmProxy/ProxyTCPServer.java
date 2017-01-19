/*
 * $Id: ProxyTCPServer.java 6865 2010-10-10 10:03:16Z ramiro $
 */
package lia.Monitor.ClientsFarmProxy;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.StringTokenizer;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import lia.Monitor.monitor.AppConfig;

/**
 * Base TCP Server for both Clients and ML Services 
 * 
 * @author mickyt,ramiro
 */
public class ProxyTCPServer extends Thread {

    /** Logger used by this class */
    private static final transient Logger logger = Logger.getLogger(ProxyTCPServer.class.getName());
    private static final ProxyTCPServer _theInstance;
    // private ServerSocket listenSocket = null;
    private boolean hasToRun = true;
    private final int[] ports;

    private final static Executor executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);

    static {
        ProxyTCPServer tmpServer = null;

        try {
            tmpServer = new ProxyTCPServer();
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "Cannot instantiate ProxyTCPServer", t);
            System.exit(-1);
        }

        _theInstance = tmpServer;
    }

    public static ProxyTCPServer getInstance() {
        return _theInstance;
    }

    public int getPort() {
        return ports[0];
    }

    public int[] getPorts() {
        return ports;
    } // getPorts

    private ProxyTCPServer() throws Exception {

        ServerSocket listenSocket = null;
        String s = AppConfig.getProperty("lia.Monitor.ClientsFarmProxy.ProxyPort", "6001");

        final ArrayList<ServerSocket> listenSockets = new ArrayList<ServerSocket>();
        StringTokenizer st = new StringTokenizer(s, " ");
        while (st.hasMoreTokens()) {
            try {
                String t = st.nextToken();
                t = t.trim();
                int bindPort = (Integer.valueOf(t)).intValue();
                listenSocket = new ServerSocket(bindPort, 50);
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "Cannot create ServerSocket", t);
                throw new Exception(t);
            } // try - catch

            new ServerPort(listenSocket).start();
            listenSockets.add(listenSocket);

        } // while


        if (listenSockets.size() == 0) {
            logger.log(Level.SEVERE, "Proxy ServerSocket not bound!! will exit");
            System.exit(1);
        } // if

        // set all local ports
        ports = new int[listenSockets.size()];
        int i = 0;

        for (ServerSocket ss : listenSockets) {
            ports[i] = ss.getLocalPort();
            i++;
        } // for

    }

    class ServerPort extends Thread {

        private final ServerSocket listenSocket;
        private final int listenPort;

        public ServerPort(ServerSocket listenSocket) {
            if (listenSocket == null) {
                throw new NullPointerException("Listen socket cannot be null");
            }
            this.listenSocket = listenSocket;
            this.listenPort = this.listenSocket.getLocalPort();
            this.setName("(ML) ProxyTCPServer listen instance on port " + listenPort);

        }

        public void run() {

            logger.log(Level.INFO, "ProxyTCPServer on port " + listenPort + " started");

            while (hasToRun) {
                try {
                    final Socket s = listenSocket.accept();
                    executor.execute(new Runnable() {
                        public void run() {
                            try {
                                //TODO
                                //add some verify/accounting in the future
                                ProxyTCPWorker.newInstance(s);
                            }catch(Throwable t) {
                                logger.log(Level.WARNING, " ProxyTCPServer on port " + listenPort + " got exception on newConnection ( " + s + " ). will close the socket. Cause: ", t);
                                if (s != null) {
                                    try {
                                        s.close();
                                    }catch(Throwable ignore) {}
                                }
                            }
                        }
                    });
                    yield();
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "ProxyTCPServerThread which listens on port " + listenPort + " got exception in main loop", t);
                }// try - catch
            } // while
        } // run
    } // ServerPort
}

