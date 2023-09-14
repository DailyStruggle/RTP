package draw;
import commonTestImpl.TestRTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square_Normal;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.NormalDistributionParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.ScatterChart;
import javafx.scene.chart.XYChart;
import javafx.stage.Stage;

public class drawSquareNormal extends Application {
    @Override public void start( Stage stage ) {
        RTP.serverAccessor = new TestRTPServerAccessor();
        //initialize to create config files
        RTP rtp = new RTP();

        int i = 0;
        while ( rtp.startupTasks.size()>0 ) {
            rtp.startupTasks.execute( Long.MAX_VALUE );
            i++;
            if( i>50 ) return;
        }

        stage.setTitle( "Square Normal Distribution Scatter Chart Sample" );
        final NumberAxis xAxis = new NumberAxis( -256, 256, 32 );
        final NumberAxis yAxis = new NumberAxis( -256, 256, 32 );
        final ScatterChart<Number,Number> sc = new
                ScatterChart<>( xAxis, yAxis );
        xAxis.setLabel( "X" );
        yAxis.setLabel( "Z" );
        sc.setTitle( "Square Normal Distribution Selections" );

        Factory<Shape<?>> factory = ( Factory<Shape<?>> ) RTP.factoryMap.get( RTP.factoryNames.shape );
        Square_Normal square1 = ( Square_Normal ) factory.get( "SQUARE_NORMAL" );
        Square_Normal square2 = ( Square_Normal ) factory.get( "SQUARE_NORMAL" );
        Square_Normal square3 = ( Square_Normal ) factory.get( "SQUARE_NORMAL" );
        square1.set( NormalDistributionParams.mean,0.5 );
        square1.set( NormalDistributionParams.deviation,1.2 );
        square2.set( NormalDistributionParams.mean,0.01 );
        square2.set( NormalDistributionParams.deviation,1.2 );
        square3.set( NormalDistributionParams.mean,0.99 );
        square3.set( NormalDistributionParams.deviation,1.2 );

        XYChart.Series series1 = new XYChart.Series();
        XYChart.Series series2 = new XYChart.Series();
        XYChart.Series series3 = new XYChart.Series();
        series1.setName( "flat locations" );
        series2.setName( "near locations" );
        series3.setName( "far locations" );

        for( i = 0; i < 1000; i++ ) {
            int[] select1 = square1.select();
            int[] select2 = square2.select();
            int[] select3 = square3.select();
            series1.getData().add( new XYChart.Data( select1[0], select1[1]) );
            series2.getData().add( new XYChart.Data( select2[0], select2[1]) );
            series3.getData().add( new XYChart.Data( select3[0], select3[1]) );
        }

        sc.getData().addAll( series1,series2,series3 );
        Scene scene  = new Scene( sc, 800, 800 );
        stage.setScene( scene );
        stage.show();
    }

    public static void main( String[] args ) {
        launch( args );
    }
}
