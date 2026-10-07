package io.jettra.store;

import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeAll;

/**
 * Clase base para tests de JettraStore que garantiza la limpieza de /jettra/node-1
 * y las bases de datos de prueba al terminar de ejecutar los tests.
 */
public abstract class JettraStoreBaseTest {

    @BeforeAll
    @AfterAll
    public static void cleanUpNode1AndTestDatabases() {
        JettraTestCleanup.cleanUpNode1AndTestDatabases();
    }

    @AfterEach
    public void cleanUpAfterEach() {
        JettraTestCleanup.cleanUpNode1AndTestDatabases();
    }
}
