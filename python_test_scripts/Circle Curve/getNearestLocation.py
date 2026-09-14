import matplotlib.pyplot as plt
import numpy as np
import math
import random
import time
import collections
from locationToXZ import locationToXZ
plt.style.use( 'seaborn-whitegrid' )


#inputs:
# cx,cz - center point
# x,z - location to get nearest of
def getNearestLocation( cx,cz,x,z ) :
    x = x - cx
    z = z - cz

    #inverse tan of o/a gets an angle between -pi/2 and pi/2
    # want -0.25 to 0.25
    rotation = math.atan( z/x )/( 2*math.pi )

    #cut down to 0.25
    rotation = rotation % 0.25
    
    #adjust for quadrant
    if ( z<0 ) & ( x<0 ) :
        rotation = rotation + 0.5
    elif ( z<0 ) :
        rotation = rotation + 0.75
    elif ( x<0 ) :
        rotation = rotation + 0.25

    #nearest radius on the curve at that angle
    radius = math.sqrt( x*x + z*z )
    radius = ( int( radius ) + rotation )

    location = radius*radius*math.pi
    return location
    
def testFunc( point ) :
    cx = 0
    cz = 0
    plt.style.use( 'seaborn-whitegrid' )
    print( "input = ",point )
    
    location = getNearestLocation( cx,cz,point[0],point[1] )
    print( "location = ",location )

    xz = locationToXZ( 0,cx,cz,location )
    print( "xz translation = ",xz )
    
    plt.plot( xz[0],xz[1],'o',color='black' )
    plt.show();

testFunc( [260,-520] )

