#!/usr/bin/python
import os
import time

# Generate some IPv4 traffic
for i in range(5):
    os.system("ping -c 3 8.8.8.8")
    time.sleep(1)
    print("Ping iteration", i+1, "complete") 