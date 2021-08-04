import matplotlib.pyplot as plt
import numpy as np
import math
import random
import time
import collections
plt.style.use('seaborn-whitegrid')

plt.title('Method 3')

wgReg = [[2000,2000],[2500,2500]]

it = 1000000
radius = 4096
centerRadius = 1024
x = np.zeros(it)
z = np.zeros(it)
totalSpace = math.pi*(radius+centerRadius)*(radius-centerRadius)
noTouch = {}


def updateSpace(region) :
    global x
    global z
    res = {}
    #get first hit and last hit
    # assuming closest and farthest for ease
    x1 = 2000
    z1 = 2000
    rotation = abs(math.atan(z1/x1))/(2*math.pi)
    radius = math.sqrt(x1*x1 + x2*x2)
    radius = (int(radius+0.5) + rotation)
    firstHit = radius*radius*math.pi

    print("firstHit: ", firstHit)
    print("radius: ", radius)
    print("rotation: ", rotation)

    radius = math.sqrt(firstHit/math.pi)
    rotation = (radius - int(radius))*2*math.pi
    xLoc = radius * math.cos(rotation)
    zLoc = radius * math.sin(rotation)

    print("radius: ", radius)
    print("rotation: ", rotation)
    print("x: ", xLoc)
    print("z: ", xLoc)
    
    rotation = math.abs(math.atan(region[0][1]/region[0][0]))/(2*math.pi)
    radius = sqrt(region[0][0]*region[0][0] + region[0][1]*region[0][1])
    radius = (int(radius+0.5) + rotation)
    lastHit = radius*radius*2*math.pi
    
    rDistance = math.sqrt((firstHit)/math.pi)
    rotation = (rDistance - int(rDistance))*2*math.pi
    xLoc = rDistance * math.cos(rotation)
    zLoc = rDistance * math.sin(rotation)
    size = int(lastHit-firstHit)

    occupiedSpace = 0
    for i in range(size) :
        rDistance = math.sqrt((i+firstHit)/math.pi + centerRadius*centerRadius)
        rotation = (rDistance - int(rDistance))*2*math.pi
        rDistance = int(rDistance)
        xLoc = rDistance * math.cos(rotation)
        zLoc = rDistance * math.sin(rotation)
        
        if (xLoc > region[0][0]) & (xLoc < region[1][0]) & (zLoc > region[0][1]) & (zLoc < region[1][1]) :
            occupiedSpace = occupiedSpace + 1
            res[i] = occupiedSpace
            x[i] = xLoc
            z[i] = zLoc
    return res

start = time.time()
occupado = updateSpace(wgReg)
stop = time.time()
print("space write time: ",stop-start)
def randomSelect(i) :
    #rSpace = float(i)
    rSpace = random.randrange(0,int(totalSpace))

    #if int(rSpace) in 

    rDistance = math.sqrt(rSpace/math.pi + centerRadius*centerRadius)
    rotation = (rDistance - int(rDistance))*2*math.pi
    
    xLoc = rDistance * math.cos(rotation)
    zLoc = rDistance * math.sin(rotation)

    x[i] = xLoc
    z[i] = zLoc

#start = time.time()
#for i in range(it) :
#    randomSelect(i)
#stop = time.time()
#print("time: ",stop-start)

plt.plot(x,z,'o',color='black')

plt.show();
