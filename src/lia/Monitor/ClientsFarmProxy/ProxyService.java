/*
 * $Id: ProxyService.java 6865 2010-10-10 10:03:16Z ramiro $
 */

package lia.Monitor.ClientsFarmProxy;

import java.rmi.RemoteException;
import java.util.Vector;


/**
 * 
 * @author mickyt
 * 
 */
public class ProxyService implements ProxyServiceI, java.io.Serializable{
	
    private static final long serialVersionUID = -6357564605550117036L;
    private transient ServiceI service ;
	
	public ProxyService() throws RemoteException {}
	
	public ProxyService (ServiceI service) throws RemoteException {
		this.service = service ;
		if (this.service == null) {
            System.out.println("ProxyService ====> service null ... IN CONSTRUCTORUL DE LA PROXY SERVICE :((:((:((((((((((("); 
		} else {
	        System.out.println ("ProxyService ====> service nu e null ... IN CONSTRUCTORUL DE LA PROXY SERVICE ");
		}
	} //constructor

	public Vector getFarms () throws RemoteException {
		return service.getFarms() ;
	} //getFarms
	
	public Vector getFarmsByGroup (String[] groups) throws RemoteException {
		if (service ==null)
			System.out.println ("Service e null") ;
		return service.getFarmsByGroup (groups) ;
	} //getFarmsByGroup
	
	public Vector getFarmsIDs () throws RemoteException {
		if (service == null)
			System.out.println ("ProxyService =====> service este null");
		return service.getFarmsIDs();
	}
	
	public Integer getNumberOfClients() throws RemoteException {
		return service.getNumberOfClients();
	}
	
}
