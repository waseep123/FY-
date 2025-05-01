#!/usr/bin/python

from mininet.net import Mininet
from mininet.node import Controller, RemoteController
from mininet.topo import SingleSwitchTopo
import time

def generate_traffic(net):
    h1, h2 = net.get('h1', 'h2')
    
    # TCP traffic
    print("Generating TCP traffic...")
    h1.cmd('nc -w 1 %s 80 </dev/null &' % h2.IP())
    time.sleep(1)
    
    # UDP traffic
    print("Generating UDP traffic...")
    h1.cmd('nc -u -w 1 %s 53 </dev/null &' % h2.IP())
    time.sleep(1)
    
    # ICMP traffic
    print("Generating ICMP traffic...")
    h1.cmd('ping -c 1 %s' % h2.IP())
    time.sleep(1)

if __name__ == "__main__":
    print("Creating Mininet topology...")
    topo = SingleSwitchTopo(2)  # Create topology with 1 switch and 2 hosts
    net = Mininet(topo=topo, controller=RemoteController)
    net.addController('c0', ip='127.0.0.1', port=6653)
    
    print("Starting network...")
    net.start()
    
    print("Starting traffic generation...")
    try:
        while True:
            generate_traffic(net)
            print("Waiting 5 seconds before next round...")
            time.sleep(5)
    except KeyboardInterrupt:
        print("\nStopping traffic generation...")
    finally:
        net.stop() 