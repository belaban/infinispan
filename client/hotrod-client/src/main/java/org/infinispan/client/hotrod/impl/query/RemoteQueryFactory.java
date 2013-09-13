package org.infinispan.client.hotrod.impl.query;

import org.infinispan.client.hotrod.exceptions.HotRodClientException;
import org.infinispan.client.hotrod.impl.RemoteCacheImpl;
import org.infinispan.client.hotrod.marshall.ProtoStreamMarshaller;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryBuilder;
import org.infinispan.query.dsl.impl.BaseQueryFactory;
import org.infinispan.query.remote.client.MarshallerRegistration;

/**
 * @author anistor@redhat.com
 * @since 6.0
 */
public final class RemoteQueryFactory extends BaseQueryFactory<Query> {

   private final RemoteCacheImpl cache;

   public RemoteQueryFactory(RemoteCacheImpl cache) {
      this.cache = cache;
      try {
         MarshallerRegistration.registerMarshallers(ProtoStreamMarshaller.getSerializationContext());
      } catch (Exception e) {
         //todo [anistor] need better exception handling
         throw new HotRodClientException("Failed to initialise serialization context", e);
      }
   }

   @Override
   public QueryBuilder<Query> from(Class entityType) {
      return new RemoteQueryBuilder(cache, entityType);
   }
}
