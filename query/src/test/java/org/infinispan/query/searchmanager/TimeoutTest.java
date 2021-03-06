package org.infinispan.query.searchmanager;

import org.apache.lucene.search.Query;
import org.hibernate.search.annotations.Field;
import org.hibernate.search.annotations.Indexed;
import org.hibernate.search.query.engine.spi.TimeoutExceptionFactory;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.query.CacheQuery;
import org.infinispan.query.Search;
import org.infinispan.query.SearchManager;
import org.infinispan.test.SingleCacheManagerTest;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.testng.annotations.Test;

import java.util.concurrent.TimeUnit;

import static org.testng.Assert.fail;

/**
 * @author <a href="mailto:mluksa@redhat.com">Marko Luksa</a>
 */
@Test(groups = "functional", testName = "query.TimeoutTest")
public class TimeoutTest extends SingleCacheManagerTest {

   @Override
   protected EmbeddedCacheManager createCacheManager() throws Exception {
      ConfigurationBuilder cfg = getDefaultStandaloneCacheConfig( true );
      cfg
         .indexing()
            .enable()
            .indexLocalOnly( false )
            .addProperty( "hibernate.search.default.directory_provider", "ram" )
            .addProperty( "hibernate.search.lucene_version", "LUCENE_CURRENT" );
      return TestCacheManagerFactory.createCacheManager( cfg );
   }

   @Test
   public void timeoutExceptionIsThrownAndIsProducedByMyFactory() throws Exception {
      SearchManager searchManager = Search.getSearchManager( cache );
      searchManager.setTimeoutExceptionFactory( new MyTimeoutExceptionFactory() );
      Query query = searchManager.buildQueryBuilderForClass( Foo.class ).get()
            .keyword().onField( "bar" ).matching( "1" )
            .createQuery();

      CacheQuery cacheQuery = searchManager.getQuery( query );
      cacheQuery.timeout( 1, TimeUnit.NANOSECONDS );

      try {
         cacheQuery.list();
         fail( "Expected MyTimeoutException" );
      } catch (MyTimeoutException e) {
      }
   }

   private static class MyTimeoutExceptionFactory implements TimeoutExceptionFactory {
      @Override
      public RuntimeException createTimeoutException(String message, Query query) {
         return new MyTimeoutException();
      }
   }

   public static class MyTimeoutException extends RuntimeException {
   }


   @Indexed(index = "FooIndex")
   public class Foo {
      private String bar;

      public Foo(String bar) {
         this.bar = bar;
      }

      @Field(name = "bar")
      public String getBar() {
         return bar;
      }
   }
}
