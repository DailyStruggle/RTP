import matplotlib.pyplot as plt
import numpy as np
import math
import random
import time
plt.style.use( 'seaborn-whitegrid' )

plt.title( 'After1' )

it = 100000
radius = 4096
centerRadius = 1024
x = np.zeros( it )
z = np.zeros( it )
filterIter = 100
radii = np.zeros( radius-centerRadius )
lpf = np.zeros( radius-centerRadius-filterIter*2+1 )
totalSpace = math.pi*( radius+centerRadius )*( radius-centerRadius )
def randomSelect( i ) :
    rSpace = random.randrange( 0,int( totalSpace) )
    
    r = math.sqrt( rSpace/math.pi + centerRadius*centerRadius )
    theta = ( r - int( r) )*2*math.pi

    xLoc = r * math.cos( theta )
    zLoc = r * math.sin( theta )

    radii[int( r )-centerRadius] = radii[int( r )-centerRadius] + 1
    x[i] = xLoc
    z[i] = zLoc
    
#start = time.time()
for i in range( it ) :
    randomSelect( i )
#stop = time.time()
#print( "time: ",stop-start )

#plt.plot( x,z,'o',color='black' )
for i in range( filterIter,radius-centerRadius-filterIter ) :
    for j in range( -filterIter,filterIter ) :
        lpf[i-filterIter] = lpf[i-filterIter] + radii[i+j]/( filterIter*2+1 )
plt.plot( range( radius-centerRadius-filterIter*2+1 ),lpf,'o',color='black' )

plt.show();
