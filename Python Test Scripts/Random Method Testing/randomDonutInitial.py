import matplotlib.pyplot as plt
import numpy as np
import math
import random
import timeit
plt.style.use( 'seaborn-whitegrid' )

radius = 4096
centerRadius = 1024
for i in range( 5000 ) :
    distance = random.randrange( centerRadius,radius )
    rotation = random.uniform( 0.0,2*math.pi )

    x = distance * math.cos( rotation )
    z = distance * math.sin( rotation )
    
    plt.plot( x,z,'o',color='black' )
    
plt.show();
