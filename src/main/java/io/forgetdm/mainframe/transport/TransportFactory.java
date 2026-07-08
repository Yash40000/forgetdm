package io.forgetdm.mainframe.transport;

import io.forgetdm.common.ApiException;
import io.forgetdm.mainframe.MainframeConnectionEntity;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Picks the right transport implementation for a connection's type. */
@Component
public class TransportFactory {

    private final LocalTransport local;
    private final ZoweTransport zowe;

    public TransportFactory(LocalTransport local, ZoweTransport zowe) {
        this.local = local;
        this.zowe = zowe;
    }

    public MainframeTransport forConnection(MainframeConnectionEntity conn) {
        String type = conn.getType() == null ? "" : conn.getType().trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "LOCAL": return local;
            case "ZOWE":  return zowe;
            default: throw ApiException.bad("Unknown connection type '" + conn.getType() + "' (expected LOCAL or ZOWE)");
        }
    }
}
