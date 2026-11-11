package com.sage.business.code.adapter.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * One route group of the /api/v2 plane (desktop design §11.3). A group
 * returns true when it consumed the exchange and false when the path does
 * not belong to it, so the dispatcher keeps a single authoritative 404 and
 * consistent problem mapping instead of every group guessing.
 */
public interface V2RouteGroup {
    /** @return true when the exchange was handled (or failed with a problem), false when the path is not owned by this group. */
    boolean handleGroup(HttpExchange exchange, String path, String method) throws IOException;
}
