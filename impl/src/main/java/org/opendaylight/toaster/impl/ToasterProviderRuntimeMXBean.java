package org.opendaylight.toaster.impl;

public interface ToasterProviderRuntimeMXBean {
    Long getToastsMade();

    void clearToastsMade();
}
