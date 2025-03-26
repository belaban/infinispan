package org.infinispan.interceptors.impl;

import org.infinispan.commands.MetadataAwareCommand;
import org.infinispan.commands.VisitableCommand;
import org.infinispan.commands.write.ReplaceCommand;
import org.infinispan.container.versioning.VersionGenerator;
import org.infinispan.context.InvocationContext;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.interceptors.DDAsyncInterceptor;
import org.infinispan.metadata.Metadata;

/**
 * Interceptor installed when compatiblity is enabled.
 * @author wburns
 * @since 9.0
 */
public class VersionInterceptor extends DDAsyncInterceptor {

   @Inject protected VersionGenerator versionGenerator;

   @Override
   public Object visitReplaceCommand(InvocationContext ctx, ReplaceCommand command) throws Throwable {
      addVersionIfNeeded(command);
      return super.visitReplaceCommand(ctx, command);
   }

   protected void addVersionIfNeeded(MetadataAwareCommand cmd) {
      Metadata metadata = cmd.getMetadata();
      if (metadata.version() == null) {
         Metadata newMetadata = metadata.builder()
               .version(versionGenerator.generateNew())
               .build();
         cmd.setMetadata(newMetadata);
      }
   }

   @Override
   public Object handleCommand(InvocationContext ctx, VisitableCommand command) throws Throwable {
      switch(command.getCommandId()) {
         case ReplaceCommand.COMMAND_ID:
            addVersionIfNeeded((ReplaceCommand)command);
            break;
      }
      return callNext(ctx, command);
   }
}
