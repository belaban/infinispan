/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */

package org.infinispan.remoting.transport.jgroups;

import org.infinispan.remoting.responses.ExceptionResponse;
import org.infinispan.remoting.transport.BackupResponse;
import org.infinispan.util.concurrent.TimeoutException;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.infinispan.xsite.XSiteBackup;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.*;
import static org.infinispan.util.Util.formatString;
import static org.infinispan.util.Util.prettyPrintTime;

/**
 * @author Mircea Markus
 * @since 5.2
 */
public class JGroupsBackupResponse implements BackupResponse {

   private static Log log = LogFactory.getLog(JGroupsBackupResponse.class);

   private final Map<XSiteBackup, Future<Object>> syncBackupCalls;
   private Map<String, Throwable> errors;
   private Set<String> communicationErrors;

   //there might be an significant difference in time between when the message is sent and when the actual wait
   // happens. Track that and adjust the timeouts accordingly.
   private long sendTimeNanos;

   public JGroupsBackupResponse(Map<XSiteBackup, Future<Object>> syncBackupCalls) {
      this.syncBackupCalls = syncBackupCalls;
      sendTimeNanos = System.nanoTime();
   }

   @Override
   public void waitForBackupToFinish() throws Exception {
      long deductFromTimeout = NANOSECONDS.toMillis(System.nanoTime() - sendTimeNanos);
      errors = new HashMap<String, Throwable>(syncBackupCalls.size());
      long elapsedTime = 0;
      for (Map.Entry<XSiteBackup, Future<Object>> entry : syncBackupCalls.entrySet()) {
         long timeout = entry.getKey().getTimeout();
         String siteName = entry.getKey().getSiteName();
         if (timeout > 0) { //0 means wait forever
            timeout -= deductFromTimeout;
            timeout -= elapsedTime;
            if (timeout <= 0 && !entry.getValue().isDone())
               errors.put(siteName, newTimeoutException(entry, entry.getKey().getTimeout()));
         }

         long startNanos = System.nanoTime();
         Object value = null;
         try {
            value = entry.getValue().get(timeout, MILLISECONDS);
         } catch (java.util.concurrent.TimeoutException te) {
            errors.put(siteName, newTimeoutException(entry, entry.getKey().getTimeout()));
            addCommunicationError(siteName);
         } catch (ExecutionException ue) {
            log.tracef("Communication error with site %s", siteName);
            errors.put(siteName, ue.getCause());
            addCommunicationError(siteName);
         } finally {
            elapsedTime += NANOSECONDS.toMillis(System.nanoTime() - startNanos);
         }

         if (value instanceof ExceptionResponse) {
            Exception remoteException = ((ExceptionResponse) value).getException();
            log.tracef(remoteException, "Got error backup response from site %s", siteName);
            errors.put(siteName, remoteException);
         } else {
            log.tracef("Received response from site %s: %s", siteName, value);
         }
      }
   }

   private void addCommunicationError(String siteName) {
      if (communicationErrors == null) //only create lazily as we don't expect communication errors to be the norm
         communicationErrors = new HashSet<String>(1);
      communicationErrors.add(siteName);
   }

   @Override
   public Set<String> getCommunicationErrors() {
      return communicationErrors == null ? Collections.<String>emptySet() : communicationErrors;
   }

   @Override
   public long getSendTimeMillis() {
      return NANOSECONDS.toMillis(sendTimeNanos);
   }

   @Override
   public Map<String, Throwable> getFailedBackups() {
      return errors;
   }

   private TimeoutException newTimeoutException(Map.Entry<XSiteBackup, Future<Object>> entry, long timeout) {
      return new TimeoutException(formatString("Timed out after %s waiting for a response from %s",
                                               prettyPrintTime(timeout), entry.getKey()));
   }
}
