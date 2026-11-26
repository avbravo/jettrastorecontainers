package io.jettra.store;

import io.jettra.store.cluster.JettraClusterTransportServer;
import io.jettra.store.core.JettraClusterStateBackupManager;
import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeAll;
import io.jettra.test.core.JettraAssert;

/**
 * Clase base para tests de JettraStore que garantiza la limpieza de /jettra/node-1,
 * el cierre de puertos de servidores/transportes activos, las bases de datos de prueba
 * y el respaldo/restauración de cluster-state.config.
 */
public abstract class JettraStoreBaseTest {

    private static JettraClusterStateBackupManager.BackupHandle baseStateBackup;

    @BeforeAll
    public static void cleanUpNode1AndTestDatabases() {
        baseStateBackup = JettraClusterStateBackupManager.protectConfigured();
        JettraTestCleanup.cleanUpNode1AndTestDatabases();
    }

    @AfterAll
    public static void restoreClusterStateAfterSuite() {
        try {
            JettraStoreServer.stopAllActiveServers();
            JettraAssert.assertEquals(0, JettraStoreServer.getActiveServerCount(),
                    "No deben quedar instancias activas de JettraStoreServer al finalizar la suite");
            JettraAssert.assertEquals(0, JettraClusterTransportServer.getActiveTransportCount(),
                    "No deben quedar instancias activas de JettraClusterTransportServer al finalizar la suite");
        } finally {
            JettraTestCleanup.cleanUpNode1AndTestDatabases();
            if (baseStateBackup != null) {
                baseStateBackup.close();
                baseStateBackup = null;
            }
        }
    }

    @AfterEach
    public void cleanUpAfterEach() {
        JettraTestCleanup.cleanUpNode1AndTestDatabases();
    }
}
