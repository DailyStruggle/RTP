import matplotlib.pyplot as plt
import numpy as np
import math
import random
import time
plt.style.use( 'seaborn-whitegrid' )

plt.title( 'Method 3' )

wgX1 = 0
wgZ1 = 0
wgX2 = 0
wgZ2 = 0

it = 100000
radius = 4096
centerRadius = 1024
x = np.zeros( it )
z = np.zeros( it )
totalSpace = math.pi*( radius+centerRadius )*( radius-centerRadius )
def randomSelect( i ) :
    #rSpace = float( i )
    rSpace = random.randrange( 0,int( totalSpace) )
    
    rDistance = math.sqrt( rSpace/math.pi + centerRadius*centerRadius )
    rotation = ( rDistance - int( rDistance) )*2*math.pi
    
    xLoc = rDistance * math.cos( rotation )
    zLoc = rDistance * math.sin( rotation )

    isInWgRegion = ( xLoc > wgX1 ) & ( xLoc < wgX2 ) & ( zLoc > wgZ1 ) & ( zLoc < wgZ2 )
    if isInWgRegion :
        randomSelect( i )
    else :
        x[i] = xLoc
        z[i] = zLoc
    
start = time.time()
for i in range( it ) :
    randomSelect( i )
stop = time.time()
print( "time: ",stop-start )

plt.plot( x,z,'o',color='black' )

plt.show();
