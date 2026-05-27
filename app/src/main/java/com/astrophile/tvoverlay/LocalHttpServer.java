package com.astrophile.tvoverlay;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;
import java.io.IOException;
import java.util.Map;
import fi.iki.elonen.NanoHTTPD;

/**
 * HTTP server yang berjalan di Android TV.
 * Kasir kirim perintah via HTTP POST ke IP TV port 8080.
 * Endpoint: POST /command  body: JSON { action, tvNum, duration, start, mode, pausedAt, expired, active }
 */
public class LocalHttpServer extends NanoHTTPD {

    private static final String TAG = "LocalHttpServer";
    public static final int PORT = 8080;

    public interface CommandListener {
        void onCommand(JSONObject payload);
    }

    private final CommandListener listener;

    public LocalHttpServer(CommandListener listener) throws IOException {
        super(PORT);
        this.listener = listener;
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        Log.i(TAG, "HTTP server started on port " + PORT);
    }

    @Override
    public Response serve(IHTTPSession session) {
        // CORS preflight
        if (session.getMethod() == Method.OPTIONS) {
            return corsResponse(newFixedLengthResponse("OK"));
        }

        if (session.getMethod() == Method.POST && session.getUri().equals("/command")) {
            try {
                Map<String, String> body = new java.util.HashMap<>();
                session.parseBody(body);
                String json = body.get("postData");
                if (json == null || json.isEmpty()) json = "{}";
                JSONObject payload = new JSONObject(json);
                Log.d(TAG, "Command received: " + payload);
                if (listener != null) listener.onCommand(payload);
                return corsResponse(newFixedLengthResponse("{"ok":true}"));
            } catch (Exception e) {
                Log.e(TAG, "Error: " + e.getMessage());
                return corsResponse(newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "application/json", "{"ok":false,"error":"" + e.getMessage() + ""}"));
            }
        }

        if (session.getMethod() == Method.GET && session.getUri().equals("/ping")) {
            return corsResponse(newFixedLengthResponse("{"ok":true,"server":"AstrophileTV"}"));
        }

        return corsResponse(newFixedLengthResponse(Response.Status.NOT_FOUND,
            "application/json", "{"ok":false,"error":"not found"}"));
    }

    private Response corsResponse(Response r) {
        r.addHeader("Access-Control-Allow-Origin", "*");
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        r.addHeader("Access-Control-Allow-Headers", "Content-Type");
        r.addHeader("Content-Type", "application/json");
        return r;
    }

    public void stopServer() {
        stop();
        Log.i(TAG, "HTTP server stopped");
    }
}
