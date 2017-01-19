package hep.io.xdr;

import java.io.IOException;

/**
 * An interface to be implemented by objects than
 * can be read and written using XDR
 * @author Tony Johnson (tonyj@slac.stanford.edu)
 * @version $Id: XDRSerializable.java 6865 2010-10-10 10:03:16Z ramiro $
 */
public interface XDRSerializable
{
   public void read(XDRDataInput in) throws IOException;

   public void write(XDRDataOutput out) throws IOException;
}
