package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.terminal.TerminalApplicationService;
import com.sage.business.code.adapter.http.stream.LongRequestAdmission;
import com.sage.business.code.contract.v2.terminal.TerminalInputRequest;
import com.sage.business.code.contract.v2.terminal.TerminalCloseRequest;
import com.sage.business.code.contract.v2.terminal.TerminalControlAcquireRequest;
import com.sage.business.code.contract.v2.terminal.TerminalControlReleaseRequest;
import com.sage.business.code.contract.v2.terminal.TerminalControlRenewRequest;
import com.sage.business.code.contract.v2.terminal.TerminalResizeRequest;
import com.sage.business.code.contract.v2.terminal.TerminalSessionCreateRequest;
import com.sage.business.code.contract.v2.terminal.TerminalSignalRequest;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/** Loopback HTTP/SSE authority boundary for stateful terminal sessions. */
public final class TerminalApiHandler extends V2HttpSupport implements V2RouteGroup {
    private static final int MAX_SSE_FRAME_BYTES = 262_144;
    private final TerminalApplicationService terminals;
    private final LongRequestAdmission longRequests;
    public TerminalApiHandler(ObjectMapper mapper, String token, TerminalApplicationService terminals) {
        this(mapper, token, terminals, new LongRequestAdmission());
    }
    public TerminalApiHandler(ObjectMapper mapper, String token,
            TerminalApplicationService terminals, LongRequestAdmission longRequests) {
        super(mapper, token);
        if (longRequests == null) throw new IllegalArgumentException("long request admission is required");
        this.terminals = terminals;
        this.longRequests = longRequests;
    }
    @Override public boolean handleGroup(HttpExchange exchange, String path, String method) throws IOException {
        if (!owns(path)) return false;
        requireAccess(exchange);
        String[] p = path.split("/");
        if (p.length == 6 && "sessions".equals(p[3]) && "terminal-sessions".equals(p[5])) {
            String businessSessionId=p[4]; safeEntityId(businessSessionId,"business session");
            if ("POST".equals(method)) { sendJson(exchange,202,terminals.create(businessSessionId,body(exchange,TerminalSessionCreateRequest.class),mutationKey(exchange))); return true; }
            if ("GET".equals(method)) { sendJson(exchange,200,terminals.list(businessSessionId)); return true; }
            throw methodNotAllowed();
        }
        if (p.length < 5 || !"terminal-sessions".equals(p[3])) return false;
        String id=p[4]; safeEntityId(id,"terminal session");
        if (p.length == 5 && "GET".equals(method)) {
            sendJson(exchange, 200, terminals.getById(id, optionalHolder(exchange)));
            return true;
        }
        if (p.length == 6 && "output".equals(p[5]) && "GET".equals(method)) {
            long waitMillis = longParam(exchange, "waitMillis", 0L);
            if (waitMillis < 0L || waitMillis > 30_000L) {
                throw new com.sage.business.code.domain.BusinessException(
                        "INVALID_REQUEST", 400, "waitMillis is outside the allowed range");
            }
            LongRequestAdmission.Handle reservation = null;
            try {
                if (waitMillis > 0L) {
                    reservation = longRequests.reserve(LongRequestAdmission.Kind.LONG_POLL,
                            "local-owner", id);
                }
                sendJson(exchange, 200, terminals.read(businessFor(id), id,
                        queryParam(exchange, "cursor"), intParam(exchange, "maxBytes", 65536),
                        waitMillis, mode(exchange)));
                return true;
            } finally {
                if (reservation != null) reservation.close();
            }
        }
        if (p.length == 7 && "output".equals(p[5]) && "live".equals(p[6]) && "GET".equals(method)) { stream(exchange,id); return true; }
        if (p.length == 7 && "control".equals(p[5]) && "POST".equals(method)) {
            String holder=authenticatedHolder(exchange); String key=mutationKey(exchange);
            if ("acquire".equals(p[6])) { sendJson(exchange,200,terminals.acquireControl(businessFor(id),id,body(exchange,TerminalControlAcquireRequest.class),key,holder,clientSessionRemainingTtl(exchange))); return true; }
            if ("renew".equals(p[6])) { sendJson(exchange,200,terminals.renewControl(businessFor(id),id,body(exchange,TerminalControlRenewRequest.class),key,holder,clientSessionRemainingTtl(exchange))); return true; }
            if ("release".equals(p[6])) { sendJson(exchange,200,terminals.releaseControl(businessFor(id),id,body(exchange,TerminalControlReleaseRequest.class),key,holder)); return true; }
            throw methodNotAllowed();
        }
        if (p.length == 6 && "input".equals(p[5]) && "POST".equals(method)) { String holder=authenticatedHolder(exchange); sendJson(exchange,202,terminals.input(businessFor(id),id,body(exchange,TerminalInputRequest.class),mutationKey(exchange),holder)); return true; }
        if (p.length == 6 && "resize".equals(p[5]) && "POST".equals(method)) { String holder=authenticatedHolder(exchange); sendJson(exchange,200,terminals.resize(businessFor(id),id,body(exchange,TerminalResizeRequest.class),mutationKey(exchange),holder)); return true; }
        if (p.length == 6 && "signals".equals(p[5]) && "POST".equals(method)) { String holder=authenticatedHolder(exchange); sendJson(exchange,200,terminals.signal(businessFor(id),id,body(exchange,TerminalSignalRequest.class),mutationKey(exchange),holder)); return true; }
        if (p.length == 5 && "DELETE".equals(method)) { String holder=authenticatedHolder(exchange); sendJson(exchange,200,terminals.close(businessFor(id),id,body(exchange,TerminalCloseRequest.class),mutationKey(exchange),holder)); return true; }
        throw methodNotAllowed();
    }
    private boolean owns(String path){return path.matches("/api/v2/sessions/[^/]+/terminal-sessions")||path.matches("/api/v2/terminal-sessions/[^/]+")||path.matches("/api/v2/terminal-sessions/[^/]+/(output|input|resize|signals)")||path.matches("/api/v2/terminal-sessions/[^/]+/control/(acquire|renew|release)")||path.matches("/api/v2/terminal-sessions/[^/]+/output/live");}
    private String businessFor(String id){return findById(id).getBusinessSessionId();}
    private com.sage.business.code.contract.v2.terminal.TerminalSessionResponse findById(String id){return terminals.getById(id);}
    /** The dispatcher has already authenticated this token against ClientSessionRegistry. */
    private String authenticatedHolder(HttpExchange exchange){
        String holder = optionalHolder(exchange);
        if (holder == null) throw new com.sage.business.code.domain.BusinessException(
                "CLIENT_REGISTRATION_REQUIRED", 428,
                "an authenticated client session is required for terminal control");
        return holder;
    }
    private String optionalHolder(HttpExchange exchange) {
        Object holder = exchange.getAttribute(V2ApiDispatcher.CLIENT_SESSION_ID_ATTRIBUTE);
        if (holder == null) return null;
        String value = String.valueOf(holder).trim();
        return value.isEmpty() ? null : value;
    }
    private long clientSessionRemainingTtl(HttpExchange exchange) {
        Object value = exchange.getAttribute(
                V2ApiDispatcher.CLIENT_SESSION_REMAINING_TTL_ATTRIBUTE);
        if (!(value instanceof Long) || ((Long) value).longValue() <= 0L) {
            throw new com.sage.business.code.domain.BusinessException(
                    "CLIENT_SESSION_EXPIRED", 401,
                    "client session has no remaining lifetime");
        }
        return ((Long) value).longValue();
    }
    private void stream(HttpExchange exchange,String id)throws IOException{
        String business=businessFor(id);
        String cursor=queryParam(exchange,"cursor");
        int max=intParam(exchange,"maxBytes",16384);
        long requestedWait = longParam(exchange,"waitMillis",30_000L);
        if (requestedWait < 0L || requestedWait > 30_000L) {
            throw new com.sage.business.code.domain.BusinessException(
                    "INVALID_REQUEST", 400, "waitMillis is outside the allowed range");
        }
        long wait=requestedWait;
        LongRequestAdmission.Handle reservation = longRequests.reserve(
                LongRequestAdmission.Kind.TERMINAL_STREAM, "local-owner", id);
        try {
            exchange.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control","no-cache, no-transform");
            exchange.getResponseHeaders().set("X-Accel-Buffering","no");
            exchange.sendResponseHeaders(200,0L);
            try(java.io.OutputStream out=exchange.getResponseBody()){
                boolean eof=false;
                while(!eof){
                    com.sage.business.code.contract.v2.terminal.TerminalOutputResponse page=
                            terminals.read(business,id,cursor,max,wait,"RAW_VT");
                    byte[] frame=("event: terminal.output\ndata: "+mapper.writeValueAsString(page)+"\n\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    if (frame.length > MAX_SSE_FRAME_BYTES) {
                        out.write(": frame-limit-exceeded; reconnect with cursor\n\n"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        out.flush();
                        break;
                    }
                    out.write(frame);
                    out.flush();
                    cursor=page.getNextCursor();
                    eof=page.isEof();
                }
            }
        } finally {
            reservation.close();
        }
    }
    private String mode(HttpExchange x){String raw=queryParam(x,"mode");return "RAW_VT".equals(raw)?"RAW_VT":"PLAIN_TEXT";}
    private int intParam(HttpExchange x,String name,int fallback){long v=longParam(x,name,fallback);if(v<1L||v>262144L)throw new com.sage.business.code.domain.BusinessException("TERMINAL_OUTPUT_LIMIT",422,name+" is outside the allowed range");return (int)v;}
}
