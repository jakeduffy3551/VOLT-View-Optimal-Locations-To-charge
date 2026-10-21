public class TEST {
    public static void main(String[] args) {
        System.out.println("Testing VOLT EV Long-Distance Route & Charger Pricing...\n");

        // Test 1: Query charging cost and details at a specific location using Open Charge Map API
        System.out.println("--- Test 1: Single Location Charger Pricing Lookup ---");
        API_Charge_Map.getChargerPricingAtLocation("Westwood, MA");

        // Test 2: Short Drive (Direct route, 0 stops required)
        System.out.println("\n--- Test 2: Short Drive (Boston to Worcester) ---");
        API_Charge_Map.planRouteWithCharger("Boston, MA", "Worcester, MA", 250.0, 180.0);

        // Test 3: Long Drive with Charger Stops & Pricing
        System.out.println("\n--- Test 3: Long Drive with Pricing (Boston to Richmond, VA) ---");
        API_Charge_Map.planRouteWithCharger("Boston, MA", "Richmond, VA", 250.0, 150.0);
    }
}
