import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class API_Charge_Map {

    private static final HttpClient client = HttpClient.newHttpClient();
    private static HttpServer activeServer = null;
    private static int activePort = 8080;

    // Optional: If you have an Open Charge Map API key, paste it here.
    private static final String OCM_API_KEY = "";

    // Thread-safe storage for current route data JSON served to index.html UI
    private static volatile String currentRouteJson = buildInitialDefaultRouteJson();

    public static void main(String[] args) {
        HttpServer server = startServer(8080);
        if (server != null) {
            String url = "http://localhost:" + activePort;
            System.out.println("============================================================");
            System.out.println("🚀 VOLT Route Finder Web App Running at: " + url);
            System.out.println("============================================================");

            // Automatically open default web browser
            try {
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                    java.awt.Desktop.getDesktop().browse(new URI(url));
                } else {
                    System.out.println("Open your web browser and navigate to: " + url);
                }
            } catch (Exception e) {
                System.out.println("Could not launch browser automatically. Please visit: " + url);
            }
        }
    }

    public static class ChargerInfo {
        public String title = "EV Charging Station";
        public String address = "Location Along Route";
        public String operator = "Unknown Operator";
        public String connType = "Standard EV Plug";
        public String powerKw = "N/A";
        public double lat;
        public double lon;
        public double distance = -1;
    }

    /**
     * Geocodes start and end locations, finds an EV charging station along the route (near midpoint),
     * and updates the route JSON for the map UI.
     */
    public static boolean planRouteWithCharger(String startQuery, String endQuery) {
        if (startQuery == null || startQuery.trim().isEmpty() || endQuery == null || endQuery.trim().isEmpty()) {
            System.out.println("❌ Error: Both Start and End locations must be provided.");
            return false;
        }

        System.out.println("\n🔍 Resolving Start Location: \"" + startQuery + "\"...");
        double[] startCoords = resolveLocation(startQuery);
        if (startCoords == null) {
            System.out.println("❌ Could not locate Start Location: \"" + startQuery + "\". Please check spelling.");
            return false;
        }
        System.out.printf("  ✔ Start Coordinates: Latitude %.4f, Longitude %.4f%n", startCoords[0], startCoords[1]);

        System.out.println("🔍 Resolving End Location: \"" + endQuery + "\"...");
        double[] endCoords = resolveLocation(endQuery);
        if (endCoords == null) {
            System.out.println("❌ Could not locate End Location: \"" + endQuery + "\". Please check spelling.");
            return false;
        }
        System.out.printf("  ✔ End Coordinates:   Latitude %.4f, Longitude %.4f%n", endCoords[0], endCoords[1]);

        // Calculate Midpoint along the route
        double midLat = (startCoords[0] + endCoords[0]) / 2.0;
        double midLon = (startCoords[1] + endCoords[1]) / 2.0;

        System.out.println("⚡ Searching Open Charge Map for EV Chargers near route midpoint...");
        ChargerInfo charger = findChargerNearPoint(midLat, midLon);

        if (charger == null) {
            System.out.println("  ⚠️ No charger found near midpoint. Searching near start location...");
            charger = findChargerNearPoint(startCoords[0], startCoords[1]);
        }

        if (charger == null) {
            charger = new ChargerInfo();
            charger.title = "Route Waypoint Charger";
            charger.address = String.format("%.4f, %.4f", midLat, midLon);
            charger.lat = midLat;
            charger.lon = midLon;
        }

        // Update JSON served to UI
        currentRouteJson = buildRouteJson(startQuery, startCoords[0], startCoords[1],
                endQuery, endCoords[0], endCoords[1], charger);

        System.out.println("✅ Route updated successfully!");
        return true;
    }

    public static double[] resolveLocation(String location) {
        if (location == null || location.trim().isEmpty())
            return null;
        location = location.trim();

        Pattern coordPattern = Pattern.compile("^\\s*([-+]?\\d+(?:\\.\\d+)?)[,\\s]+([-+]?\\d+(?:\\.\\d+)?)\\s*$");
        Matcher coordMatcher = coordPattern.matcher(location);
        if (coordMatcher.matches()) {
            double lat = Double.parseDouble(coordMatcher.group(1));
            double lon = Double.parseDouble(coordMatcher.group(2));
            return new double[] { lat, lon };
        }

        return geocodeLocation(location);
    }

    public static ChargerInfo findChargerNearPoint(double latitude, double longitude) {
        try {
            StringBuilder urlBuilder = new StringBuilder("https://api.openchargemap.org/v3/poi?");
            urlBuilder.append("latitude=").append(latitude);
            urlBuilder.append("&longitude=").append(longitude);
            urlBuilder.append("&maxresults=5");
            urlBuilder.append("&distanceunit=Miles");

            if (OCM_API_KEY != null && !OCM_API_KEY.isEmpty()) {
                urlBuilder.append("&key=").append(URLEncoder.encode(OCM_API_KEY, StandardCharsets.UTF_8));
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlBuilder.toString()))
                    .header("User-Agent", "VOLT-App/1.0")
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return parseChargerInfo(response.body());
            }
        } catch (Exception e) {
            System.err.println("Failed to fetch charger from Open Charge Map: " + e.getMessage());
        }
        return null;
    }

    public static ChargerInfo parseChargerInfo(String json) {
        if (json == null || json.trim().equals("[]") || !json.contains("\"AddressInfo\"")) {
            return null;
        }

        String addressBlock = extractNestedObject(json, "AddressInfo");
        String operatorBlock = extractNestedObject(json, "OperatorInfo");
        String connectionBlock = extractNestedArray(json, "Connections");

        ChargerInfo info = new ChargerInfo();
        info.title = extract(addressBlock, "\"Title\"\\s*:\\s*\"([^\"]+)\"");
        String line1 = extract(addressBlock, "\"AddressLine1\"\\s*:\\s*\"([^\"]+)\"");
        String town = extract(addressBlock, "\"Town\"\\s*:\\s*\"([^\"]+)\"");
        String state = extract(addressBlock, "\"StateOrProvince\"\\s*:\\s*\"([^\"]+)\"");
        String postcode = extract(addressBlock, "\"Postcode\"\\s*:\\s*\"([^\"]+)\"");

        StringBuilder addrSb = new StringBuilder();
        if (!line1.equals("N/A")) addrSb.append(line1);
        if (!town.equals("N/A")) { if (addrSb.length() > 0) addrSb.append(", "); addrSb.append(town); }
        if (!state.equals("N/A")) { if (addrSb.length() > 0) addrSb.append(", "); addrSb.append(state); }
        if (!postcode.equals("N/A")) { if (addrSb.length() > 0) addrSb.append(" "); addrSb.append(postcode); }
        info.address = addrSb.length() > 0 ? addrSb.toString() : "Address N/A";

        String latStr = extract(addressBlock, "\"Latitude\"\\s*:\\s*([0-9.-]+)");
        String lonStr = extract(addressBlock, "\"Longitude\"\\s*:\\s*([0-9.-]+)");
        String distStr = extract(addressBlock, "\"Distance\"\\s*:\\s*([0-9.]+)");

        if (!latStr.equals("N/A")) info.lat = Double.parseDouble(latStr);
        if (!lonStr.equals("N/A")) info.lon = Double.parseDouble(lonStr);
        if (!distStr.equals("N/A")) info.distance = Double.parseDouble(distStr);

        info.operator = extract(operatorBlock, "\"Title\"\\s*:\\s*\"([^\"]+)\"");
        info.connType = extract(connectionBlock, "\"Title\"\\s*:\\s*\"([^\"]+)\"");
        info.powerKw = extract(connectionBlock, "\"PowerKW\"\\s*:\\s*([0-9.]+)");

        if (info.title.equals("N/A")) info.title = "EV Charging Station";
        if (info.operator.equals("N/A")) info.operator = "Public Network";
        if (info.connType.equals("N/A")) info.connType = "Standard EV Plug";

        return info;
    }

    public static double[] geocodeLocation(String address) {
        if (address == null) return null;
        address = address.trim();

        double[] coords = queryNominatim(address);
        if (coords != null) return coords;

        if (address.matches("^\\d{5}(-\\d{4})?$")) {
            coords = queryNominatim(address + ", USA");
            if (coords != null) return coords;
        }

        if (address.contains(",")) {
            String[] parts = address.split(",");
            StringBuilder reversed = new StringBuilder();
            for (int i = parts.length - 1; i >= 0; i--) {
                reversed.append(parts[i].trim());
                if (i > 0) reversed.append(", ");
            }
            coords = queryNominatim(reversed.toString());
            if (coords != null) return coords;
        }

        String withoutNumber = address.replaceAll("^\\d+\\s+", "").replaceAll(",\\s*\\d+\\s+", ", ");
        if (!withoutNumber.equalsIgnoreCase(address)) {
            coords = queryNominatim(withoutNumber);
            if (coords != null) return coords;
        }

        return null;
    }

    private static double[] queryNominatim(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://nominatim.openstreetmap.org/search?q=" + encoded + "&format=json&limit=1";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "VOLT-App/1.0 (contact: student@volt.edu)")
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();

            Matcher latMatcher = Pattern.compile("\"lat\":\"([^\"]+)\"").matcher(body);
            Matcher lonMatcher = Pattern.compile("\"lon\":\"([^\"]+)\"").matcher(body);

            if (latMatcher.find() && lonMatcher.find()) {
                double lat = Double.parseDouble(latMatcher.group(1));
                double lon = Double.parseDouble(lonMatcher.group(1));
                return new double[] { lat, lon };
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String extractNestedObject(String text, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\{");
        Matcher m = pattern.matcher(text);
        if (!m.find()) return "";
        int openBrace = m.end() - 1;
        int depth = 0;
        for (int i = openBrace; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(openBrace + 1, i);
            }
        }
        return "";
    }

    private static String extractNestedArray(String text, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[");
        Matcher m = pattern.matcher(text);
        if (!m.find()) return "";
        int openBracket = m.end() - 1;
        int depth = 0;
        for (int i = openBracket; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return text.substring(openBracket + 1, i);
            }
        }
        return "";
    }

    private static String extract(String json, String regex) {
        if (json == null || json.isEmpty()) return "N/A";
        Matcher m = Pattern.compile(regex).matcher(json);
        return m.find() ? m.group(1).replace("\\u0022", "\"").replace("\\/", "/") : "N/A";
    }

    private static String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String buildRouteJson(String startName, double startLat, double startLon,
                                         String endName, double endLat, double endLon,
                                         ChargerInfo charger) {
        long updatedAt = System.currentTimeMillis();
        return "{"
                + "\"status\":\"success\","
                + "\"updatedAt\":" + updatedAt + ","
                + "\"start\":{\"name\":\"" + escapeJson(startName) + "\",\"lat\":" + startLat + ",\"lon\":" + startLon + "},"
                + "\"end\":{\"name\":\"" + escapeJson(endName) + "\",\"lat\":" + endLat + ",\"lon\":" + endLon + "},"
                + "\"charger\":{"
                + "\"title\":\"" + escapeJson(charger.title) + "\","
                + "\"address\":\"" + escapeJson(charger.address) + "\","
                + "\"operator\":\"" + escapeJson(charger.operator) + "\","
                + "\"connType\":\"" + escapeJson(charger.connType) + "\","
                + "\"powerKw\":\"" + escapeJson(charger.powerKw) + "\","
                + "\"lat\":" + charger.lat + ","
                + "\"lon\":" + charger.lon
                + "}"
                + "}";
    }

    private static String buildInitialDefaultRouteJson() {
        ChargerInfo defaultCharger = new ChargerInfo();
        defaultCharger.title = "Dover EV Charging Station";
        defaultCharger.address = "Clapboardtree St, Dover, MA";
        defaultCharger.operator = "ChargePoint";
        defaultCharger.connType = "J1772";
        defaultCharger.powerKw = "7.2";
        defaultCharger.lat = 42.2085;
        defaultCharger.lon = -71.2257;

        return buildRouteJson("Xaverian Brothers High School", 42.2033, -71.2185,
                "Dunkin' High St Westwood", 42.2132, -71.2330,
                defaultCharger);
    }

    // =========================================================================
    // HTTP WEB SERVER IMPLEMENTATION
    // =========================================================================

    public static HttpServer startServer(int preferredPort) {
        int port = preferredPort;
        while (port < preferredPort + 20) {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
                server.createContext("/", new StaticFileHandler());
                server.createContext("/api/route", new RouteApiHandler());
                server.createContext("/api/health", new HealthCheckHandler(port));

                server.setExecutor(Executors.newCachedThreadPool());
                server.start();

                activeServer = server;
                activePort = port;
                return server;
            } catch (IOException e) {
                port++;
            }
        }
        System.err.println("Error: Could not bind HTTP server to any port in range " + preferredPort + "-" + (port - 1));
        return null;
    }

    public static void stopServer() {
        if (activeServer != null) {
            activeServer.stop(0);
            activeServer = null;
        }
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQueryParams(URI uri) {
        Map<String, String> map = new HashMap<>();
        if (uri == null || uri.getRawQuery() == null) return map;
        String query = uri.getRawQuery();
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length > 1) {
                map.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private static String getMimeType(String filename) {
        if (filename.endsWith(".html") || filename.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (filename.endsWith(".css")) return "text/css; charset=UTF-8";
        if (filename.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (filename.endsWith(".json")) return "application/json; charset=UTF-8";
        if (filename.endsWith(".png")) return "image/png";
        return "text/plain; charset=UTF-8";
    }

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            byte[] fileBytes = loadStaticFileBytes(path);

            if (fileBytes == null) {
                String error = "404 Not Found: Could not locate file or index.html.";
                byte[] bytes = error.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                exchange.sendResponseHeaders(404, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }

            String contentType = getMimeType(path == null || path.equals("/") ? "index.html" : path);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, fileBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(fileBytes);
            }
        }
    }

    private static byte[] loadStaticFileBytes(String requestedPath) {
        String path = (requestedPath == null || requestedPath.equals("/") || requestedPath.isEmpty())
                ? "index.html" : requestedPath;
        if (path.startsWith("/")) path = path.substring(1);

        File file = new File(path);
        if (file.exists() && !file.isDirectory()) {
            try { return Files.readAllBytes(file.toPath()); } catch (IOException ignored) {}
        }

        File indexFile = new File("index.html");
        if (indexFile.exists() && !indexFile.isDirectory()) {
            try { return Files.readAllBytes(indexFile.toPath()); } catch (IOException ignored) {}
        }

        return getEmbeddedIndexHtml().getBytes(StandardCharsets.UTF_8);
    }

    private static String getEmbeddedIndexHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "  <meta charset=\"utf-8\" />\n" +
                "  <title>VOLT - EV Route & Charger Finder</title>\n" +
                "  <meta name=\"viewport\" content=\"initial-scale=1,maximum-scale=1,user-scalable=no\" />\n" +
                "  <link href=\"https://api.mapbox.com/mapbox-gl-js/v3.0.1/mapbox-gl.css\" rel=\"stylesheet\" />\n" +
                "  <style>\n" +
                "    body { margin: 0; padding: 0; font-family: sans-serif; }\n" +
                "    #map { position: absolute; top: 0; bottom: 0; width: 100%; }\n" +
                "    #instructions {\n" +
                "      position: absolute; top: 10px; left: 10px; width: 320px; max-height: 88vh;\n" +
                "      overflow-y: auto; background: rgba(255, 255, 255, 0.96); padding: 16px;\n" +
                "      border-radius: 8px; box-shadow: 0 4px 14px rgba(0,0,0,0.25); z-index: 10;\n" +
                "    }\n" +
                "    h3 { margin-top: 0; margin-bottom: 12px; font-size: 18px; color: #111; }\n" +
                "    label { display: block; font-size: 12px; font-weight: bold; margin-bottom: 4px; color: #444; }\n" +
                "    input[type=\"text\"] {\n" +
                "      width: 100%; padding: 8px; margin-bottom: 10px; box-sizing: border-box;\n" +
                "      border: 1px solid #ccc; border-radius: 4px; font-size: 13px;\n" +
                "    }\n" +
                "    button {\n" +
                "      width: 100%; padding: 10px; background-color: #0066FF; color: white; border: none;\n" +
                "      border-radius: 4px; font-weight: bold; cursor: pointer; font-size: 14px;\n" +
                "    }\n" +
                "    button:hover { background-color: #0052cc; }\n" +
                "    p { margin: 4px 0; font-size: 13px; }\n" +
                "    ol { padding-left: 20px; margin: 8px 0 0 0; font-size: 12px; }\n" +
                "    li { margin-bottom: 6px; }\n" +
                "    .badge { display: inline-block; padding: 2px 6px; border-radius: 4px; font-size: 11px; font-weight: bold; color: white; }\n" +
                "    .badge-start { background-color: #00704A; }\n" +
                "    .badge-charger { background-color: #0066FF; }\n" +
                "    .badge-end { background-color: #FF6600; }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div id=\"map\"></div>\n" +
                "<div id=\"instructions\">\n" +
                "  <h3>⚡ VOLT Route Planner</h3>\n" +
                "  <form id=\"route-form\" onsubmit=\"submitRoute(event)\">\n" +
                "    <label for=\"startInput\">Start Location</label>\n" +
                "    <input type=\"text\" id=\"startInput\" placeholder=\"e.g. Boston, MA\" required />\n" +
                "    <label for=\"endInput\">End Location</label>\n" +
                "    <input type=\"text\" id=\"endInput\" placeholder=\"e.g. Providence, RI\" required />\n" +
                "    <button type=\"submit\" id=\"btnSubmit\">Plan Route</button>\n" +
                "  </form>\n" +
                "  <hr style=\"margin:14px 0; border:0; border-top:1px solid #eee;\"/>\n" +
                "  <div id=\"route-details\">Loading route map...</div>\n" +
                "</div>\n" +
                "<script src=\"https://api.mapbox.com/mapbox-gl-js/v3.0.1/mapbox-gl.js\"></script>\n" +
                "<script>\n" +
                "  const accessToken = 'pk.eyJ1IjoibWFkZG94bCIsImEiOiJjbXU3OG1vbWgwanBwMnpwdDVwaGtndTRqIn0.xreTdZS3Gu9ThwR2brfAFw';\n" +
                "  mapboxgl.accessToken = accessToken;\n" +
                "  let startMarker = null, endMarker = null, chargerMarker = null, lastUpdatedAt = 0;\n" +
                "  const map = new mapboxgl.Map({ container: 'map', style: 'mapbox://styles/mapbox/streets-v12', center: [-71.225, 42.208], zoom: 12 });\n" +
                "\n" +
                "  function escapeHtml(str) { return str ? String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\"/g, '&quot;') : ''; }\n" +
                "\n" +
                "  async function submitRoute(e) {\n" +
                "    if (e) e.preventDefault();\n" +
                "    const start = document.getElementById('startInput').value;\n" +
                "    const end = document.getElementById('endInput').value;\n" +
                "    const btn = document.getElementById('btnSubmit');\n" +
                "    btn.disabled = true;\n" +
                "    btn.innerText = 'Searching Route...';\n" +
                "    try {\n" +
                "      const res = await fetch(`/api/route?start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}`);\n" +
                "      if (res.ok) await getRoute(true);\n" +
                "      else alert('Failed to resolve route locations.');\n" +
                "    } catch(err) {\n" +
                "      console.error(err);\n" +
                "      alert('Server error planning route.');\n" +
                "    } finally {\n" +
                "      btn.disabled = false;\n" +
                "      btn.innerText = 'Plan Route';\n" +
                "    }\n" +
                "  }\n" +
                "\n" +
                "  async function getRoute(force = false) {\n" +
                "    try {\n" +
                "      const response = await fetch('/api/route');\n" +
                "      if (!response.ok) return;\n" +
                "      const data = await response.json();\n" +
                "      if (data.status !== 'success' || (!force && data.updatedAt && data.updatedAt === lastUpdatedAt)) return;\n" +
                "      lastUpdatedAt = data.updatedAt;\n" +
                "      if (!document.getElementById('startInput').value) document.getElementById('startInput').value = data.start.name;\n" +
                "      if (!document.getElementById('endInput').value) document.getElementById('endInput').value = data.end.name;\n" +
                "      const origin = [data.start.lon, data.start.lat];\n" +
                "      const destination = [data.end.lon, data.end.lat];\n" +
                "      const charger = [data.charger.lon, data.charger.lat];\n" +
                "      if (startMarker) startMarker.remove();\n" +
                "      if (endMarker) endMarker.remove();\n" +
                "      if (chargerMarker) chargerMarker.remove();\n" +
                "      startMarker = new mapboxgl.Marker({ color: '#00704A' }).setLngLat(origin).setPopup(new mapboxgl.Popup().setHTML(`<b>🟢 Start:</b> ${escapeHtml(data.start.name)}`)).addTo(map);\n" +
                "      const chargerPopupHtml = `<div style=\"font-family: sans-serif; max-width:220px;\"><h4 style=\"margin:0 0 4px 0; color:#0066FF;\">⚡ ${escapeHtml(data.charger.title)}</h4><p style=\"margin:2px 0; font-size:12px;\"><b>Address:</b> ${escapeHtml(data.charger.address)}</p><p style=\"margin:2px 0; font-size:12px;\"><b>Operator:</b> ${escapeHtml(data.charger.operator)}</p><p style=\"margin:2px 0; font-size:12px;\"><b>Plug:</b> ${escapeHtml(data.charger.connType)} (${escapeHtml(data.charger.powerKw)} kW)</p></div>`;\n" +
                "      chargerMarker = new mapboxgl.Marker({ color: '#0066FF' }).setLngLat(charger).setPopup(new mapboxgl.Popup().setHTML(chargerPopupHtml)).addTo(map);\n" +
                "      endMarker = new mapboxgl.Marker({ color: '#FF6600' }).setLngLat(destination).setPopup(new mapboxgl.Popup().setHTML(`<b>🏁 Destination:</b> ${escapeHtml(data.end.name)}`)).addTo(map);\n" +
                "      const bounds = new mapboxgl.LngLatBounds(); bounds.extend(origin); bounds.extend(charger); bounds.extend(destination);\n" +
                "      map.fitBounds(bounds, { padding: 70, maxZoom: 15 });\n" +
                "      const directionsUrl = `https://api.mapbox.com/directions/v5/mapbox/driving/${origin[0]},${origin[1]};${charger[0]},${charger[1]};${destination[0]},${destination[1]}?steps=true&geometries=geojson&access_token=${accessToken}`;\n" +
                "      const dirResponse = await fetch(directionsUrl); const dirData = await dirResponse.json();\n" +
                "      if (dirData.routes && dirData.routes.length > 0) {\n" +
                "        const route = dirData.routes[0];\n" +
                "        if (map.getSource('route')) { map.getSource('route').setData({ type: 'Feature', geometry: route.geometry }); }\n" +
                "        else { map.addSource('route', { type: 'geojson', data: { type: 'Feature', geometry: route.geometry } }); map.addLayer({ id: 'route', type: 'line', source: 'route', layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': '#0066FF', 'line-width': 5, 'line-opacity': 0.8 } }); }\n" +
                "        const durationMins = Math.round(route.duration / 60); const distanceMiles = (route.distance / 1609.34).toFixed(2);\n" +
                "        let stepsHtml = `<p><span class=\"badge badge-start\">START</span> <strong>${escapeHtml(data.start.name)}</strong></p><p><span class=\"badge badge-charger\">CHARGER STOP</span> <strong>${escapeHtml(data.charger.title)}</strong><br/><small style=\"color:#555;\">${escapeHtml(data.charger.address)}<br/>Operator: ${escapeHtml(data.charger.operator)} | Plug: ${escapeHtml(data.charger.connType)} (${escapeHtml(data.charger.powerKw)} kW)</small></p><p><span class=\"badge badge-end\">END</span> <strong>${escapeHtml(data.end.name)}</strong></p><hr style=\"margin:10px 0; border:0; border-top:1px solid #eee;\"/><p><strong>Total Distance:</strong> ${distanceMiles} miles</p><p><strong>Est. Drive Time:</strong> ~${durationMins} mins</p><hr style=\"margin:10px 0; border:0; border-top:1px solid #eee;\"/><strong>Driving Directions:</strong><ol style=\"padding-left:18px; margin:6px 0;\">`;\n" +
                "        route.legs.forEach((leg, legIdx) => {\n" +
                "          const legLabel = legIdx === 0 ? \"Leg 1: Start ➔ Charger Stop\" : \"Leg 2: Charger Stop ➔ Destination\";\n" +
                "          stepsHtml += `<li style=\"font-weight:bold; list-style:none; margin-left:-18px; margin-top:8px; color:#333;\">📍 ${legLabel}</li>`;\n" +
                "          leg.steps.forEach(step => { stepsHtml += `<li>${step.maneuver.instruction}</li>`; });\n" +
                "        });\n" +
                "        stepsHtml += `</ol>`; document.getElementById('route-details').innerHTML = stepsHtml;\n" +
                "      }\n" +
                "    } catch (error) { console.error('Error loading route:', error); }\n" +
                "  }\n" +
                "  map.on('load', () => { getRoute(); });\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    static class RouteApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            Map<String, String> params = parseQueryParams(exchange.getRequestURI());
            String startParam = params.get("start");
            String endParam = params.get("end");

            if (startParam != null && !startParam.trim().isEmpty() && endParam != null && !endParam.trim().isEmpty()) {
                planRouteWithCharger(startParam, endParam);
            }

            sendJsonResponse(exchange, 200, currentRouteJson);
        }
    }

    static class HealthCheckHandler implements HttpHandler {
        private final int port;

        public HealthCheckHandler(int port) {
            this.port = port;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            sendJsonResponse(exchange, 200, "{\"status\":\"ok\",\"port\":" + port + "}");
        }
    }
}