package org.infinispan.remoting.transport.jgroups;

import org.infinispan.commons.marshall.Marshaller;
import org.infinispan.marshall.protostream.impl.GlobalMarshaller;
import org.infinispan.protostream.ProtobufUtil;
import org.infinispan.remoting.responses.SuccessfulResponse;
import org.jgroups.MessageFactory;
import org.jgroups.util.*;

import java.io.*;
import java.util.Objects;
import java.util.function.Supplier;

public class InfinispanMessage extends org.jgroups.BaseMessage {
   // can be null or a SizeStreamable or an ObjectWrapper (also SizeStreamable)
   protected Object              obj; // change to AbstractDataCommand, CacheRpcCommand, ReplicableCommand?
   protected GlobalMarshaller    marshaller;
   protected volatile int        cached_size=-1; // look into replacing with VarHandle
   public static final short  TYPE=15;

   static {
      MessageFactory.get().registerDefaultMessage(TYPE, InfinispanBytesMessage::new);
   }

   public InfinispanMessage() {
      this(null);
   }

   /**
    * Constructs a message given a destination address
    * @param dest The Address of the receiver. If it is null, then the message is sent to the group. Otherwise, it is
    *             sent to a single member.
    */
   public InfinispanMessage(org.jgroups.Address dest) {
      super(dest);
   }

   /**
    * Constructs a message given a destination address and the payload object
    * @param dest The Address of the receiver. If it is null, then the message is sent to the group. Otherwise, it is
    *             sent to a single member.
    * @param obj To be used as payload.
    */
   public InfinispanMessage(org.jgroups.Address dest, Object obj, Marshaller m) {
      super(dest);
      setMarshaller(m);
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
      if(Objects.equals(this.obj, obj))
         return this;
      this.obj=obj;
      cached_size=this.obj == null? 0 : -1;
      return this;
   }

   public <T extends Object> T getObject() {
      return (T)obj;
   }

   public void writePayload(DataOutput out) throws IOException {
      ByteArrayDataOutputStream outstream=(ByteArrayDataOutputStream)out;
      try {
         // int length=msg.getLength()
         int pos=outstream.position();
         outstream.writeInt(-1); // placeholder for length (4 bytes)
         if(obj == SuccessfulResponse.SUCCESSFUL_EMPTY_RESPONSE) {
            out.writeByte(JGroupsTransport.EMPTY_MESSAGE_BYTE);
         }
         else
            marshaller.writeObject(obj, outstream);

         int length=outstream.position() - pos - Integer.BYTES;
         byte[] buf=outstream.buffer();
         Util.INT_ARRAY_VIEW.set(buf, pos, length); // replace the placeholder with the actual length
      }
      catch(Throwable t) {
         System.err.printf("exception: %s\n", t);
      }
   }

   // not used
   public void readPayload(DataInput in) throws IOException, ClassNotFoundException {
      try {
         byte is_null=in.readByte();
         if(is_null == -1) {
            this.obj=SuccessfulResponse.SUCCESSFUL_EMPTY_RESPONSE;
            cached_size=0;
         }
         else {
            SizeCountingInputStream tmp=new SizeCountingInputStream((InputStream)in);
            this.obj=marshaller.readObject(tmp);
            cached_size=tmp.position();
         }
      }
      catch(Throwable t) {
         System.err.printf("-- exception: %s\n", t);
      }
   }

   @Override protected org.jgroups.Message copyPayload(org.jgroups.Message copy) {
      ((InfinispanMessage)copy).setMarshaller(this.marshaller).setObject(obj);
      return copy;
   }

   public String toString() {
      return super.toString() + String.format(", obj: %s", obj);
   }

   @Override protected int payloadSize() {
      if(obj == null)
         return 0;
      int tmp=cached_size; // volatile read
      if(tmp >= 0)
         return tmp;
       try {
           return cached_size=ProtobufUtil.computeWrappedMessageSize(marshaller.getSerializationContext(), obj); //actualSize();
       }
       catch(IOException e) {
           throw new RuntimeException(e);
       }
   }

   /*protected int actualSize() {
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
   }*/
}
