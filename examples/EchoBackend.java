import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Local demo only. Run with: java examples/EchoBackend.java 9001 */
public class EchoBackend {
    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 9001 : Integer.parseInt(args[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        var workers = Executors.newFixedThreadPool(4);
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            try (exchange) {
                byte[] requestBody = exchange.getRequestBody().readNBytes(10 * 1024 * 1024 + 1);
                if (requestBody.length > 10 * 1024 * 1024) {
                    exchange.sendResponseHeaders(413, -1);
                    return;
                }
                String text = exchange.getRequestMethod() + " " + exchange.getRequestURI()
                        + "\nbackend=" + server.getAddress().getPort() + "\n"
                        + new String(requestBody, StandardCharsets.UTF_8);
                byte[] body = text.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.getResponseHeaders().set("X-Demo-Backend", Integer.toString(server.getAddress().getPort()));
                boolean head = exchange.getRequestMethod().equals("HEAD");
                exchange.sendResponseHeaders(200, head ? -1 : body.length);
                if (!head) {
                    exchange.getResponseBody().write(body);
                }
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            workers.shutdownNow();
        }));
        server.start();
        System.out.println("Demo backend listening on " + server.getAddress());
    }
}
