package io.jettra.store.engine.models;

import java.util.function.Supplier;

public final class JettraRef<T> {
    public enum FetchMode { LAZY, EAGER }

    private final String targetEngine;
    private final String targetId;
    private final FetchMode fetchMode;
    private final Supplier<T> resolver;
    private T resolvedValue;
    private boolean isResolved;

    public JettraRef(String targetEngine, String targetId, FetchMode fetchMode, Supplier<T> resolver) {
        this.targetEngine = targetEngine;
        this.targetId = targetId;
        this.fetchMode = fetchMode;
        this.resolver = resolver;
        this.isResolved = false;

        if (fetchMode == FetchMode.EAGER && resolver != null) {
            this.resolvedValue = resolver.get();
            this.isResolved = true;
        }
    }

    public synchronized T resolve() {
        if (!isResolved && resolver != null) {
            this.resolvedValue = resolver.get();
            this.isResolved = true;
        }
        return resolvedValue;
    }

    public boolean isResolved() {
        return isResolved;
    }

    public String getTargetEngine() {
        return targetEngine;
    }

    public String getTargetId() {
        return targetId;
    }

    public FetchMode getFetchMode() {
        return fetchMode;
    }

    public String getDescriptor() {
        return targetEngine + "::" + targetId;
    }

    @Override
    public String toString() {
        return "JettraRef[" + getDescriptor() + ", resolved=" + isResolved + "]";
    }
}
