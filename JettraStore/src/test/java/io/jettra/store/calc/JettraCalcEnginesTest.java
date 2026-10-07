package io.jettra.store.calc;

import io.jettra.store.JettraStoreBaseTest;
import io.jettra.store.JettraTestCleanup;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.store.engine.models.DocumentEngine;
import io.jettra.store.engine.query.JettraSQLProcessor;
import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.BeforeAll;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static io.jettra.test.core.JettraAssert.*;

public class JettraCalcEnginesTest extends JettraStoreBaseTest {

    @BeforeAll
    @AfterAll
    public static void cleanUpNode1AndTestDatabases() {
        JettraTestCleanup.cleanUpNode1AndTestDatabases();
    }


    @Test
    @DisplayName("Test Math Operations & Parser")
    public void testMathOperations() {
        assertEquals(4.0, JettraMath.sqrt(16.0), 0.001);
        assertEquals(3.0, JettraMath.cbrt(27.0), 0.001);
        assertEquals(120L, JettraMath.factorial(5));
        assertEquals(6L, JettraMath.gcd(54, 24));
        assertEquals(216L, JettraMath.lcm(54, 24));

        double evalRes = JettraMath.eval("cbrt(64) + sqrt(25) * 2");
        assertEquals(14.0, evalRes, 0.001);
    }

    @Test
    @DisplayName("Test Statistics Operations")
    public void testStatisticsOperations() {
        List<Double> data = List.of(10.0, 20.0, 30.0, 40.0, 50.0);
        assertEquals(30.0, JettraStatistics.mean(data), 0.001);
        assertEquals(30.0, JettraStatistics.median(data), 0.001);
        assertEquals(150.0, JettraStatistics.sum(data), 0.001);
        assertEquals(10.0, JettraStatistics.min(data), 0.001);
        assertEquals(50.0, JettraStatistics.max(data), 0.001);
        assertEquals(20.0, JettraStatistics.iqr(data), 0.001);
        assertTrue(JettraStatistics.stddev(data, true) > 0);
    }

    @Test
    @DisplayName("Test Financial Operations")
    public void testFinancialOperations() {
        // PMT
        double pmt = JettraFinance.pmt(0.05 / 12.0, 360, 200000.0);
        assertTrue(pmt > 1000.0 && pmt < 1100.0);

        // CAGR
        double cagr = JettraFinance.cagr(100.0, 200.0, 3.0);
        assertTrue(cagr > 25.0 && cagr < 27.0);

        // Amortization schedule
        var sched = JettraFinance.amortizationSchedule(10000.0, 0.06, 12);
        assertEquals(12, sched.size());
        assertEquals(0.0, sched.get(11).remainingBalance(), 0.01);
    }

    @Test
    @DisplayName("Test Vector Operations")
    public void testVectorOperations() {
        float[] v1 = new float[]{1f, 0f, 0f};
        float[] v2 = new float[]{0f, 1f, 0f};
        assertEquals(0f, JettraVectorMath.dotProduct(v1, v2), 0.001f);
        assertEquals(0f, JettraVectorMath.cosineSimilarity(v1, v2), 0.001f);
        assertEquals((float) Math.sqrt(2.0), JettraVectorMath.euclideanDistance(v1, v2), 0.001f);

        float[] cross = JettraVectorMath.crossProduct(v1, v2);
        assertEquals(0f, cross[0], 0.001f);
        assertEquals(0f, cross[1], 0.001f);
        assertEquals(1f, cross[2], 0.001f);
    }

    @Test
    @DisplayName("Test SQL & Aggregations Integration")
    public void testSQLAggregations() {
        JettraStoreConfig cfg = JettraStoreConfig.load();
        try (JettraDatabase db = new JettraDatabase("test_calc_db", cfg)) {
            DocumentEngine engine = db.getDocumentEngine("sales");

            engine.insert("s1", Map.of("category", "A", "amount", 100.0));
            engine.insert("s2", Map.of("category", "A", "amount", 200.0));
            engine.insert("s3", Map.of("category", "B", "amount", 300.0));

            JettraSQLProcessor processor = new JettraSQLProcessor(db);
            var q1 = processor.execute("SELECT category, SUM(amount) AS total, AVG(amount) AS avg_amt FROM sales GROUP BY category");
            assertEquals(2, q1.totalRows());

            var qMath = processor.execute("MATH 10 * 5 + sqrt(100)");
            assertEquals(1, qMath.totalRows());
            assertEquals(60.0, (Double) qMath.rows().get(0).get(1), 0.001);

            var qFin = processor.execute("FINANCE CAGR 100 200 3");
            assertEquals(1, qFin.totalRows());

            var qVec = processor.execute("VECTOR DOT [1, 2, 3] [4, 5, 6]");
            assertEquals(1, qVec.totalRows());
            assertEquals(32.0f, (Float) qVec.rows().get(0).get(0), 0.001f);
        } finally {
            cleanUpNode1AndTestDatabases();
        }
    }
}
