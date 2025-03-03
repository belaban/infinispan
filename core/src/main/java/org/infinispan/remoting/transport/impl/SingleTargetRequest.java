package org.infinispan.remoting.transport.impl;

import net.jcip.annotations.GuardedBy;
import org.infinispan.commons.util.Util;
import org.infinispan.remoting.responses.CacheNotFoundResponse;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.transport.AbstractRequest;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.ResponseCollector;
import org.infinispan.remoting.transport.jgroups.RequestTracker;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.util.Set;

import static org.infinispan.util.logging.Log.CLUSTER;

/**
 * Request implementation that waits for a response from a single target node.
 *
 * @author Dan Berindei
 * @since 9.1
 */
public class SingleTargetRequest<T> extends AbstractRequest<T> {
   private static final Log log = LogFactory.getLog(SingleTargetRequest.class);

   // Only changes from non-null to null
   @GuardedBy("responseCollector")
   private volatile RequestTracker requestTracker;

   public SingleTargetRequest(ResponseCollector<T> wrapper, long requestId, RequestRepository repository, RequestTracker requestTracker) {
      super(requestId, wrapper, repository);
      this.requestTracker = requestTracker;
   }

   @Override
   public void onResponse(Address sender, Response response) {
      try {
         T result;
         final RequestTracker tmp = requestTracker;
         if (tmp != null) {
            if (!tmp.destination().equals(sender)) {
               log.tracef("Received unexpected response to request %d from %s, target is %s", requestId, sender, tmp.destination());
            }
            tmp.onComplete();
            requestTracker = null;
         }
         boolean skipSync = responseCollector instanceof SingleResponseCollector;
         if (skipSync) {
            result = addResponse(sender, response);
         }
         else {
            synchronized(responseCollector) {
               result=addResponse(sender, response);
            }
         }
         complete(result);
      } catch (Exception e) {
         completeExceptionally(e);
      }
   }

   @Override
   public boolean onNewView(Set<Address> members) {
      try {
         T result;
         final RequestTracker tmp = requestTracker;
         if (tmp == null || members.contains(tmp.destination())) {
            return false;
         }
         tmp.onComplete();
         requestTracker = null;
         boolean skipSync = responseCollector instanceof SingleResponseCollector;
         if (skipSync) {
            result=addResponse(tmp.destination(), CacheNotFoundResponse.INSTANCE);
         }
         else {
            synchronized(responseCollector) {
               result=addResponse(tmp.destination(), CacheNotFoundResponse.INSTANCE);
            }
         }
         complete(result);
      } catch (Exception e) {
         completeExceptionally(e);
      }
      return true;
   }

   @GuardedBy("responseCollector")
   private T addResponse(Address sender, Response response) {
      T result = responseCollector.addResponse(sender, response);
      if (result == null) {
         result = responseCollector.finish();
      }
      return result;
   }

   @Override
   protected void onTimeout() {
      // The target might be null
      String targetString = null;
      final RequestTracker tmp = requestTracker;
      if (tmp != null) {
         tmp.onTimeout();
         targetString = tmp.destination().toString();
      }
      completeExceptionally(CLUSTER.requestTimedOut(requestId, targetString, Util.prettyPrintTime(getTimeoutMs())));
   }
}
