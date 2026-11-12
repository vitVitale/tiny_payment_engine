package com.tpe.systemtests.harness;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A stand-in for a service jar in {@link ServiceProcessTest}: no Spring, no dependencies outside the JDK, so its one
 * class file packaged with a {@code Main-Class} manifest is a runnable jar. Listens on {@code --server.port}, answers
 * the readiness probe with {@code 200}, prints its arguments to its log and serves them at {@code /args}.
 */
public final class FakeService {

    private FakeService() {}

    public static void main(String[] args) throws IOException {
        int port = 0;
        for (String arg : args) {
            if (arg.startsWith("--server.port=")) {
                port = Integer.parseInt(arg.substring("--server.port=".length()));
            }
        }
        String joined = String.join(" ", args);
        System.out.println("fake service started with " + joined);
        var server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        server.createContext("/actuator/health/readiness", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/args", exchange -> {
            byte[] body = joined.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }
}
