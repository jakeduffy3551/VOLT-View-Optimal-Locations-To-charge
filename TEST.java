public class TEST {
    public static void main(String[] args) {
        System.out.println("Testing VOLT Charger Finder...\n");

        // Example 1: Search by City / Address
        API_Charge_Map.findClosestCharger("Boston, MA");

        // Example 2: Search by Coordinates
        // API_Charge_Map.findClosestCharger(40.7128, -74.0060);
    }
}
