package leafcraft.rtp.tools;

import org.bstats.charts.CustomChart;
import org.bstats.json.JsonObjectBuilder;

public class Charts {
    private static CustomChart testChart = new CustomChart("TEST") {
        @Override
        protected JsonObjectBuilder.JsonObject getChartData() throws Exception {
            return null;
        }
    };
}
