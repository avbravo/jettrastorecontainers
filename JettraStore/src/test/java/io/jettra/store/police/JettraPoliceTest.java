package io.jettra.store.police;

import io.jettra.store.JettraStoreBaseTest;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;

import java.util.ArrayList;
import java.util.List;

import static io.jettra.test.core.JettraAssert.*;

public class JettraPoliceTest extends JettraStoreBaseTest {

    @Test
    @DisplayName("Debe permitir consultas seguras y acotadas sin requerir intervención forzada")
    public void testSafeQueryAllowed() {
        JettraPolice police = JettraPolice.getInstance();
        // Colección pequeña con límite acotado
        JettraPolice.PoliceDecision decision = police.evaluateHeapSafety("SQL_SELECT", "config", 50, 10, 256);
        assertFalse(decision.interventionRequired());
        assertEquals(JettraPolice.PoliceAction.PERMITTED, decision.action());
        assertEquals(10, decision.enforcedLimit());
    }

    @Test
    @DisplayName("Debe activar JettraPolice y forzar paginación lazy ante consultas masivas no acotadas")
    public void testMassiveQueryTriggersPoliceIntervention() {
        JettraPolice police = JettraPolice.getInstance();
        // Colección masiva de 200,000 registros sin límite (SELECT * FROM clientes)
        JettraPolice.PoliceDecision decision = police.evaluateHeapSafety("SQL_SELECT", "clientes", 200000, 0, 512);

        assertTrue(decision.interventionRequired());
        assertEquals(JettraPolice.PoliceAction.AUTO_PAGINATE_LAZY, decision.action());
        assertTrue(decision.enforcedLimit() <= 100);
        assertTrue(decision.enforcedLimit() > 0);
        assertTrue(decision.rationale().contains("JettraPolice activado"));

        // Verificar que la alerta fue registrada en la auditoría del centinela
        List<JettraPolice.PoliceAlert> alerts = police.getAlerts();
        assertFalse(alerts.isEmpty());
        boolean hasHeapAlert = alerts.stream().anyMatch(a -> "HEAP_EXHAUSTION_PREVENTED".equals(a.code()));
        assertTrue(hasHeapAlert);
    }

    @Test
    @DisplayName("Debe procesar streaming lazy mediante LazyPagedCursor distribuyendo la carga")
    public void testLazyPagedCursorWorkloadDistribution() {
        List<String> mockDatabase = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            mockDatabase.add("doc_" + i);
        }

        int pageSize = 50;
        JettraPolice.LazyPagedCursor<String> cursor = new JettraPolice.LazyPagedCursor<>(pageSize, (offset, limit) -> {
            int toIndex = Math.min(offset + limit, mockDatabase.size());
            if (offset >= mockDatabase.size()) return List.of();
            return mockDatabase.subList(offset, toIndex);
        });

        int totalRead = 0;
        int pageCount = 0;
        while (cursor.hasNextPage()) {
            List<String> page = cursor.fetchNextPage();
            if (page.isEmpty()) break;
            pageCount++;
            totalRead += page.size();
            assertTrue(page.size() <= pageSize);
        }

        assertEquals(250, totalRead);
        assertEquals(5, pageCount);
    }
}
