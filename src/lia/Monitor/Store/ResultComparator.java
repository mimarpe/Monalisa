package lia.Monitor.Store;

import java.io.Serializable;
import java.util.Comparator;

/**
 * Simple comparator for *Result objects, to order them in time
 * 
 * @author costing
 */
public final class ResultComparator implements java.util.Comparator<Object>,Serializable {
	private static final long	serialVersionUID	= 1L;
	
	private static final ResultComparator instance = new ResultComparator();
	
	/**
	 * Singleton
	 * 
	 * @return the only instance of this object
	 */
	public static Comparator<Object> getInstance(){
		return instance;
	}
	
	@Override
	public int compare(final Object o1, final Object o2) {
		final long t1 = Cache.getResultTime(o1);
		final long t2 = Cache.getResultTime(o2);

		if (t1 < t2)
			return -1;
		if (t1 > t2)
			return 1;

		return 0;
	}
}
