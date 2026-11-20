module io.jettra.driver {
    requires JettraStore;
    requires JettraCollections;
    requires JettraJWT;
    requires JettraJSON;
    requires JettraMemory;
    requires java.net.http;

    exports io.jettra.driver;
    exports io.jettra.driver.config;
    exports io.jettra.driver.admin;
    exports io.jettra.driver.listener;
    exports io.jettra.driver.query;
}
