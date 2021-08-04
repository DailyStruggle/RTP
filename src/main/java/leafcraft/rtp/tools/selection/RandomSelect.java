package leafcraft.rtp.tools.selection;

public class RandomSelect {
    public static int[] select(RandomSelectParams params) {

    }

    public static int[] selectCircle(RandomSelectParams params) {
        return null;
    }

    public static int[] selectSquare(RandomSelectParams params) {
        int[] res = new int[3];



        return res;
//
//        totalSpace = (radius - centerRadius) * (radius + centerRadius);
//        Double rSpace = totalSpace*Math.pow(r.nextDouble(),weight);
//
//        Double rDistance = Math.sqrt(rSpace+centerRadius*centerRadius);
//        Double perimeterStep = 8*(rDistance*(rDistance-rDistance.intValue()));
//        Double shortStep = perimeterStep%rDistance;
//
//        if(perimeterStep<rDistance*4) {
//            if(perimeterStep<rDistance*2) {
//                if(perimeterStep<rDistance) {
//                    x = rDistance;
//                    z = shortStep;
//                }
//                else {
//                    x = rDistance - shortStep;
//                    z = rDistance;
//                }
//            }
//            else {
//                if(perimeterStep<rDistance*3) {
//                    x = -shortStep;
//                    z = rDistance;
//                }
//                else {
//                    x = -rDistance;
//                    z = rDistance - shortStep;
//                }
//            }
//        }
//        else {
//            if(perimeterStep<rDistance*6) {
//                if(perimeterStep<rDistance*5) {
//                    x = -rDistance;
//                    z = -shortStep;
//                }
//                else {
//                    x = -(rDistance - shortStep);
//                    z = -rDistance;
//                }
//            }
//            else {
//                if(perimeterStep<rDistance*7) {
//                    x = shortStep;
//                    z = -rDistance;
//                }
//                else {
//                    x = rDistance;
//                    z = -(rDistance-shortStep);
//                }
//            }
//        }
//        break;
    }

}
