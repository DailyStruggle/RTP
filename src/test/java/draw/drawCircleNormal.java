package draw;
import commonTestImpl.TestRTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle_Normal;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.NormalDistributionParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.ScatterChart;
import javafx.scene.chart.XYChart;
import javafx.stage.Stage;

public class drawCircleNormal extends Application {
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

        stage.setTitle( "Circle Normal Distribution Scatter Chart Sample" );
        final NumberAxis xAxis = new NumberAxis( -256, 256, 32 );
        final NumberAxis yAxis = new NumberAxis( -256, 256, 32 );
        final ScatterChart<Number,Number> sc = new
                ScatterChart<>( xAxis, yAxis );
        xAxis.setLabel( "X" );
        yAxis.setLabel( "Z" );
        sc.setTitle( "Circle Normal Distribution Selections" );

        Factory<Shape<?>> factory = ( Factory<Shape<?>> ) RTP.factoryMap.get( RTP.factoryNames.shape );
        Circle_Normal circle1 = ( Circle_Normal ) factory.get( "CIRCLE_NORMAL" );
        Circle_Normal circle2 = ( Circle_Normal ) factory.get( "CIRCLE_NORMAL" );
        Circle_Normal circle3 = ( Circle_Normal ) factory.get( "CIRCLE_NORMAL" );
        circle1.set( NormalDistributionParams.mean,0.5 );
        circle1.set( NormalDistributionParams.deviation,1.2 );
        circle2.set( NormalDistributionParams.mean,0.01 );
        circle2.set( NormalDistributionParams.deviation,1.2 );
        circle3.set( NormalDistributionParams.mean,0.99 );
        circle3.set( NormalDistributionParams.deviation,1.2 );

        XYChart.Series<Number,Number> series1 = new XYChart.Series<>();
        XYChart.Series<Number,Number> series2 = new XYChart.Series<>();
        XYChart.Series<Number,Number> series3 = new XYChart.Series<>();
        series1.setName( "flat locations" );
        series2.setName( "near locations" );
        series3.setName( "far locations" );

        for( i = 0; i < 1000; i++ ) {
            int[] select1 = circle1.select();
            int[] select2 = circle2.select();
            int[] select3 = circle3.select();
            series1.getData().add( new XYChart.Data<>( select1[0], select1[1]) );
            series2.getData().add( new XYChart.Data<>( select2[0], select2[1]) );
            series3.getData().add( new XYChart.Data<>( select3[0], select3[1]) );
        }

        sc.getData().add( series1 );
        sc.getData().add( series2 );
        sc.getData().add( series3 );
        Scene scene  = new Scene( sc, 800, 800 );
        stage.setScene( scene );
        stage.show();
    }

    public static void main( String[] args ) {
        launch( args );
    }
}
