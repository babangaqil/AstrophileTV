package com.astrophile.tvoverlay;

import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * HTTP server ringan — pakai Java ServerSocket bawaan, tanpa library eksternal.
 * Kasir kirim perintah via HTTP POST ke IP TV port 8080.
 * Endpoint: POST /command  body: JSON
 */
public class LocalHttpServer {

    private static final String TAG  = "LocalHttpServer";
    public  static final int    PORT = 8080;

    public interface CommandListener {
        void onCommand(JSONObject payload);
    }

    private final CommandListener listener;
    private ServerSocket serverSocket;
    private Thread       serverThread;
    private volatile boolean running = false;

    public LocalHttpServer(CommandListener listener) {
        this.listener = listener;
    }

    public void start() throws Exception {
        serverSocket = new ServerSocket(PORT);
        running = true;
        serverThread = new Thread(() -> {
            Log.i(TAG, "HTTP server listening on port " + PORT);
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleClient(client)).start();
                } catch (Exception e) {
                    if (running) Log.e(TAG, "accept error: " + e.getMessage());
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void handleClient(Socket client) {
        try {
            BufferedReader in  = new BufferedReader(new InputStreamReader(client.getInputStream()));
            OutputStream   out = client.getOutputStream();

            // Baca request line
            String requestLine = in.readLine();
            if (requestLine == null) { client.close(); return; }

            String method = requestLine.split(" ")[0];
            String path   = requestLine.split(" ").length > 1 ? requestLine.split(" ")[1] : "/";

            // Baca headers
            int contentLength = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(line.split(":")[1].trim()); }
                    catch (Exception ignored) {}
                }
            }

            // Baca body
            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int read = in.read(buf, 0, contentLength);
                if (read > 0) body = new String(buf, 0, read);
            }

            // CORS headers
            String cors = "Access-Control-Allow-Origin: *\r\n"
                        + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                        + "Access-Control-Allow-Headers: Content-Type\r\n";

            if (method.equals("OPTIONS")) {
                sendResponse(out, "200 OK", cors, "");
            } else if (method.equals("POST") && path.equals("/command")) {
                try {
                    JSONObject payload = new JSONObject(body.isEmpty() ? "{}" : body);
                    Log.d(TAG, "Command: " + payload);
                    if (listener != null) listener.onCommand(payload);
                    sendResponse(out, "200 OK", cors, "{\"ok\":true}");
                } catch (Exception e) {
                    Log.e(TAG, "parse error: " + e.getMessage());
                    sendResponse(out, "500 Internal Server Error", cors, "{\"ok\":false}");
                }
            } else if (method.equals("GET") && path.equals("/ping")) {
                try {
                    if (listener != null)
                        listener.onCommand(new JSONObject("{\"_cmd\":\"ping\"}"));
                } catch (Exception ignored) {}
                sendResponse(out, "200 OK", cors, "{\"ok\":true,\"server\":\"AstrophileTV\"}");
            } else {
                sendResponse(out, "404 Not Found", cors, "{\"ok\":false,\"error\":\"not found\"}");
            }

            client.close();
        } catch (Exception e) {
            Log.e(TAG, "handleClient: " + e.getMessage());
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private void sendResponse(OutputStream out, String status, String extraHeaders, String body) throws Exception {
        String response = "HTTP/1.1 " + status + "\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + body.getBytes("UTF-8").length + "\r\n"
                        + extraHeaders
                        + "Connection: close\r\n"
                        + "\r\n"
                        + body;
        out.write(response.getBytes("UTF-8"));
        out.flush();
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        Log.i(TAG, "HTTP server stopped");
    }
}
