import java.io.File;
import java.io.IOException;
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
import java.util.Scanner;
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
        System.out.printf("📍 Route Midpoint: Latitude %.4f, Longitude %.4f%n", midLat, midLon);

        System.out.println("⚡ Searching Open Charge Map for EV Chargers near route midpoint...");
        ChargerInfo charger = findChargerNearPoint(midLat, midLon);

        if (charger == null) {
            System.out.println("  ⚠️ No charger found near midpoint. Searching near start location...");
            charger = findChargerNearPoint(startCoords[0], startCoords[1]);
        }

        if (charger == null) {
            // Fallback charger object at midpoint
            charger = new ChargerInfo();
            charger.title = "Route Waypoint Charger";
            charger.address = String.format("%.4f, %.4f", midLat, midLon);
            charger.lat = midLat;
            charger.lon = midLon;
        }

        // Print Summary to Terminal
        System.out.println("\n============================================================");
        System.out.println("            ⚡ OPTIMAL EV ROUTE SELECTED ⚡                  ");
        System.out.println("============================================================");
        System.out.println("🟢 Start Point:     " + startQuery);
        System.out.println("⚡ Charging Station: " + charger.title);
        System.out.println("   Address:         " + charger.address);
        System.out.println("   Operator:        " + charger.operator);
        System.out.println("   Plug Type:       " + charger.connType + (charger.powerKw.equals("N/A") ? "" : " (" + charger.powerKw + " kW)"));
        System.out.println("   Coordinates:     " + charger.lat + ", " + charger.lon);
        System.out.println("🏁 End Point:       " + endQuery);
        System.out.println("============================================================");

        // Update JSON served to index.html UI
        currentRouteJson = buildRouteJson(startQuery, startCoords[0], startCoords[1],
                endQuery, endCoords[0], endCoords[1], charger);

        System.out.println("\n🌐 Map updated! Open or refresh http://localhost:" + activePort + " to view route on map.\n");
        return true;
    }

    /**
     * Resolves location string into [latitude, longitude].
     * Accepts address strings or raw coordinates like "42.2033, -71.2185".
     */
    public static double[] resolveLocation(String location) {
        if (location == null || location.trim().isEmpty()) return null;
        location = location.trim();

        // 1. Check if directly formatted as coordinates
        Pattern coordPattern = Pattern.compile("^\\s*([-+]?\\d+(?:\\.\\d+)?)[,\\s]+([-+]?\\d+(?:\\.\\d+)?)\\s*$");
        Matcher coordMatcher = coordPattern.matcher(location);
        if (coordMatcher.matches()) {
            double lat = Double.parseDouble(coordMatcher.group(1));
            double lon = Double.parseDouble(coordMatcher.group(2));
            return new double[] { lat, lon };
        }

        // 2. Geocode using Nominatim
        return geocodeLocation(location);
    }

    /**
     * Finds closest EV charger to a point using Open Charge Map API.
     */
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
            String responseBody = response.body();

            if (response.statusCode() == 200) {
                return parseChargerInfo(responseBody);
            }
        } catch (Exception e) {
            System.err.println("Failed to fetch charger from Open Charge Map: " + e.getMessage());
        }
        return null;
    }

    /**
     * Parses key fields from Open Charge Map JSON into a ChargerInfo object.
     */
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

    /**
     * Smart geocoder: resolves addresses, places, landmarks, ZIP codes using Nominatim.
     */
    public static double[] geocodeLocation(String address) {
        if (address == null) return null;
        address = address.trim();

        // Attempt 1: Exact query as entered
        double[] coords = queryNominatim(address);
        if (coords != null) return coords;

        // Attempt 2: If 5-digit US ZIP code, append USA
        if (address.matches("^\\d{5}(-\\d{4})?$")) {
            coords = queryNominatim(address + ", USA");
            if (coords != null) return coords;
        }

        // Attempt 3: If comma-separated, try reversed order
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

        // Attempt 4: Try removing street number
        String withoutNumber = address.replaceAll("^\\d+\\s+", "").replaceAll(",\\s*\\d+\\s+", ", ");
        if (!withoutNumber.equalsIgnoreCase(address)) {
            coords = queryNominatim(withoutNumber);
            if (coords != null) return coords;
        }

        // Attempt 5: Fallback to city/town part
        if (address.contains(",")) {
            String[] parts = address.split(",");
            for (String part : parts) {
                String cleanPart = part.trim();
                if (!cleanPart.matches(".*\\d+.*") && cleanPart.length() >= 3) {
                    coords = queryNominatim(cleanPart);
                    if (coords != null) return coords;
                }
            }
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

    private static String buildRouteJson(String startName, double startLat, double startLon,
                                         String endName, double endLat, double endLon,
                                         ChargerInfo charger) {
        long updatedAt = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("{")
                .append("\"status\":\"success\",")
                .append("\"updatedAt\":").append(updatedAt).append(",")
                .append("\"start\":{")
                .append("\"name\":\"").append(escapeJson(startName)).append("\",")
                .append("\"lat\":").append(startLat).append(",")
                .append("\"lon\":").append(startLon)
                .append("},")
                .append("\"end\":{")
                .append("\"name\":\"").append(escapeJson(endName)).append("\",")
                .append("\"lat\":").append(endLat).append(",")
                .append("\"lon\":").append(endLon)
                .append("},")
                .append("\"charger\":{")
                .append("\"title\":\"").append(escapeJson(charger.title)).append("\",")
                .append("\"address\":\"").append(escapeJson(charger.address)).append("\",")
                .append("\"operator\":\"").append(escapeJson(charger.operator)).append("\",")
                .append("\"connType\":\"").append(escapeJson(charger.connType)).append("\",")
                .append("\"powerKw\":\"").append(escapeJson(charger.powerKw)).append("\",")
                .append("\"lat\":").append(charger.lat).append(",")
                .append("\"lon\":").append(charger.lon)
                .append("}")
                .append("}");
        return sb.toString();
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

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (path == null || path.equals("/") || path.isEmpty()) {
                path = "/index.html";
            }
            if (path.startsWith("/")) {
                path = path.substring(1);
            }

            File file = new File(path);
            if (!file.exists() || file.isDirectory()) {
                file = new File("index.html");
            }

            if (!file.exists()) {
                String error = "404 Not Found: index.html not found in working directory.";
                byte[] bytes = error.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                exchange.sendResponseHeaders(404, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }

            String contentType = getMimeType(file.getName());
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, fileBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(fileBytes);
            }
        }
    }

    static class RouteApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            // Check if query params were passed directly to GET /api/route?start=...&end=...
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
        public HealthCheckHandler(int port) { this.port = port; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            String json = "{\"status\":\"ok\",\"service\":\"VOLT-Route-Backend\",\"port\":" + port + "}";
            sendJsonResponse(exchange, 200, json);
        }
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
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
        Map<String, String> params = new HashMap<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) return params;

        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                params.put(key, val);
            } else if (idx < 0) {
                String key = URLDecoder.decode(pair, StandardCharsets.UTF_8);
                params.put(key, "");
            }
        }
        return params;
    }

    private static String getMimeType(String filename) {
        if (filename.endsWith(".html") || filename.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (filename.endsWith(".css")) return "text/css; charset=UTF-8";
        if (filename.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (filename.endsWith(".json")) return "application/json; charset=UTF-8";
        if (filename.endsWith(".png")) return "image/png";
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
        if (filename.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // =========================================================================
    // MAIN ENTRYPOINT (Interactive Terminal Loop + Web Server)
    // =========================================================================

    public static void main(String[] args) {
        // Start background Web Server
        HttpServer server = startServer(8080);
        int port = (server != null) ? activePort : 8080;

        System.out.println("============================================================");
        System.out.println("      ⚡ VOLT - View Optimal Locations To charge ⚡          ");
        System.out.println("============================================================");
        if (server != null) {
            System.out.println("🌐 Interactive Web Map & API live at: http://localhost:" + port);
            System.out.println("   Open http://localhost:" + port + " in your browser!");
            System.out.println("------------------------------------------------------------");
        }

        // If arguments were provided (e.g. java API_Charge_Map "Boston, MA" "Worcester, MA")
        if (args.length >= 2) {
            String start = args[0];
            String end = args[1];
            planRouteWithCharger(start, end);
        } else if (args.length == 1) {
            String location = args[0];
            planRouteWithCharger(location, "Dunkin' High St Westwood");
        }

        // Interactive Terminal Loop
        Scanner scanner = new Scanner(System.in);
        System.out.println("\nInstructions:");
        System.out.println("  1. Enter your Start Location (e.g., 'XBHS Dover, MA' or 'Boston, MA')");
        System.out.println("  2. Enter your End Location (e.g., 'Dunkin Walpole, MA' or 'Worcester, MA')");
        System.out.println("  3. The app finds an optimal EV charger on your route and updates the map!");
        System.out.println("  • Type 'q' or 'exit' at any prompt to quit.\n");

        while (true) {
            System.out.println("------------------------------------------------------------");
            System.out.print("📍 Enter START Location: ");
            if (!scanner.hasNextLine()) break;

            String startInput = scanner.nextLine().trim();
            if (startInput.equalsIgnoreCase("q") || startInput.equalsIgnoreCase("exit") || startInput.equalsIgnoreCase("quit")) {
                System.out.println("\nThanks for using VOLT! Goodbye.");
                break;
            }
            if (startInput.isEmpty()) continue;

            System.out.print("🏁 Enter END Location:   ");
            if (!scanner.hasNextLine()) break;

            String endInput = scanner.nextLine().trim();
            if (endInput.equalsIgnoreCase("q") || endInput.equalsIgnoreCase("exit") || endInput.equalsIgnoreCase("quit")) {
                System.out.println("\nThanks for using VOLT! Goodbye.");
                break;
            }
            if (endInput.isEmpty()) continue;

            planRouteWithCharger(startInput, endInput);
        }

        scanner.close();
        stopServer();
    }
}
