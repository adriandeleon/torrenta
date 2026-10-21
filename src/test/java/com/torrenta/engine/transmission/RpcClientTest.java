package com.torrenta.engine.transmission;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.torrenta.engine.EngineException;
import com.torrenta.engine.EngineProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The RPC transport against a stand-in daemon: the session-id handshake, auth, the daemon's refusals. */
class RpcClientTest {

    private StubRpcServer server;

    @BeforeEach
    void start() throws Exception {
        server = new StubRpcServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private RpcClient client() {
        return new RpcClient(profile("", ""), RpcClient.defaultClient());
    }

    private EngineProfile profile(String user, String password) {
        return new EngineProfile("p", "Box", EngineProfile.Kind.REMOTE, server.hostUrl(), user, password);
    }

    @Test
    void theFirstCallTakesTheSessionIdFromThe409AndRetriesOnce() throws Exception {
        server.answer("session-get", StubRpcServer.success(Map.of("version", "4.1.0", "rpc-version", 18L)));
        RpcClient client = client();
        assertEquals("/transmission/rpc", client.endpoint().getPath(), "the path is added to a host URL");

        RpcClient.Daemon daemon = client.probe();
        assertEquals("4.1.0", daemon.version());
        assertEquals(18, daemon.rpcVersion());
        List<StubRpcServer.Call> calls = server.calls();
        assertEquals(2, calls.size(), "one 409, one real call");
        assertFalse(calls.get(0).hadSessionId());
        assertTrue(calls.get(1).hadSessionId());

        // The id is kept: the next call goes straight through.
        server.clearCalls();
        client.call("session-get", Map.of());
        assertEquals(1, server.calls().size());
        assertTrue(server.calls().get(0).hadSessionId());
    }

    @Test
    void credentialsTravelAsBasicAuthAndTheirAbsenceIsSaidPlainly() throws Exception {
        server.requireAuth("alice", "s3cret");
        server.answer("session-get", StubRpcServer.success(Map.of("version", "4.0.6", "rpc-version", 17L)));

        EngineException refused = assertThrows(EngineException.class, () -> client().probe());
        assertTrue(refused.getMessage().contains("wants a username and password"), refused.getMessage());

        EngineException wrong = assertThrows(
                EngineException.class,
                () -> new RpcClient(profile("alice", "nope"), RpcClient.defaultClient()).probe());
        assertTrue(wrong.getMessage().contains("refused the username or password"), wrong.getMessage());

        RpcClient ok = new RpcClient(profile("alice", "s3cret"), RpcClient.defaultClient());
        assertEquals("4.0.6", ok.probe().version());
        assertTrue(server.last("session-get").authorization().startsWith("Basic "));
    }

    @Test
    void aResultOtherThanSuccessIsTheDaemonsOwnWords() {
        server.answer("torrent-add", "{\"arguments\":{},\"result\":\"unrecognized info\"}");
        EngineException e =
                assertThrows(EngineException.class, () -> client().call("torrent-add", Map.of("filename", "x")));
        assertEquals("unrecognized info", e.getMessage());

        EngineException unknown = assertThrows(EngineException.class, () -> client().call("no-such-method", Map.of()));
        assertEquals("no method name", unknown.getMessage());
    }

    @Test
    void aDaemonThatIsNotThereAndOneThatTalksNonsenseAreBothSaid() throws Exception {
        int closed;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        RpcClient gone = new RpcClient(
                URI.create("http://127.0.0.1:" + closed + "/transmission/rpc"), "", "", RpcClient.defaultClient());
        EngineException e = assertThrows(EngineException.class, () -> gone.call("session-get", Map.of()));
        assertTrue(e.getMessage().startsWith("Cannot reach the daemon at 127.0.0.1:" + closed), e.getMessage());

        server.force(200, "<html>not json</html>");
        // The forced body skips the handshake, so this is a direct answer that is not JSON.
        EngineException nonsense = assertThrows(EngineException.class, () -> client().call("session-get", Map.of()));
        assertTrue(nonsense.getMessage().contains("was not JSON"), nonsense.getMessage());

        server.force(500, "boom");
        EngineException http = assertThrows(EngineException.class, () -> client().call("session-get", Map.of()));
        assertTrue(http.getMessage().contains("HTTP 500"), http.getMessage());
    }

    @Test
    void theRequestBodyIsMethodAndArguments() throws Exception {
        server.answer("torrent-set", StubRpcServer.success());
        client().call("torrent-set", Map.of("ids", List.of("abc"), "labels", List.of("a", "b")));
        StubRpcServer.Call call = server.last("torrent-set");
        assertEquals(List.of("abc"), call.arguments().get("ids"));
        assertEquals(List.of("a", "b"), call.arguments().get("labels"));
    }
}
