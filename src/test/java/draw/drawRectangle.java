package draw;
import commonTestImpl.TestRTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Rectangle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.ScatterChart;
import javafx.scene.chart.XYChart;
import javafx.stage.Stage;

public class drawRectangle extends Application {
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

        stage.setTitle( "Rectangle Scatter Chart Sample" );
        final NumberAxis xAxis = new NumberAxis( -256, 256, 32 );
        final NumberAxis yAxis = new NumberAxis( -256, 256, 32 );
        final ScatterChart<Number,Number> sc = new
                ScatterChart<>( xAxis, yAxis );
        xAxis.setLabel( "X" );
        yAxis.setLabel( "Z" );
        sc.setTitle( "Rectangle Selections" );

        Factory<Shape<?>> factory = ( Factory<Shape<?>> ) RTP.factoryMap.get( RTP.factoryNames.shape );
        Rectangle rect1 = ( Rectangle ) factory.get( "RECTANGLE" );
        Rectangle rect2 = ( Rectangle ) factory.get( "RECTANGLE" );
        Rectangle rect3 = ( Rectangle ) factory.get( "RECTANGLE" );
        rect1.set( RectangleParams.width,256 );
        rect1.set( RectangleParams.height,512 );
        rect2.set( RectangleParams.width,512 );
        rect2.set( RectangleParams.height,256 );
        rect3.set( RectangleParams.width,128 );
        rect3.set( RectangleParams.height,256 );
        rect3.set( RectangleParams.rotation,45 );

        XYChart.Series series1 = new XYChart.Series();
        XYChart.Series series2 = new XYChart.Series();
        XYChart.Series series3 = new XYChart.Series();
        series1.setName( "tall" );
        series2.setName( "fat" );
        series3.setName( "rotated" );

        for( i = 0; i < 1000; i++ ) {
            int[] select1 = rect1.select();
            int[] select2 = rect2.select();
            int[] select3 = rect3.select();
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
