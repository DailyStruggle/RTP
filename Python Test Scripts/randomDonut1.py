import matplotlib.pyplot as plt
import numpy as np
import math
import random
import time
plt.style.use('seaborn-whitegrid')

plt.title('Before')

it = 5000
radius = 4096
centerRadius = 0
x = np.zeros(it)
z = np.zeros(it)
def randomSelect(i) :
    distance = centerRadius + (radius-centerRadius)*math.sqrt(random.uniform(0,1))
    rotation = random.uniform(0,1)*math.pi*2
    
    xLoc = int(distance * math.cos(rotation))
    zLoc = int(distance * math.sin(rotation))

    x[i] = xLoc
    z[i] = zLoc
        
    
start = time.time()
for i in range(it) :
    randomSelect(i)
stop = time.time()
print("time: ",stop-start)

plt.plot(x,z,'o',color='black')

plt.show();
