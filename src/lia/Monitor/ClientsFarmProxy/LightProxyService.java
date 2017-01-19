/*
 * $Id: LightProxyService.java 6865 2010-10-10 10:03:16Z ramiro $
 * Created on Aug 7, 2007
 * 
 */
package lia.Monitor.ClientsFarmProxy;

import java.io.Serializable;
import java.rmi.RemoteException;
import java.util.Vector;

/**
 * 
 * A "lighter" version for lia.Monitor.ClientsFarmProxy.ProxyService
 * It is a "dumb" proxy; will return null on all methods
 * 
 * @author ramiro
 * 
 */
public class LightProxyService implements ProxyServiceI, Serializable {

    private static final long serialVersionUID = -986984224472050635L;

    /**
     * If this is not changed Jini will give the same SID
     */
    public String _key = "N/A";
    
    public Vector getFarms() throws RemoteException {
        return null;
    }

    public Vector getFarmsByGroup(String[] groups) throws RemoteException {
        return null;
    }

    public Vector getFarmsIDs() throws RemoteException {
        return null;
    }

    public Integer getNumberOfClients() throws RemoteException {
        return null;
    }

}
