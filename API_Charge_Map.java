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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    private static final String OCM_API_KEY = "59ded5d1-27cf-467e-b088-4424c36d78db";

    // Thread-safe storage for current route data JSON served to index.html UI
    private static volatile String currentRouteJson = buildInitialDefaultRouteJson();

    public static class ChargerInfo {
        public String title = "EV Charging Station";
        public String address = "Location Along Route";
        public String operator = "Unknown Operator";
        public String connType = "Standard EV Plug";
        public String powerKw = "N/A";
        public String usageCost = "N/A";
        public String priceRate = "$0.42/kWh";
        public String estCost = "$12.50";
        public double lat;
        public double lon;
        public double distance = -1;
        public int stopNumber = 1;
        public int chargeDurationMins = 20;
    }

    /**
     * Computes Haversine great-circle distance between two GPS coordinates in miles.
     */
    public static double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final double R = 3958.8; // Earth radius in miles
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /**
     * Overload for backward compatibility defaulting to 250 mi total range, 180 mi current battery.
     */
    public static boolean planRouteWithCharger(String startQuery, String endQuery) {
        return planRouteWithCharger(startQuery, endQuery, 250.0, 180.0);
    }

    /**
     * Geocodes start and end locations, calculates safe EV range, finds optimal high-speed
     * EV charging stations along the route (supporting multi-stop long drives), and updates route JSON.
     */
    public static boolean planRouteWithCharger(String startQuery, String endQuery, double totalRange, double currentRange) {
        if (startQuery == null || startQuery.trim().isEmpty() || endQuery == null || endQuery.trim().isEmpty()) {
            System.out.println(" Error: Both Start and End locations must be provided.");
            return false;
        }

        if (totalRange <= 0) totalRange = 250.0;
        if (currentRange <= 0) currentRange = 180.0;
        if (currentRange > totalRange) currentRange = totalRange;

        System.out.println("\n Resolving Start Location: \"" + startQuery + "\"...");
        double[] startCoords = resolveLocation(startQuery);
        if (startCoords == null) {
            System.out.println(" Could not locate Start Location: \"" + startQuery + "\". Please check spelling.");
            return false;
        }
        System.out.printf("   Start Coordinates: Latitude %.4f, Longitude %.4f%n", startCoords[0], startCoords[1]);

        System.out.println(" Resolving End Location: \"" + endQuery + "\"...");
        double[] endCoords = resolveLocation(endQuery);
        if (endCoords == null) {
            System.out.println(" Could not locate End Location: \"" + endQuery + "\". Please check spelling.");
            return false;
        }
        System.out.printf("   End Coordinates:   Latitude %.4f, Longitude %.4f%n", endCoords[0], endCoords[1]);

        double directDistMiles = haversineDistance(startCoords[0], startCoords[1], endCoords[0], endCoords[1]);
        System.out.printf(" Direct Distance: %.1f miles | Vehicle Max Range: %.0f mi | Current Battery: %.0f mi%n",
                directDistMiles, totalRange, currentRange);

        // Safety reserve buffer: 15-25 miles
        double reserve = Math.min(25.0, Math.max(15.0, totalRange * 0.10));
        List<ChargerInfo> chargers = new ArrayList<>();

        if (directDistMiles <= (currentRange - reserve)) {
            System.out.printf(" Direct Route: Current battery (%.0f mi) is sufficient for %.1f mi drive. No charging stop required.%n",
                    currentRange, directDistMiles);
        } else {
            // Multi-stop algorithm along the route corridor
            double progress = 0.0;
            double availableRange = currentRange;
            int stopNum = 1;

            while ((directDistMiles - progress) > (availableRange - reserve) && stopNum <= 8) {
                double safeLeg = availableRange - reserve;
                // Target stopping at ~80% of safe range to avoid stopping prematurely near start
                double idealDist = progress + Math.min(safeLeg * 0.82, (directDistMiles - progress) - 5);
                double t = Math.max(0.05, Math.min(0.95, idealDist / directDistMiles));

                double targetLat = startCoords[0] + t * (endCoords[0] - startCoords[0]);
                double targetLon = startCoords[1] + t * (endCoords[1] - startCoords[1]);

                System.out.printf(" Searching for optimal Stop #%d charger near %.1f mi (~%.4f, %.4f)...%n",
                        stopNum, idealDist, targetLat, targetLon);
                ChargerInfo charger = findChargerNearPoint(targetLat, targetLon);

                if (charger == null) {
                    charger = new ChargerInfo();
                    charger.title = "Route Waypoint Charger #" + stopNum;
                    charger.address = String.format("%.4f, %.4f", targetLat, targetLon);
                    charger.powerKw = "150";
                    charger.connType = "CCS / NACS";
                    charger.operator = "Public Fast Charger";
                    charger.lat = targetLat;
                    charger.lon = targetLon;
                }

                charger.stopNumber = stopNum;
                double kw = 50.0;
                try { kw = Double.parseDouble(charger.powerKw); } catch (Exception ignored) {}
                charger.chargeDurationMins = (int) Math.round(Math.max(15, ((totalRange * 0.65 * 0.28) / Math.max(25, kw)) * 60) + 5);

                double addedKwh = (totalRange * 0.65) * 0.28;
                if (charger.priceRate.equalsIgnoreCase("free") || charger.priceRate.toLowerCase().contains("free")) {
                    charger.estCost = "Free ($0.00)";
                } else {
                    double rate = 0.42;
                    Matcher pm = Pattern.compile("([0-9]+\\.?[0-9]*)").matcher(charger.priceRate);
                    if (pm.find()) {
                        try {
                            rate = Double.parseDouble(pm.group(1));
                            if (rate > 5) rate /= 100.0;
                        } catch (Exception ignored) {}
                    }
                    charger.estCost = String.format("~$%.2f", addedKwh * rate);
                }

                chargers.add(charger);
                progress = idealDist;
                availableRange = totalRange * 0.82;
                stopNum++;
            }
        }

        // Print Summary to Terminal
        System.out.println("\n============================================================");
        System.out.println("             OPTIMAL EV ROUTE SELECTED                      ");
        System.out.println("============================================================");
        System.out.println(" Start Point:     " + startQuery);
        System.out.println(" End Point:       " + endQuery);
        System.out.printf(" Total Distance:  ~%.1f miles%n", directDistMiles);
        System.out.printf(" Battery State:   %.0f mi left / %.0f mi total range%n", currentRange, totalRange);
        System.out.printf(" Charging Stops:  %d%n", chargers.size());

        for (ChargerInfo c : chargers) {
            System.out.println(" -----------------------------------------------------------");
            System.out.printf(" Stop #%d Charger: %s%n", c.stopNumber, c.title);
            System.out.println("   Address:        " + c.address);
            System.out.println("   Operator:       " + c.operator);
            System.out.println("   Power & Plug:   " + c.powerKw + " kW (" + c.connType + ")");
            System.out.printf("   Price / Rate:   %s (Est. Session: %s)%n", c.priceRate, c.estCost);
            System.out.printf("   Est. Charge:    ~%d min%n", c.chargeDurationMins);
            System.out.printf("   Coordinates:    %.4f, %.4f%n", c.lat, c.lon);
        }
        System.out.println("============================================================");

        // Update JSON served to index.html UI
        currentRouteJson = buildRouteJson(startQuery, startCoords[0], startCoords[1],
                endQuery, endCoords[0], endCoords[1], totalRange, currentRange, chargers);

        System.out.println("\n Map updated! Open or refresh http://localhost:" + activePort + " to view route on map.\n");
        return true;
    }

    /**
     * Resolves location string into [latitude, longitude].
     * Accepts address strings or raw coordinates like "42.2033, -71.2185".
     */
    public static double[] resolveLocation(String location) {
        if (location == null || location.trim().isEmpty())
            return null;
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
     * Finds highest-scoring EV charger near a point using Open Charge Map API,
     * prioritizing DC Fast charging (50-350 kW) to save travel time.
     */
    public static ChargerInfo findChargerNearPoint(double latitude, double longitude) {
        try {
            StringBuilder urlBuilder = new StringBuilder("https://api.openchargemap.org/v3/poi?");
            urlBuilder.append("latitude=").append(latitude);
            urlBuilder.append("&longitude=").append(longitude);
            urlBuilder.append("&maxresults=15");
            urlBuilder.append("&distanceunit=Miles");

            if (OCM_API_KEY != null && !OCM_API_KEY.isEmpty()) {
                urlBuilder.append("&key=").append(URLEncoder.encode(OCM_API_KEY, StandardCharsets.UTF_8));
            }

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(urlBuilder.toString()))
                    .header("User-Agent", "VOLT-App/1.0");

            if (OCM_API_KEY != null && !OCM_API_KEY.isEmpty()) {
                reqBuilder.header("X-API-Key", OCM_API_KEY);
            }

            HttpRequest request = reqBuilder.build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();

            if (response.statusCode() == 200) {
                List<ChargerInfo> candidates = parseAllChargers(responseBody);
                if (!candidates.isEmpty()) {
                    ChargerInfo best = null;
                    double bestScore = -1;
                    for (ChargerInfo c : candidates) {
                        double kw = 0;
                        try { kw = Double.parseDouble(c.powerKw); } catch (Exception ignored) {}
                        // Prioritize DC Fast Charging: 50-350kW saves hours of waiting!
                        double score = (kw >= 150) ? 100 : (kw >= 50 ? 75 : (kw >= 20 ? 40 : 10));
                        if (c.distance > 0) {
                            score -= (c.distance * 2.0); // penalize distance from target
                        }
                        if (score > bestScore) {
                            bestScore = score;
                            best = c;
                        }
                    }
                    if (best != null) return best;
                }
                return parseChargerInfo(responseBody);
            }
        } catch (Exception e) {
            System.err.println("Failed to fetch charger from Open Charge Map: " + e.getMessage());
        }
        return null;
    }

    /**
     * Parses multiple ChargerInfo objects from Open Charge Map top-level JSON array.
     */
    public static List<ChargerInfo> parseAllChargers(String json) {
        List<ChargerInfo> list = new ArrayList<>();
        if (json == null || json.trim().isEmpty() || json.trim().equals("[]")) {
            return list;
        }
        int startBracket = json.indexOf('[');
        int endBracket = json.lastIndexOf(']');
        if (startBracket < 0 || endBracket <= startBracket) return list;

        int depth = 0;
        int objStart = -1;
        for (int i = startBracket + 1; i < endBracket; i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String obj = json.substring(objStart, i + 1);
                    ChargerInfo info = parseChargerInfo(obj);
                    if (info != null) list.add(info);
                    objStart = -1;
                }
            }
        }
        return list;
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
        if (!line1.equals("N/A"))
            addrSb.append(line1);
        if (!town.equals("N/A")) {
            if (addrSb.length() > 0)
                addrSb.append(", ");
            addrSb.append(town);
        }
        if (!state.equals("N/A")) {
            if (addrSb.length() > 0)
                addrSb.append(", ");
            addrSb.append(state);
        }
        if (!postcode.equals("N/A")) {
            if (addrSb.length() > 0)
                addrSb.append(" ");
            addrSb.append(postcode);
        }
        info.address = addrSb.length() > 0 ? addrSb.toString() : "Address N/A";

        String latStr = extract(addressBlock, "\"Latitude\"\\s*:\\s*([0-9.-]+)");
        String lonStr = extract(addressBlock, "\"Longitude\"\\s*:\\s*([0-9.-]+)");
        String distStr = extract(addressBlock, "\"Distance\"\\s*:\\s*([0-9.]+)");

        if (!latStr.equals("N/A"))
            info.lat = Double.parseDouble(latStr);
        if (!lonStr.equals("N/A"))
            info.lon = Double.parseDouble(lonStr);
        if (!distStr.equals("N/A"))
            info.distance = Double.parseDouble(distStr);

        info.operator = extract(operatorBlock, "\"Title\"\\s*:\\s*\"([^\"]+)\"");
        info.connType = extract(connectionBlock, "\"Title\"\\s*:\\s*\"([^\"]+)\"");
        info.powerKw = extract(connectionBlock, "\"PowerKW\"\\s*:\\s*([0-9.]+)");

        if (info.title.equals("N/A"))
            info.title = "EV Charging Station";
        if (info.operator.equals("N/A"))
            info.operator = "Public Network";
        if (info.connType.equals("N/A"))
            info.connType = "Standard EV Plug";

        // Extract pricing information from Open Charge Map
        String usageCost = extract(json, "\"UsageCost\"\\s*:\\s*\"([^\"]+)\"");
        String usageBlock = extractNestedObject(json, "UsageType");
        String usageTitle = extract(usageBlock, "\"Title\"\\s*:\\s*\"([^\"]+)\"");
        String isPayAtLoc = extract(usageBlock, "\"IsPayAtLocation\"\\s*:\\s*([a-zA-Z]+)");

        if (!usageCost.equals("N/A") && !usageCost.trim().isEmpty() && !usageCost.equalsIgnoreCase("null") && !usageCost.equalsIgnoreCase("unknown")) {
            info.usageCost = usageCost.trim();
            info.priceRate = usageCost.trim();
            if (info.priceRate.toLowerCase().contains("free")) {
                info.priceRate = "Free";
                info.estCost = "Free ($0.00)";
            }
        } else if (usageTitle.toLowerCase().contains("free") || isPayAtLoc.equalsIgnoreCase("false")) {
            info.usageCost = "Free";
            info.priceRate = "Free";
            info.estCost = "Free ($0.00)";
        } else {
            // Operator network standard rates
            String op = info.operator.toLowerCase();
            if (op.contains("tesla")) info.priceRate = "$0.38/kWh";
            else if (op.contains("electrify")) info.priceRate = "$0.48/kWh";
            else if (op.contains("evgo")) info.priceRate = "$0.45/kWh";
            else if (op.contains("chargepoint")) info.priceRate = "$0.35/kWh";
            else if (op.contains("blink")) info.priceRate = "$0.45/kWh";
            else if (op.contains("flo")) info.priceRate = "$0.35/kWh";
            else if (op.contains("rivian")) info.priceRate = "$0.36/kWh";
            else if (op.contains("shell")) info.priceRate = "$0.42/kWh";
            else {
                double kw = 50.0;
                try { kw = Double.parseDouble(info.powerKw); } catch (Exception ignored) {}
                info.priceRate = (kw >= 50) ? "$0.42/kWh" : "$0.25/kWh";
            }
            info.usageCost = "Network Standard (~" + info.priceRate + ")";
        }

        return info;
    }

    /**
     * Smart geocoder: resolves addresses, places, landmarks, ZIP codes using
     * Nominatim.
     */
    public static double[] geocodeLocation(String address) {
        if (address == null)
            return null;
        address = address.trim();

        // Attempt 1: Exact query as entered
        double[] coords = queryNominatim(address);
        if (coords != null)
            return coords;

        // Attempt 2: If 5-digit US ZIP code, append USA
        if (address.matches("^\\d{5}(-\\d{4})?$")) {
            coords = queryNominatim(address + ", USA");
            if (coords != null)
                return coords;
        }

        // Attempt 3: If comma-separated, try reversed order
        if (address.contains(",")) {
            String[] parts = address.split(",");
            StringBuilder reversed = new StringBuilder();
            for (int i = parts.length - 1; i >= 0; i--) {
                reversed.append(parts[i].trim());
                if (i > 0)
                    reversed.append(", ");
            }
            coords = queryNominatim(reversed.toString());
            if (coords != null)
                return coords;
        }

        // Attempt 4: Try removing street number
        String withoutNumber = address.replaceAll("^\\d+\\s+", "").replaceAll(",\\s*\\d+\\s+", ", ");
        if (!withoutNumber.equalsIgnoreCase(address)) {
            coords = queryNominatim(withoutNumber);
            if (coords != null)
                return coords;
        }

        // Attempt 5: Fallback to city/town part
        if (address.contains(",")) {
            String[] parts = address.split(",");
            for (String part : parts) {
                String cleanPart = part.trim();
                if (!cleanPart.matches(".*\\d+.*") && cleanPart.length() >= 3) {
                    coords = queryNominatim(cleanPart);
                    if (coords != null)
                        return coords;
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
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String extractNestedObject(String text, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\{");
        Matcher m = pattern.matcher(text);
        if (!m.find())
            return "";
        int openBrace = m.end() - 1;
        int depth = 0;
        for (int i = openBrace; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{')
                depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0)
                    return text.substring(openBrace + 1, i);
            }
        }
        return "";
    }

    private static String extractNestedArray(String text, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[");
        Matcher m = pattern.matcher(text);
        if (!m.find())
            return "";
        int openBracket = m.end() - 1;
        int depth = 0;
        for (int i = openBracket; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[')
                depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0)
                    return text.substring(openBracket + 1, i);
            }
        }
        return "";
    }

    private static String extract(String json, String regex) {
        if (json == null || json.isEmpty())
            return "N/A";
        Matcher m = Pattern.compile(regex).matcher(json);
        if (!m.find()) return "N/A";
        String val = m.group(1).replace("\\u0022", "\"").replace("\\/", "/");
        val = val.replace("\\u003E", ">").replace("\\u003e", ">").replace("\\u003C", "<").replace("\\u003c", "<");
        return val;
    }

    private static String buildRouteJson(String startName, double startLat, double startLon,
            String endName, double endLat, double endLon,
            double totalRange, double currentRange,
            List<ChargerInfo> chargers) {
        long updatedAt = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("{")
                .append("\"status\":\"success\",")
                .append("\"updatedAt\":").append(updatedAt).append(",")
                .append("\"totalRange\":").append(totalRange).append(",")
                .append("\"currentRange\":").append(currentRange).append(",")
                .append("\"stopsCount\":").append(chargers != null ? chargers.size() : 0).append(",")
                .append("\"start\":{")
                .append("\"name\":\"").append(escapeJson(startName)).append("\",")
                .append("\"lat\":").append(startLat).append(",")
                .append("\"lon\":").append(startLon)
                .append("},")
                .append("\"end\":{")
                .append("\"name\":\"").append(escapeJson(endName)).append("\",")
                .append("\"lat\":").append(endLat).append(",")
                .append("\"lon\":").append(endLon)
                .append("},");

        // Primary charger for backward compatibility
        ChargerInfo primary = (chargers != null && !chargers.isEmpty()) ? chargers.get(0) : new ChargerInfo();
        sb.append("\"charger\":{")
                .append("\"title\":\"").append(escapeJson(primary.title)).append("\",")
                .append("\"address\":\"").append(escapeJson(primary.address)).append("\",")
                .append("\"operator\":\"").append(escapeJson(primary.operator)).append("\",")
                .append("\"connType\":\"").append(escapeJson(primary.connType)).append("\",")
                .append("\"powerKw\":\"").append(escapeJson(primary.powerKw)).append("\",")
                .append("\"priceRate\":\"").append(escapeJson(primary.priceRate)).append("\",")
                .append("\"estCost\":\"").append(escapeJson(primary.estCost)).append("\",")
                .append("\"lat\":").append(primary.lat).append(",")
                .append("\"lon\":").append(primary.lon)
                .append("},");

        // Array of all chargers
        sb.append("\"chargers\":[");
        if (chargers != null) {
            for (int i = 0; i < chargers.size(); i++) {
                ChargerInfo c = chargers.get(i);
                if (i > 0) sb.append(",");
                sb.append("{")
                        .append("\"stopNumber\":").append(c.stopNumber).append(",")
                        .append("\"title\":\"").append(escapeJson(c.title)).append("\",")
                        .append("\"address\":\"").append(escapeJson(c.address)).append("\",")
                        .append("\"operator\":\"").append(escapeJson(c.operator)).append("\",")
                        .append("\"connType\":\"").append(escapeJson(c.connType)).append("\",")
                        .append("\"powerKw\":\"").append(escapeJson(c.powerKw)).append("\",")
                        .append("\"priceRate\":\"").append(escapeJson(c.priceRate)).append("\",")
                        .append("\"estCost\":\"").append(escapeJson(c.estCost)).append("\",")
                        .append("\"chargeDurationMins\":").append(c.chargeDurationMins).append(",")
                        .append("\"lat\":").append(c.lat).append(",")
                        .append("\"lon\":").append(c.lon)
                        .append("}");
            }
        }
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }

    private static String buildRouteJson(String startName, double startLat, double startLon,
            String endName, double endLat, double endLon,
            ChargerInfo charger) {
        List<ChargerInfo> list = new ArrayList<>();
        if (charger != null) list.add(charger);
        return buildRouteJson(startName, startLat, startLon, endName, endLat, endLon, 250.0, 180.0, list);
    }

    private static String buildInitialDefaultRouteJson() {
        ChargerInfo defaultCharger = new ChargerInfo();
        defaultCharger.title = "Dover EV Charging Station";
        defaultCharger.address = "Clapboardtree St, Dover, MA";
        defaultCharger.operator = "ChargePoint";
        defaultCharger.connType = "J1772";
        defaultCharger.powerKw = "7.2";
        defaultCharger.priceRate = "$0.35/kWh";
        defaultCharger.estCost = "~$8.50";
        defaultCharger.lat = 42.2085;
        defaultCharger.lon = -71.2257;

        return buildRouteJson("Xaverian Brothers High School", 42.2033, -71.2185,
                "Dunkin' High St Westwood", 42.2132, -71.2330,
                defaultCharger);
    }

    /**
     * Resolves a location name/address, queries Open Charge Map API with the API key,
     * extracts charger pricing and charging cost details, and prints a comprehensive report.
     */
    public static ChargerInfo getChargerPricingAtLocation(String location) {
        if (location == null || location.trim().isEmpty()) {
            System.out.println("Error: Location cannot be empty.");
            return null;
        }
        System.out.println("\n============================================================");
        System.out.println("   EV CHARGER & PRICING LOOKUP: " + location);
        System.out.println("============================================================");
        double[] coords = resolveLocation(location);
        if (coords == null) {
            System.out.println("Could not resolve location coordinates for: " + location);
            return null;
        }
        System.out.printf(" Coordinates:    Latitude %.4f, Longitude %.4f%n", coords[0], coords[1]);
        ChargerInfo charger = findChargerNearPoint(coords[0], coords[1]);
        if (charger == null) {
            System.out.println("No charger found near this location.");
            return null;
        }

        // Calculate estimated session cost based on typical 45 kWh recharge
        double defaultChargeKwh = 45.0;
        if (charger.priceRate.equalsIgnoreCase("free") || charger.priceRate.toLowerCase().contains("free")) {
            charger.estCost = "Free ($0.00)";
        } else {
            double rate = 0.42;
            Matcher pm = Pattern.compile("([0-9]+\\.?[0-9]*)").matcher(charger.priceRate);
            if (pm.find()) {
                try {
                    rate = Double.parseDouble(pm.group(1));
                    if (rate > 5) rate /= 100.0;
                } catch (Exception ignored) {}
            }
            charger.estCost = String.format("~$%.2f", defaultChargeKwh * rate);
        }

        System.out.println(" Station Name:   " + charger.title);
        System.out.println(" Address:        " + charger.address);
        System.out.println(" Operator:       " + charger.operator);
        System.out.println(" Power & Plug:   " + charger.powerKw + " kW (" + charger.connType + ")");
        System.out.println(" OCM API Cost:   " + charger.usageCost);
        System.out.println(" Effective Rate: " + charger.priceRate);
        System.out.printf(" Est. Cost (recharge ~45 kWh): %s%n", charger.estCost);
        System.out.println("============================================================\n");
        return charger;
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
        System.err
                .println("Error: Could not bind HTTP server to any port in range " + preferredPort + "-" + (port - 1));
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
                ? "index.html"
                : requestedPath;
        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        // 1. Try relative to current working directory
        File file = new File(path);
        if (file.exists() && !file.isDirectory()) {
            try {
                return Files.readAllBytes(file.toPath());
            } catch (IOException ignored) {
            }
        }

        File indexFile = new File("index.html");
        if (indexFile.exists() && !indexFile.isDirectory()) {
            try {
                return Files.readAllBytes(indexFile.toPath());
            } catch (IOException ignored) {
            }
        }

        // 2. Try relative to compiled class file location / JAR directory
        try {
            URI codeSourceUri = API_Charge_Map.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            File codeSourceFile = new File(codeSourceUri);
            File baseDir = codeSourceFile.isDirectory() ? codeSourceFile : codeSourceFile.getParentFile();

            File fileNearClass = new File(baseDir, path);
            if (fileNearClass.exists() && !fileNearClass.isDirectory()) {
                return Files.readAllBytes(fileNearClass.toPath());
            }

            File indexNearClass = new File(baseDir, "index.html");
            if (indexNearClass.exists() && !indexNearClass.isDirectory()) {
                return Files.readAllBytes(indexNearClass.toPath());
            }
        } catch (Exception ignored) {
        }

        // 3. Try ClassLoader resources
        try (var is = API_Charge_Map.class.getResourceAsStream("/" + path)) {
            if (is != null)
                return is.readAllBytes();
        } catch (Exception ignored) {
        }

        try (var is = API_Charge_Map.class.getResourceAsStream("/index.html")) {
            if (is != null)
                return is.readAllBytes();
        } catch (Exception ignored) {
        }

        // 4. Fallback HTML content so map ALWAYS works regardless of execution working
        // directory
        if (path.endsWith(".html") || path.endsWith(".htm") || path.equalsIgnoreCase("index.html")) {
            return getEmbeddedIndexHtml().getBytes(StandardCharsets.UTF_8);
        }

        return null;
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
                "      position: absolute; top: 10px; left: 10px; max-width: 330px; max-height: 85vh;\n" +
                "      overflow-y: auto; background: rgba(255, 255, 255, 0.95); padding: 15px;\n" +
                "      border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.25); z-index: 10;\n" +
                "    }\n" +
                "    h3 { margin-top: 0; margin-bottom: 8px; font-size: 16px; color: #111; }\n" +
                "    p { margin: 4px 0; font-size: 14px; }\n" +
                "    ol { padding-left: 20px; margin: 8px 0 0 0; font-size: 13px; }\n" +
                "    li { margin-bottom: 6px; }\n" +
                "    .badge { display: inline-block; padding: 2px 6px; border-radius: 4px; font-size: 11px; font-weight: bold; color: white; }\n"
                +
                "    .badge-start { background-color: #00704A; }\n" +
                "    .badge-charger { background-color: #0066FF; }\n" +
                "    .badge-end { background-color: #FF6600; }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div id=\"map\"></div>\n" +
                "<div id=\"instructions\">\n" +
                "  <h3>VOLT Route & Charger</h3>\n" +
                "  <div id=\"route-details\">Loading route from terminal input...</div>\n" +
                "</div>\n" +
                "<script src=\"https://api.mapbox.com/mapbox-gl-js/v3.0.1/mapbox-gl.js\"></script>\n" +
                "<script>\n" +
                "  const accessToken = 'pk.eyJ1IjoibWFkZG94bCIsImEiOiJjbXU3OG1vbWgwanBwMnpwdDVwaGtndTRqIn0.xreTdZS3Gu9ThwR2brfAFw';\n"
                +
                "  mapboxgl.accessToken = accessToken;\n" +
                "  let startMarker = null, endMarker = null, chargerMarker = null, lastUpdatedAt = 0;\n" +
                "  const map = new mapboxgl.Map({ container: 'map', style: 'mapbox://styles/mapbox/streets-v12', center: [-71.225, 42.208], zoom: 12 });\n"
                +
                "  function escapeHtml(str) { return str ? String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\"/g, '&quot;') : ''; }\n"
                +
                "  async function getRoute() {\n" +
                "    try {\n" +
                "      const response = await fetch('/api/route');\n" +
                "      if (!response.ok) return;\n" +
                "      const data = await response.json();\n" +
                "      if (data.status !== 'success' || (data.updatedAt && data.updatedAt === lastUpdatedAt)) return;\n"
                +
                "      lastUpdatedAt = data.updatedAt;\n" +
                "      const origin = [data.start.lon, data.start.lat];\n" +
                "      const destination = [data.end.lon, data.end.lat];\n" +
                "      const charger = [data.charger.lon, data.charger.lat];\n" +
                "      if (startMarker) startMarker.remove();\n" +
                "      if (endMarker) endMarker.remove();\n" +
                "      if (chargerMarker) chargerMarker.remove();\n" +
                "      startMarker = new mapboxgl.Marker({ color: '#00704A' }).setLngLat(origin).setPopup(new mapboxgl.Popup().setHTML(`<b>Start:</b> ${escapeHtml(data.start.name)}`)).addTo(map);\n"
                +
                "      const chargerPopupHtml = `<div style=\"font-family: sans-serif; max-width:220px;\"><h4 style=\"margin:0 0 4px 0; color:#0066FF;\">${escapeHtml(data.charger.title)}</h4><p style=\"margin:2px 0; font-size:12px;\"><b>Address:</b> ${escapeHtml(data.charger.address)}</p><p style=\"margin:2px 0; font-size:12px;\"><b>Operator:</b> ${escapeHtml(data.charger.operator)}</p><p style=\"margin:2px 0; font-size:12px;\"><b>Plug:</b> ${escapeHtml(data.charger.connType)} (${escapeHtml(data.charger.powerKw)} kW)</p></div>`;\n"
                +
                "      chargerMarker = new mapboxgl.Marker({ color: '#0066FF' }).setLngLat(charger).setPopup(new mapboxgl.Popup().setHTML(chargerPopupHtml)).addTo(map);\n"
                +
                "      endMarker = new mapboxgl.Marker({ color: '#FF6600' }).setLngLat(destination).setPopup(new mapboxgl.Popup().setHTML(`<b>Destination:</b> ${escapeHtml(data.end.name)}`)).addTo(map);\n"
                +
                "      const bounds = new mapboxgl.LngLatBounds(); bounds.extend(origin); bounds.extend(charger); bounds.extend(destination);\n"
                +
                "      map.fitBounds(bounds, { padding: 70, maxZoom: 15 });\n" +
                "      const directionsUrl = `https://api.mapbox.com/directions/v5/mapbox/driving/${origin[0]},${origin[1]};${charger[0]},${charger[1]};${destination[0]},${destination[1]}?steps=true&geometries=geojson&access_token=${accessToken}`;\n"
                +
                "      const dirResponse = await fetch(directionsUrl); const dirData = await dirResponse.json();\n" +
                "      if (dirData.routes && dirData.routes.length > 0) {\n" +
                "        const route = dirData.routes[0];\n" +
                "        if (map.getSource('route')) { map.getSource('route').setData({ type: 'Feature', geometry: route.geometry }); }\n"
                +
                "        else { map.addSource('route', { type: 'geojson', data: { type: 'Feature', geometry: route.geometry } }); map.addLayer({ id: 'route', type: 'line', source: 'route', layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': '#0066FF', 'line-width': 5, 'line-opacity': 0.8 } }); }\n"
                +
                "        const durationMins = Math.round(route.duration / 60); const distanceMiles = (route.distance / 1609.34).toFixed(2);\n"
                +
                "        let stepsHtml = `<p><span class=\"badge badge-start\">START</span> <strong>${escapeHtml(data.start.name)}</strong></p><p><span class=\"badge badge-charger\">CHARGER STOP</span> <strong>${escapeHtml(data.charger.title)}</strong><br/><small style=\"color:#555;\">${escapeHtml(data.charger.address)}<br/>Operator: ${escapeHtml(data.charger.operator)} | Plug: ${escapeHtml(data.charger.connType)} (${escapeHtml(data.charger.powerKw)} kW)</small></p><p><span class=\"badge badge-end\">END</span> <strong>${escapeHtml(data.end.name)}</strong></p><hr style=\"margin:10px 0; border:0; border-top:1px solid #eee;\"/><p><strong>Total Distance:</strong> ${distanceMiles} miles</p><p><strong>Est. Drive Time:</strong> ~${durationMins} mins</p><hr style=\"margin:10px 0; border:0; border-top:1px solid #eee;\"/><strong>Driving Directions:</strong><ol style=\"padding-left:18px; margin:6px 0;\">`;\n"
                +
                "        route.legs.forEach((leg, legIdx) => {\n" +
                "          const legLabel = legIdx === 0 ? \"Leg 1: Start to Charger Stop\" : \"Leg 2: Charger Stop to Destination\";\n"
                +
                "          stepsHtml += `<li style=\"font-weight:bold; list-style:none; margin-left:-18px; margin-top:8px; color:#333;\">${legLabel}</li>`;\n"
                +
                "          leg.steps.forEach(step => { stepsHtml += `<li>${step.maneuver.instruction}</li>`; });\n" +
                "        });\n" +
                "        stepsHtml += `</ol>`; document.getElementById('route-details').innerHTML = stepsHtml;\n" +
                "      }\n" +
                "    } catch (error) { console.error('Error loading route:', error); }\n" +
                "  }\n" +
                "  map.on('load', () => { getRoute(); setInterval(getRoute, 2500); });\n" +
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

            // Check if query params were passed directly to GET
            // /api/route?start=...&end=...&totalRange=...&currentRange=...
            Map<String, String> params = parseQueryParams(exchange.getRequestURI());
            String startParam = params.get("start");
            String endParam = params.get("end");
            double totalRange = 250.0;
            double currentRange = 180.0;

            if (params.containsKey("totalRange")) {
                try { totalRange = Double.parseDouble(params.get("totalRange")); } catch (Exception ignored) {}
            }
            if (params.containsKey("currentRange")) {
                try { currentRange = Double.parseDouble(params.get("currentRange")); } catch (Exception ignored) {}
            }

            if (startParam != null && !startParam.trim().isEmpty() && endParam != null && !endParam.trim().isEmpty()) {
                planRouteWithCharger(startParam, endParam, totalRange, currentRange);
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
        if (rawQuery == null || rawQuery.isEmpty())
            return params;

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
        if (filename.endsWith(".html") || filename.endsWith(".htm"))
            return "text/html; charset=UTF-8";
        if (filename.endsWith(".css"))
            return "text/css; charset=UTF-8";
        if (filename.endsWith(".js"))
            return "application/javascript; charset=UTF-8";
        if (filename.endsWith(".json"))
            return "application/json; charset=UTF-8";
        if (filename.endsWith(".png"))
            return "image/png";
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg"))
            return "image/jpeg";
        if (filename.endsWith(".svg"))
            return "image/svg+xml";
        return "application/octet-stream";
    }

    private static String escapeJson(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // =========================================================================
    // MAIN ENTRYPOINT (Web Server)
    // =========================================================================

    public static void main(String[] args) {
        // Start background Web Server
        HttpServer server = startServer(8080);
        int port = (server != null) ? activePort : 8080;

        System.out.println("============================================================");
        System.out.println("      VOLT - View Optimal Locations To charge          ");
        System.out.println("============================================================");
        if (server != null) {
            System.out.println("Web App is live at: http://localhost:" + port);
            System.out.println("   Open index.html or visit http://localhost:" + port + " in your browser.");
            System.out.println("   All route planning and charger search are now inside the HTML interface!");
            System.out.println("   (Press Ctrl+C in this terminal to stop the server)");
            System.out.println("============================================================");
        }

        // If arguments were provided (e.g. java API_Charge_Map "Boston, MA" "Worcester, MA" 300 200)
        if (args.length >= 2) {
            String start = args[0];
            String end = args[1];
            double totalRange = 250.0;
            double currentRange = 180.0;
            if (args.length >= 3) {
                try { totalRange = Double.parseDouble(args[2]); } catch (Exception ignored) {}
            }
            if (args.length >= 4) {
                try { currentRange = Double.parseDouble(args[3]); } catch (Exception ignored) {}
            }
            planRouteWithCharger(start, end, totalRange, currentRange);
        } else if (args.length == 1) {
            String location = args[0];
            planRouteWithCharger(location, "Dunkin' High St Westwood");
        }

        // Keep server running without demanding terminal inputs
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
        } finally {
            stopServer();
        }
    }
}
