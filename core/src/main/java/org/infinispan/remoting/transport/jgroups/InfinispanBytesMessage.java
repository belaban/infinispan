package org.infinispan.remoting.transport.jgroups;

import org.jgroups.Address;
import org.jgroups.BytesMessage;
import org.jgroups.Message;
import org.jgroups.util.ByteArray;

import java.io.DataInput;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * @author Bela Ban
 * @since x.y
 */
public class InfinispanBytesMessage extends BytesMessage  {
    public InfinispanBytesMessage() {
        super();
    }

    public InfinispanBytesMessage(Address dest) {
        super(dest);
    }

    public InfinispanBytesMessage(Address dest, byte[] array) {
        super(dest, array);
    }

    public InfinispanBytesMessage(Address dest, byte[] array, int offset, int length) {
        super(dest, array, offset, length);
    }

    public InfinispanBytesMessage(Address dest, ByteArray array) {
        super(dest, array);
    }

    public InfinispanBytesMessage(Address dest, Object obj) {
        super(dest, obj);
    }

    @Override
    public Supplier<Message> create() {
        return InfinispanBytesMessage::new;
    }

    @Override
    public short getType() {
        return InfinispanMessage.TYPE;
    }

    @Override
    public void readPayload(DataInput in) throws IOException {
        // in.readByte();
        super.readPayload(in);
    }
}
