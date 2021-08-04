import matplotlib.pyplot as plt
import numpy as np
import math
import random
import time
plt.style.use('seaborn-whitegrid')

plt.title('Method 2')

wgX1 = 0
wgZ1 = 0
wgX2 = 4000
wgZ2 = 4000

it = 60000
radius = 4096
centerRadius = 1024
x = np.zeros(it)
z = np.zeros(it)
def randomSelect(i) :
    distance = centerRadius + (radius-centerRadius)*math.sqrt(random.uniform(0,1))
    rotation = random.uniform(0,1)*math.pi*2
    
    xLoc = int(distance * math.cos(rotation))
    zLoc = int(distance * math.sin(rotation))

    isInWgRegion = (xLoc > wgX1) & (xLoc < wgX2) & (zLoc > wgZ1) & (zLoc < wgZ2)
    if isInWgRegion :
        randomSelect(i)
    else :
        x[i] = xLoc
        z[i] = zLoc
        
    
start = time.time()
for i in range(it) :
    randomSelect(i)
stop = time.time()
print("time: ",stop-start)

plt.plot(x,z,'o',color='black')

plt.show();
