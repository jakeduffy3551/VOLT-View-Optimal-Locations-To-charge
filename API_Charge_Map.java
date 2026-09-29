import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class API_Charge_Map {

    private static final HttpClient client = HttpClient.newHttpClient();

    // Optional: If you have an Open Charge Map API key, paste it here.
    // Basic requests work without one, but having an API key is recommended for
    // heavy use.
    private static final String OCM_API_KEY = "";

    /**
     * Finds the closest EV charger to a given location string.
     * Accepts:
     * - Full addresses: "67 County St Dover, MA"
     * - Landmarks: "Museum of Illusions Boston"
     * - Cities/Towns: "Boston, MA" or "Norfolk"
     * - ZIP Codes: "02030" or "02056"
     * - Coordinates: "40.7128, -74.0060"
     *
     * @param location Address, city, landmark, ZIP, or coordinates
     * @return Raw JSON response from Open Charge Map
     */
    public static String findClosestCharger(String location) {
        if (location == null || location.trim().isEmpty()) {
            System.out.println("Error: Location cannot be empty.");
            return null;
        }

        location = location.trim();

        // 1. Check if the input is directly formatted as coordinates (e.g. "40.7128,
        // -74.0060")
        Pattern coordPattern = Pattern.compile("^\\s*([-+]?\\d+(?:\\.\\d+)?)[,\\s]+([-+]?\\d+(?:\\.\\d+)?)\\s*$");
        Matcher coordMatcher = coordPattern.matcher(location);

        if (coordMatcher.matches()) {
            double lat = Double.parseDouble(coordMatcher.group(1));
            double lon = Double.parseDouble(coordMatcher.group(2));
            System.out.printf("Searching using coordinates: (%.4f, %.4f)%n", lat, lon);
            return findClosestCharger(lat, lon);
        }

        // 2. Geocode address/city/landmark/ZIP into coordinates using smart resolver
        System.out.println("Looking up coordinates for: \"" + location + "\"...");
        double[] coords = geocodeLocation(location);

        if (coords == null) {
            System.out.println("\n Could not locate: \"" + location + "\"");
            System.out.println("💡 Tips for entering addresses:");
            System.out.println("   • Standard format: Street, City, State  (e.g., '67 County St, Dover, MA')");
            System.out.println("   • City & State:   'Boston, MA' or 'Norfolk, MA'");
            System.out.println("   • Popular Places: 'Museum of Illusions Boston' or 'Fenway Park'");
            System.out.println("   • ZIP code:       '02030' or '02056'");
            System.out.println("   • Type 'step' to enter address line-by-line.\n");
            return null;
        }

        System.out.printf("Found coordinates: Latitude %.4f, Longitude %.4f%n", coords[0], coords[1]);
        return findClosestCharger(coords[0], coords[1]);
    }

    /**
     * Finds the closest EV charger to the given latitude and longitude.
     */
    public static String findClosestCharger(double latitude, double longitude) {
        try {
            StringBuilder urlBuilder = new StringBuilder("https://api.openchargemap.org/v3/poi?");
            urlBuilder.append("latitude=").append(latitude);
            urlBuilder.append("&longitude=").append(longitude);
            urlBuilder.append("&maxresults=1");
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

            if (response.statusCode() != 200) {
                System.out.println("API error (HTTP " + response.statusCode() + "): " + responseBody);
                return responseBody;
            }

            displayChargerDetails(responseBody);
            return responseBody;
        } catch (Exception e) {
            System.err.println("Failed to fetch charger: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Smart geocoder: resolves addresses, places, landmarks, ZIP codes,
     * and handles inverted ordering or unindexed street numbers gracefully.
     */
    public static double[] geocodeLocation(String address) {
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

        // Attempt 3: If comma-separated, try reversed order (e.g. "Norfolk, 4 Kenny
        // Pond Rd" -> "4 Kenny Pond Rd, Norfolk")
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

        // Attempt 4: Try removing street number if present (e.g. "4 Kenny Pond Rd" ->
        // "Kenny Pond Rd")
        String withoutNumber = address.replaceAll("^\\d+\\s+", "").replaceAll(",\\s*\\d+\\s+", ", ");
        if (!withoutNumber.equalsIgnoreCase(address)) {
            coords = queryNominatim(withoutNumber);
            if (coords != null)
                return coords;
        }

        // Attempt 5: Fallback to the city/town/area component if a specific street
        // wasn't found
        if (address.contains(",")) {
            String[] parts = address.split(",");
            for (String part : parts) {
                String cleanPart = part.trim();
                // Pick the portion without numbers that looks like a town or area
                if (!cleanPart.matches(".*\\d+.*") && cleanPart.length() >= 3) {
                    coords = queryNominatim(cleanPart);
                    if (coords != null) {
                        System.out.println(
                                "Note: Exact street not found, showing chargers near area: \"" + cleanPart + "\"");
                        return coords;
                    }
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
        } catch (Exception e) {
            // Silently fall through to next attempt
        }
        return null;
    }

    /**
     * Parses key fields from the Open Charge Map JSON response and prints a clean
     * summary.
     */
    public static void displayChargerDetails(String json) {
        if (json == null || json.trim().equals("[]") || !json.contains("\"AddressInfo\"")) {
            System.out.println("\nNo charging stations found near this location.");
            return;
        }

        String addressBlock = extractNestedObject(json, "AddressInfo");
        String operatorBlock = extractNestedObject(json, "OperatorInfo");
        String connectionBlock = extractNestedArray(json, "Connections");

        String title = extract(addressBlock, "\"Title\"\\s*:\\s*\"([^\"]+)\"");
        String address = extract(addressBlock, "\"AddressLine1\"\\s*:\\s*\"([^\"]+)\"");
        String town = extract(addressBlock, "\"Town\"\\s*:\\s*\"([^\"]+)\"");
        String state = extract(addressBlock, "\"StateOrProvince\"\\s*:\\s*\"([^\"]+)\"");
        String postcode = extract(addressBlock, "\"Postcode\"\\s*:\\s*\"([^\"]+)\"");
        String distanceStr = extract(addressBlock, "\"Distance\"\\s*:\\s*([0-9.]+)");
        String latStr = extract(addressBlock, "\"Latitude\"\\s*:\\s*([0-9.-]+)");
        String lonStr = extract(addressBlock, "\"Longitude\"\\s*:\\s*([0-9.-]+)");

        String operator = extract(operatorBlock, "\"Title\"\\s*:\\s*\"([^\"]+)\"");
        String connType = extract(connectionBlock, "\"Title\"\\s*:\\s*\"([^\"]+)\"");
        String powerKw = extract(connectionBlock, "\"PowerKW\"\\s*:\\s*([0-9.]+)");
        String quantity = extract(connectionBlock, "\"Quantity\"\\s*:\\s*([0-9]+)");

        double distance = distanceStr.equals("N/A") ? -1 : Double.parseDouble(distanceStr);

        System.out.println("\n========================================");
        System.out.println("         ⚡ CLOSEST EV CHARGER ⚡         ");
        System.out.println("========================================");
        System.out.println("Station Name: " + title);
        if (distance >= 0) {
            System.out.printf("Distance:     %.2f miles away%n", distance);
        }
        System.out.println("Address:      " + (address.equals("N/A") ? "" : address + ", ")
                + (town.equals("N/A") ? "" : town + ", ")
                + (state.equals("N/A") ? "" : state + " ")
                + (postcode.equals("N/A") ? "" : postcode));
        System.out.println("Operator:     " + operator);
        System.out
                .println("Plug Type:    " + connType + (quantity.equals("N/A") ? "" : " (Quantity: " + quantity + ")"));
        System.out.println("Power:        " + (powerKw.equals("N/A") ? "N/A" : powerKw + " kW"));
        System.out.println("Coordinates:  " + latStr + ", " + lonStr);
        System.out.println("Maps Link:    https://www.google.com/maps/search/?api=1&query=" + latStr + "," + lonStr);
        System.out.println("========================================\n");
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
        return m.find() ? m.group(1).replace("\\u0022", "\"").replace("\\/", "/") : "N/A";
    }

    /**
     * Guided mode for typing in address components step-by-step.
     */
    private static void promptStepByStep(Scanner scanner) {
        System.out.println("\n--- Step-by-Step Address Input ---");
        System.out.print("Street Address (optional, hit Enter to skip): ");
        String street = scanner.nextLine().trim();

        System.out.print("City / Town: ");
        String city = scanner.nextLine().trim();

        System.out.print("State (e.g. MA): ");
        String state = scanner.nextLine().trim();

        System.out.print("ZIP Code (optional, hit Enter to skip): ");
        String zip = scanner.nextLine().trim();

        StringBuilder fullAddress = new StringBuilder();
        if (!street.isEmpty())
            fullAddress.append(street).append(", ");
        if (!city.isEmpty())
            fullAddress.append(city).append(", ");
        if (!state.isEmpty())
            fullAddress.append(state).append(" ");
        if (!zip.isEmpty())
            fullAddress.append(zip);

        String query = fullAddress.toString().trim();
        if (query.endsWith(","))
            query = query.substring(0, query.length() - 1);

        System.out.println("\nSearching for: " + query);
        findClosestCharger(query);
    }

    public static void main(String[] args) {
        // If an argument was provided on the command line (e.g. java API_Charge_Map
        // "Boston, MA")
        if (args.length > 0) {
            String location = String.join(" ", args);
            findClosestCharger(location);
            return;
        }

        // Interactive Continuous Loop: stays alive so the user can search multiple
        // addresses
        Scanner scanner = new Scanner(System.in);
        System.out.println("============================================================");
        System.out.println("         ⚡ VOLT - EV Charging Station Finder ⚡           ");
        System.out.println("============================================================");
        System.out.println("Search by: ");
        System.out.println("  • Full Address: '67 County St Dover, MA'");
        System.out.println("  • Landmark:     'Museum of Illusions Boston'");
        System.out.println("  • City & State: 'Boston, MA'");
        System.out.println("  • ZIP Code:     '02030'");
        System.out.println("  • Coordinates:  '42.2037, -71.2637'");
        System.out.println("  • Guided Input: Type 'step' to enter street, city, state");
        System.out.println("  • Quit:         Type 'q' or 'exit'");
        System.out.println("============================================================\n");

        while (true) {
            System.out.print("Enter location (or 'step' for guided / 'q' to quit): ");
            if (!scanner.hasNextLine())
                break;

            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("q") || input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                System.out.println("\nThanks for using VOLT! Goodbye.");
                break;
            }

            if (input.isEmpty()) {
                continue;
            }

            if (input.equalsIgnoreCase("step")) {
                promptStepByStep(scanner);
            } else {
                findClosestCharger(input);
            }
            System.out.println();
        }

        scanner.close();
    }
}
