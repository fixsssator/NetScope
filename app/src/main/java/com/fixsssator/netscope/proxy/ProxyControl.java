package com.fixsssator.netscope.proxy;

/** Small public facade over package-private proxy internals. */
public final class ProxyControl {
    private ProxyControl() {}

    /** Forget hosts that were switched to blind tunnelling after a failed TLS handshake. */
    public static void resetBypass() {
        ProxyConnection.resetBypass();
    }
}
