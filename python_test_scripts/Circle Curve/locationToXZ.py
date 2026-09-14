import matplotlib.pyplot as plt
import numpy as np
import math
import random
import time
import collections


#inputs:
# r - outer radius
# cr - inner radius, i.e. center radius
# cx,cz - center point
# location - how far along on the curve
#output: [x,z]
def locationToXZ( cr,cx,cz,location ) :
    #distance from the center point
    radius = math.sqrt( location/math.pi + cr*cr )

    #% distance along the curve, translated to radians
    rotation = ( radius - int( radius) )*2*math.pi

    #polar to cartesian
    x = radius * math.cos( rotation ) + cx
    z = radius * math.sin( rotation ) + cz

    return [x,z]

def testFunc( cr,length ) :
    plt.style.use( 'seaborn-whitegrid' )
    x = np.zeros( length )
    z = np.zeros( length )
    print( "length=",length )

    start = time.time()
    for i in range( length ) :
        xz = locationToXZ( cr,0,0,i )
        x[i] = xz[0]
        z[i] = xz[1]
    stop = time.time()
    print( "avg time: ",( (stop-start )/length )*1000000,"ns" )
    plt.plot( x,z,'o',color='black' )
    plt.show();

#testFunc( 64,int( 10000) )
