public class TEST {
    public static void main(String[] args) {
        System.out.println("Testing VOLT EV Long-Distance Route & Charger Planner...\n");

        // Example 1: Short Trip (Direct route, 0 stops required)
        System.out.println("--- Test 1: Short Drive (Boston to Worcester) ---");
        API_Charge_Map.planRouteWithCharger("Boston, MA", "Worcester, MA", 250.0, 180.0);

        // Example 2: Long Drive (Multi-stop route, multiple chargers required)
        System.out.println("\n--- Test 2: Long Drive (Boston to Richmond, VA) ---");
        API_Charge_Map.planRouteWithCharger("Boston, MA", "Richmond, VA", 250.0, 150.0);
    }
}
