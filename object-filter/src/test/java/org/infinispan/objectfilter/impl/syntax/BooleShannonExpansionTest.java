package org.infinispan.objectfilter.impl.syntax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.infinispan.objectfilter.impl.syntax.parser.IckleParser;
import org.infinispan.objectfilter.impl.syntax.parser.IckleParsingResult;
import org.infinispan.objectfilter.impl.syntax.parser.ReflectionEntityNamesResolver;
import org.infinispan.objectfilter.impl.syntax.parser.ReflectionPropertyHelper;
import org.junit.Test;

/**
 * @author anistor@redhat.com
 * @since 8.0
 */
public class BooleShannonExpansionTest {

   private final ReflectionPropertyHelper propertyHelper = new ReflectionPropertyHelper(new ReflectionEntityNamesResolver(null));

   private final BooleanFilterNormalizer booleanFilterNormalizer = new BooleanFilterNormalizer();

   private final IndexedFieldProvider.FieldIndexingMetadata<?> dummyFieldIndexingMetadata = new IndexedFieldProvider.FieldIndexingMetadata<>() {
      @Override
      public boolean hasProperty(String[] propertyPath) {
         throw new UnsupportedOperationException("Dummy implementation; should never be invoked during test.");
      }

      @Override
      public boolean isSearchable(String[] propertyPath) {
         // report that everything is indexed except 'license' and 'number' fields
         String last = propertyPath[propertyPath.length - 1];
         return !"number".equals(last) && !"license".equals(last);
      }

      @Override
      public boolean isAnalyzed(String[] propertyPath) {
         throw new UnsupportedOperationException("Dummy implementation; should never be invoked during test.");
      }

      @Override
      public boolean isNormalized(String[] propertyPath) {
         throw new UnsupportedOperationException("Dummy implementation; should never be invoked during test.");
      }

      @Override
      public boolean isProjectable(String[] propertyPath) {
         return isSearchable(propertyPath);
      }

      @Override
      public boolean isAggregable(String[] propertyPath) {
         throw new UnsupportedOperationException("Dummy implementation; should never be invoked during test.");
      }

      @Override
      public boolean isSortable(String[] propertyPath) {
         return isSearchable(propertyPath);
      }

      @Override
      public boolean isVector(String[] propertyPath) {
         throw new UnsupportedOperationException("Dummy implementation; should never be invoked during test.");
      }

      @Override
      public boolean isSpatial(String[] propertyPath) {
         throw new UnsupportedOperationException("Dummy implementation; should never be invoked during test.");
      }

      @Override
      public Object getNullMarker(String[] propertyPath) {
         throw new UnsupportedOperationException("Dummy implementation; should never be invoked during test.");
      }

      @Override
      public Object keyType(String property) {
         throw new UnsupportedOperationException("Dummy implementation; should never be invoked during test.");
      }
   };

   private final BooleShannonExpansion booleShannonExpansion = new BooleShannonExpansion(3, dummyFieldIndexingMetadata);

   /**
    * @param queryString     the input query to parse and expand
    * @param expectedExprStr the expected 'toString()' of the output AST
    * @param expectedQuery   the expected equivalent Ickle query string of the AST
    */
   private void assertExpectedTree(String queryString, String expectedExprStr, String expectedQuery) {
      IckleParsingResult<Class<?>> parsingResult = IckleParser.parse(queryString, propertyHelper);
      BooleanExpr expr = booleanFilterNormalizer.normalize(parsingResult.getWhereClause());
      expr = booleShannonExpansion.expand(expr);
      if (expectedExprStr != null) {
         assertNotNull(expr);
         assertEquals(expectedExprStr, expr.toString());
      } else {
         assertNull(expr);
      }
      if (expectedQuery != null) {
         String queryOut = SyntaxTreePrinter.printTree(parsingResult.getTargetEntityName(), null, expr, null, parsingResult.getSortFields());
         assertEquals(expectedQuery, queryOut);
      }
   }

   @Test
   public void testNothingToExpand() {
      assertExpectedTree("from org.infinispan.objectfilter.test.model.Person",
            null,
            "FROM org.infinispan.objectfilter.test.model.Person");
   }

   @Test
   public void testExpansionNotNeeded() {
      assertExpectedTree("from org.infinispan.objectfilter.test.model.Person p where " +
                  "p.surname = 'Adrian' or p.name = 'Nistor'",
            "OR(EQUAL(PROP(surname), CONST(\"Adrian\")), EQUAL(PROP(name), CONST(\"Nistor\")))",
            "FROM org.infinispan.objectfilter.test.model.Person WHERE (surname = \"Adrian\") OR (name = \"Nistor\")");
   }

   @Test
   public void testExpansionNotPossible() {
      assertExpectedTree("from org.infinispan.objectfilter.test.model.Person p where " +
                  "p.license = 'A' or p.name = 'Nistor'",
            "CONST_TRUE",
            "FROM org.infinispan.objectfilter.test.model.Person");
   }

   @Test
   public void testExpansionNotPossible2() {
      assertExpectedTree("from org.infinispan.objectfilter.test.model.Person p where " +
                  "p.name = 'A' and p.name > 'A'",
            "CONST_FALSE",
            null);
   }

   @Test
   public void testExpansionPossible() {
      assertExpectedTree("from org.infinispan.objectfilter.test.model.Person p where " +
                  "p.phoneNumbers.number != '1234' and p.surname = 'Adrian' or p.name = 'Nistor'",
            "OR(EQUAL(PROP(surname), CONST(\"Adrian\")), EQUAL(PROP(name), CONST(\"Nistor\")))",
            "FROM org.infinispan.objectfilter.test.model.Person WHERE (surname = \"Adrian\") OR (name = \"Nistor\")");
   }

   @Test
   public void testExpansionTooBig() {
      assertExpectedTree("from org.infinispan.objectfilter.test.model.Person p where " +
                  "p.phoneNumbers.number != '1234' and p.surname = 'Adrian' or p.name = 'Nistor' and license = 'PPL'",
            "CONST_TRUE",
            "FROM org.infinispan.objectfilter.test.model.Person");
   }
}
