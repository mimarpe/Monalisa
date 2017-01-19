/*
 * $Id: CircuitsHolder.java 6865 2010-10-10 10:03:16Z ramiro $
 */
package lia.Monitor.ciena.circuits.topo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import lia.Monitor.ciena.circuits.topo.tl1.TL1CDCICircuitsHolder;

/**
 *
 * @author ramiro
 */
public final class CircuitsHolder {

    private static final transient Logger logger = Logger.getLogger(CircuitsHolder.class.getName());
    /**
     * K = swName; V: CDCICircuitsHolder
     */
    public final Map<String, TopoEntry> cdciMap = new ConcurrentHashMap<String, TopoEntry>();
    private static CircuitsHolder _thisInstance;

    private static final class TopoEntry {

        final CDCICircuitsHolder circuitsHolder;
        final TL1CDCICircuitsHolder tl1CircuitsHolder;
        final AtomicLong lastUpdate = new AtomicLong(0);

        TopoEntry(final TL1CDCICircuitsHolder tl1CircuitsHolder, final CDCICircuitsHolder circuitsHolder) {
            this.circuitsHolder = circuitsHolder;
            this.tl1CircuitsHolder = tl1CircuitsHolder;
            lastUpdate.set(System.currentTimeMillis());
        }
    }

    public static final synchronized CircuitsHolder getInstance() {

        if (_thisInstance == null) {
            _thisInstance = new CircuitsHolder();
        }

        return _thisInstance;
    }

    public final void notifyTL1Responses(TL1CDCICircuitsHolder[] responses) {
        if (logger.isLoggable(Level.FINEST)) {
            logger.log(Level.FINEST, "\n\n[ CircuitsHolder ] [ notifyTL1Responses ] Circuits TL1 response(s):\n" + Arrays.toString(responses) + "\n\n");
        }

        for (int i = 0; i < responses.length; i++) {

            final TL1CDCICircuitsHolder tl1CircuitsHolder = responses[i];
            final TopoEntry entry = cdciMap.get(tl1CircuitsHolder.swName);

            // check if already cached or if the same topology as in previous iterations
            if (entry == null || entry.tl1CircuitsHolder == null || !tl1CircuitsHolder.equals(entry.tl1CircuitsHolder)) {
                try {
                    cdciMap.put(tl1CircuitsHolder.swName, new TopoEntry(tl1CircuitsHolder,
                            CDCICircuitsHolder.fromTL1CircuitsHolder(tl1CircuitsHolder)));
                    if (logger.isLoggable(Level.FINE)) {
                        if (logger.isLoggable(Level.FINER)) {
                            logger.log(Level.FINER, "[ CircuitsHolder ] [ notifyTL1Responses ] New TL1 response: " + tl1CircuitsHolder);
                        } else {
                            logger.log(Level.FINE, "[ CircuitsHolder ] [ notifyTL1Responses ] Added TL1 response for node: " + tl1CircuitsHolder.swName);
                        }
                    }
                } catch (Throwable t) {
                    logger.log(Level.WARNING,
                            " [ CircuitsHolder ] [ notifyTL1Responses ] got exception parsing TL1 response " + tl1CircuitsHolder, t);
                }
            } else {
                entry.lastUpdate.set(System.currentTimeMillis());
                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, "[ CircuitsHolder ] [ notifyTL1Responses ] OSRP TL1 Topo for nodeID: " + tl1CircuitsHolder.swName + " already in the local cache");
                }
            }
        }

    } // notifyTL1Responses

    public final TL1CDCICircuitsHolder[] getAllTL1Circuits() {
        ArrayList<TL1CDCICircuitsHolder> ret = new ArrayList<TL1CDCICircuitsHolder>();
        for (final TopoEntry entry : cdciMap.values()) {
            ret.add(entry.tl1CircuitsHolder);
        }

        return ret.toArray(new TL1CDCICircuitsHolder[ret.size()]);
    }
    
    public final CDCICircuitsHolder[] getAllCDCICircuits() {
        ArrayList<CDCICircuitsHolder> ret = new ArrayList<CDCICircuitsHolder>();
        for (final TopoEntry entry : cdciMap.values()) {
            ret.add(entry.circuitsHolder);
        }

        return ret.toArray(new CDCICircuitsHolder[ret.size()]);
    }
    
    public final String[] getAllNodeNames() {
        return cdciMap.keySet().toArray(new String[0]);
    }
}
