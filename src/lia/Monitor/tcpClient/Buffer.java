package lia.Monitor.tcpClient;

import java.util.LinkedList;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * In testing for the OSGFTPHelper a lot of messages are comming, so this buffer is used in order not to kill the tmClient thread...
 */
public class Buffer implements Runnable {

	/** Logger Name */
	private static final transient String COMPONENT = "lia.Monitor.JiniClient.CommonGUI";
	/** The Logger */
	private static final transient Logger logger = Logger.getLogger(COMPONENT);

	private ResultProcesserInterface client;
	private static final int MAX_QUEUE_LENGTH = 150; // maximum queue length
	private LinkedList queue;
	private boolean started = false;
	private boolean active = true;
	private String name = "";
	
	public Buffer(ResultProcesserInterface client, String name) {
		this.client = client;
		queue = new LinkedList();
		started = false;
		this.name = name;
	}
	
	public void start() {
		Thread thread = new Thread(this);
		thread.setName(name);
		thread.start();
	}
	
	public void stop() {
		active = false;
		synchronized (queue) {
			queue.notifyAll();
		}
	}
	
	public void newFarmResult(MLSerClient client, Object result) {
	
		synchronized (queue) {
			while (!started) {
				try {
					queue.wait();
				} catch (Exception ex) { }
			}
			if (queue.size() < MAX_QUEUE_LENGTH)
				queue.add(new Object[] { client, result });
			else
				if (logger.isLoggable(Level.FINEST))
					logger.info(name+" - max size exceded");
			queue.notifyAll();
		}
	}
	
	public void run() {
		
		MLSerClient tclient = null;
		Object result = null;
		started = true;
		synchronized (queue) {
			queue.notifyAll();
		}
		while (active) {
            try {
                synchronized (queue) {
                    while (queue.size() == 0 && active) {
                        try {
                            queue.wait();
                        } catch (Exception ex) { }
                    }
                    if (!active) break;
                    Object[] obj = (Object[])queue.remove(0);
                    tclient = (MLSerClient)obj[0];
                    result = obj[1];
                }
                if (active)
                    client.process(tclient, result);
                tclient = null;
                result = null;
            }catch(Throwable t) {
                logger.log(Level.WARNING, " Got exception in " + name, t);
            }
		}
        logger.log(Level.INFO, name + " exists main loop");
	}
	
} // end of class Buffer
