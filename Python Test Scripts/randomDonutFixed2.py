import matplotlib.pyplot as plt
import numpy as np
import math
import random
import timeit
plt.style.use('seaborn-whitegrid')

plt.title('Method 2')

radius = 4096
centerRadius = 1024
for i in range(5000) :
    distance = centerRadius + (radius-centerRadius)*math.sqrt(random.uniform(0,1))
    rotation = random.uniform(0,1)*math.pi*2
    
    x = int(distance * math.cos(rotation))
    z = int(distance * math.sin(rotation))
    
    plt.plot(x,z,'o',color='black')
    
plt.show();
