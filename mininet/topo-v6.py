#!/usr/bin/python

import argparse

from mininet.cli import CLI
from mininet.log import setLogLevel
from mininet.net import Mininet
from mininet.node import Host, RemoteController
from mininet.topo import Topo
from stratum import StratumBmv2Switch

CPU_PORT = 255


class IPv4Host(Host):
    """Host that can be configured with an IPv4 gateway (default route)."""

    def config(self, ipv4, ipv4_gw=None, **params):
        super(IPv4Host, self).config(**params)
        self.cmd('ip -6 addr flush dev %s' % self.defaultIntf())
        self.cmd('ip -4 addr flush dev %s' % self.defaultIntf())
        self.cmd('ip addr add %s dev %s' % (ipv4, self.defaultIntf()))
        if ipv4_gw:
            self.cmd('ip route add default via %s' % ipv4_gw)
        # Disable offload
        for attr in ["rx", "tx", "sg"]:
            cmd = "/sbin/ethtool --offload %s %s off" % (self.defaultIntf(), attr)
            self.cmd(cmd)

        def updateIP():
            return ipv4.split('/')[0]

        self.defaultIntf().updateIP = updateIP

    def terminate(self):
        super(IPv4Host, self).terminate()


class OneSwitchTwoHostTopo(Topo):
    """Topology with 1 switch and 2 IPv4 hosts"""

    def __init__(self, *args, **kwargs):
        Topo.__init__(self, *args, **kwargs)

        # Single switch
        switch = self.addSwitch('s1', cls=StratumBmv2Switch, cpuport=CPU_PORT)

        # Hosts
        h1 = self.addHost('h1', cls=IPv4Host, mac="00:00:00:00:00:01",
                          ipv4='10.0.0.1/24', ipv4_gw='10.0.0.254')
        h2 = self.addHost('h2', cls=IPv4Host, mac="00:00:00:00:00:02",
                          ipv4='10.0.0.2/24', ipv4_gw='10.0.0.254')

        # Links
        self.addLink(h1, switch)
        self.addLink(h2, switch)


def main():
    net = Mininet(topo=OneSwitchTwoHostTopo(), controller=RemoteController('c0', ip='10.3.12.140'))
    net.start()
    CLI(net)
    net.stop()
    print('#' * 80)
    print('ATTENTION: Mininet was stopped! Perhaps accidentally?')
    print('No worries, it will restart automatically in a few seconds...')
    print('To access again the Mininet CLI, use `make mn-cli`')
    print('To detach from the CLI (without stopping), press Ctrl-D')
    print('To permanently quit Mininet, use `make stop`')
    print('#' * 80)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description='Mininet topology script for 1 switch 2 hosts with stratum_bmv2 and IPv4 hosts')
    args = parser.parse_args()
    setLogLevel('info')

    main()

