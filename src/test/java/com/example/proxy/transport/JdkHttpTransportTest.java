package com.example.proxy.transport;

import static org.junit.jupiter.api.Assertions.*;

import com.example.proxy.support.ProxyTestSupport;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
class JdkHttpTransportTest {
    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PATCH"})
    void sendsOnlyOneRequestWhenThePeerClosesWithoutAResponse(String method) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean stopping = new AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Path output = ProxyTestSupport.testDirectory("retry-probe-").resolve("probe.log");
        Process child = null;
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            Future<?> accept = executor.submit(() -> {
                while (!stopping.get()) {
                    try (Socket socket = listener.accept()) {
                        socket.setSoTimeout(3000);
                        int matched = 0;
                        byte[] end = {'\r', '\n', '\r', '\n'};
                        while (matched < end.length) {
                            int value = socket.getInputStream().read();
                            if (value < 0) {
                                throw new IOException("Incomplete request headers");
                            }
                            matched = value == end[matched] ? matched + 1 : (value == '\r' ? 1 : 0);
                        }
                        requests.incrementAndGet();
                        // Close after reading the request but before sending any response headers.
                    } catch (IOException failure) {
                        if (!stopping.get()) {
                            throw new AssertionError(failure);
                        }
                    }
                }
            });
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            child = new ProcessBuilder(java,
                    "-Djdk.httpclient.disableRetryConnect=false", "-Djdk.httpclient.enableAllMethodRetry=true",
                    "-Djdk.httpclient.redirects.retrylimit=5", "-cp", classpath,
                    NoRetryProbe.class.getName(), "http://127.0.0.1:" + listener.getLocalPort() + "/fault", method)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            // A fresh JVM's TLS/SecureRandom initialization takes about 9s on this Windows host.
            // The actual network exchange still has its own 2s request timeout.
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Probe process did not terminate");
            assertEquals(0, child.exitValue(), Files.readString(output));
            assertTrue(Files.readString(output).contains("EXPECTED_IO_FAILURE"));
            stopping.set(true);
            listener.close();
            accept.get(2, TimeUnit.SECONDS);
            assertEquals(1, requests.get(), "HttpClient repeated the HTTP request internally");
        } finally {
            stopping.set(true);
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(3, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
