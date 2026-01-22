package org.infinispan.remoting.transport.jgroups;

import org.infinispan.commons.marshall.Marshaller;
import org.infinispan.marshall.protostream.impl.GlobalMarshaller;
import org.infinispan.remoting.responses.SuccessfulResponse;
import org.jgroups.util.*;

import java.io.*;
import java.util.function.Supplier;

// TODO: we don't actually use this yet, just reserve the type id for future usage
public class InfinispanMessage extends org.jgroups.BaseMessage {
   // can be null or a SizeStreamable or an ObjectWrapper (also SizeStreamable)
   protected Object              obj; // change to AbstractDataCommand, CacheRpcCommand, ReplicableCommand?
   protected GlobalMarshaller    marshaller;
   protected volatile int        cached_size; // look into replacing with VarHandle
   protected static final short  TYPE=1005;


   public InfinispanMessage() {
      this(null);
   }

   /**
    * Constructs a message given a destination address
    * @param dest The Address of the receiver. If it is null, then the message is sent to the group. Otherwise, it is
    *             sent to a single member.
    */
   public InfinispanMessage(org.jgroups.Address dest) {
      this(dest, null);
   }



   /**
    * Constructs a message given a destination address and the payload object
    * @param dest The Address of the receiver. If it is null, then the message is sent to the group. Otherwise, it is
    *             sent to a single member.
    * @param obj To be used as payload.
    */
   public InfinispanMessage(org.jgroups.Address dest, Object obj) {
      super(dest);
      setObject(obj);
   }




   public Supplier<org.jgroups.Message> create()                 {return InfinispanMessage::new;}
   public short             getType()                            {return TYPE;}
   public boolean           hasPayload()                         {return obj != null;}
   public boolean           hasArray()                           {return false;}
   public int               getOffset()                          {return 0;}
   public int               getLength()                          {return obj != null? payloadSize() : 0;}
   public byte[]            getArray()                           {throw new UnsupportedOperationException();}
   public InfinispanMessage setArray(byte[] b, int off, int len) {throw new UnsupportedOperationException();}
   public InfinispanMessage setArray(ByteArray buf)              {throw new UnsupportedOperationException();}
   public Marshaller        getMarshaller()                      {return marshaller;}
   public InfinispanMessage setMarshaller(Marshaller m)          {this.marshaller=(GlobalMarshaller)m; return this;}


   /** Sets the object. If the object doesn't implement {@link SizeStreamable}, or is a primitive type,
    * it will be wrapped into an {@link ObjectWrapperSerializable} (which does implement SizeStreamable)
    */
   public InfinispanMessage setObject(Object obj) {
      this.obj=obj;
      return this;
   }

   public <T extends Object> T getObject() {
      return (T)obj;
   }

   public void writePayload(DataOutput out) throws IOException {
      OutputStream outstream=out instanceof OutputStream? (OutputStream)out
        : new OutputStreamAdapter((ByteArrayDataOutputStream)out);
      try {
         if(obj == null) {
            out.writeByte(-1);
            return;
         }
         out.writeByte(1);
         marshaller.writeObject(obj, outstream);
         outstream.write(0);
      }
      catch(Throwable t) {
         System.err.printf("exception: %s\n", t);
      }
   }

   public void readPayload(DataInput in) throws IOException, ClassNotFoundException {
      try {
         byte is_null=in.readByte();
         if(is_null == -1)
            this.obj=SuccessfulResponse.SUCCESSFUL_EMPTY_RESPONSE;
         else
            this.obj=marshaller.readObject((InputStream)in);
      }
      catch(Throwable t) {
         System.err.printf("-- exception: %s\n", t);
      }
   }

   @Override protected org.jgroups.Message copyPayload(org.jgroups.Message copy) {
      if(obj != null)
         ((InfinispanMessage)copy).setObject(obj);
      ((InfinispanMessage)copy).cached_size=cached_size;
      return copy;
   }

   public String toString() {
      return super.toString() + String.format(", obj: %s", obj);
   }

   @Override protected int payloadSize() {
      if(obj == null)
         return 0;
      int tmp=cached_size; // volatile read
      if(tmp > 0)
         return tmp;
      return cached_size=actualSize();
   }

   protected int actualSize() {
      if(obj == null)
         return 0;
      SizeCountingOutputStream out=new SizeCountingOutputStream();
      try {
         marshaller.writeObject(obj, out);
         return out.size();
      }
      catch(IOException e) {
         throw new RuntimeException(e);
      }
   }
}
