package com.cloud.hypervisor.kvm.resource;

import com.cloud.hypervisor.kvm.resource.LibvirtVmDef.InterfaceDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVmDef.RngDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVmDef.WatchDogDef;
import com.cloud.hypervisor.kvm.resource.xml.LibvirtDiskDef;
import com.cloud.model.enumeration.DiskControllerType;

import java.util.List;

import junit.framework.TestCase;

public class LibvirtDomainXMLParserTest extends TestCase {

    public void testDomainXMLParser() {
        final int vncPort = 5900;

        final DiskControllerType diskBus = DiskControllerType.SCSI;
        final LibvirtDiskDef.DiskType diskType = LibvirtDiskDef.DiskType.FILE;
        final LibvirtDiskDef.DeviceType deviceType = LibvirtDiskDef.DeviceType.DISK;
        final LibvirtDiskDef.DiskFmtType diskFormat = LibvirtDiskDef.DiskFmtType.QCOW2;
        final LibvirtDiskDef.DiskCacheMode diskCache = LibvirtDiskDef.DiskCacheMode.NONE;

        final InterfaceDef.NicModel ifModel = InterfaceDef.NicModel.VIRTIO;
        final InterfaceDef.GuestNetType ifType = InterfaceDef.GuestNetType.BRIDGE;

        final String diskLabel = "vda";
        final String diskPath = "/var/lib/libvirt/images/my-test-image.qcow2";

        final String xml = "<domain type='kvm' id='10'>" +
                "<name>s-2970-VM</name>" +
                "<uuid>4d2c1526-865d-4fc9-a1ac-dbd1801a22d0</uuid>" +
                "<description>Debian GNU/Linux 6(64-bit)</description>" +
                "<memory unit='KiB'>262144</memory>" +
                "<currentMemory unit='KiB'>262144</currentMemory>" +
                "<vcpu placement='static'>1</vcpu>" +
                "<cputune>" +
                "<shares>250</shares>" +
                "</cputune>" +
                "<resource>" +
                "<partition>/machine</partition>" +
                "</resource>" +
                "<os>" +
                "<type arch='x86_64' machine='pc-i440fx-1.5'>hvm</type>" +
                "<boot dev='cdrom'/>" +
                "<boot dev='hd'/>" +
                "</os>" +
                "<features>" +
                "<acpi/>" +
                "<apic/>" +
                "<pae/>" +
                "</features>" +
                "<clock offset='utc'/>" +
                "<on_poweroff>destroy</on_poweroff>" +
                "<on_reboot>restart</on_reboot>" +
                "<on_crash>destroy</on_crash>" +
                "<devices>" +
                "<emulator>/usr/bin/kvm-spice</emulator>" +
                "<disk type='" + diskType.toString() + "' device='" + deviceType.toString() + "'>" +
                "<driver name='qemu' type='" + diskFormat.toString() + "' cache='" + diskCache.toString() + "'/>" +
                "<source file='" + diskPath + "'/>" +
                "<target dev='" + diskLabel + "' bus='" + diskBus.toString() + "'/>" +
                "<alias name='virtio-disk0'/>" +
                "<address type='pci' domain='0x0000' bus='0x00' slot='0x08' function='0x0'/>" +
                "</disk>" +
                "<disk type='file' device='cdrom'>" +
                "<driver name='qemu' type='raw' cache='none'/>" +
                "<source file='/opt/cosmic/agent/vms/systemvm.iso'/>" +
                "<target dev='hdc' bus='ide'/>" +
                "<readonly/>" +
                "<alias name='ide0-1-0'/>" +
                "<address type='drive' controller='0' bus='1' target='0' unit='0'/>" +
                "</disk>" +
                "<controller type='usb' index='0'>" +
                "<alias name='usb0'/>" +
                "<address type='pci' domain='0x0000' bus='0x00' slot='0x01' function='0x2'/>" +
                "</controller>" +
                "<controller type='pci' index='0' model='pci-root'>" +
                "<alias name='pci0'/>" +
                "</controller>" +
                "<controller type='ide' index='0'>" +
                "<alias name='ide0'/>" +
                "<address type='pci' domain='0x0000' bus='0x00' slot='0x01' function='0x1'/>" +
                "</controller>" +
                "<controller type='virtio-serial' index='0'>" +
                "<alias name='virtio-serial0'/>" +
                "<address type='pci' domain='0x0000' bus='0x00' slot='0x07' function='0x0'/>" +
                "</controller>" +
                "<interface type='" + ifType.toString() + "'>" +
                "<mac address='0e:00:a9:fe:02:00'/>" +
                "<source bridge='cloud0'/>" +
                "<target dev='vnet0'/>" +
                "<model type='" + ifModel.toString() + "'/>" +
                "<alias name='net0'/>" +
                "<address type='pci' domain='0x0000' bus='0x00' slot='0x03' function='0x0'/>" +
                "</interface>" +
                "<interface type='" + ifType.toString() + "'>" +
                "<mac address='06:c5:94:00:05:65'/>" +
                "<source bridge='cloudbr1'/>" +
                "<target dev='vnet1'/>" +
                "<model type='" + ifModel.toString() + "'/>" +
                "<alias name='net1'/>" +
                "<address type='pci' domain='0x0000' bus='0x00' slot='0x04' function='0x0'/>" +
                "</interface>" +
                "<interface type='" + ifType.toString() + "'>" +
                "<mac address='06:c9:f4:00:04:40'/>" +
                "<source bridge='cloudbr0'/>" +
                "<target dev='vnet2'/>" +
                "<model type='" + ifModel.toString() + "'/>" +
                "<alias name='net2'/>" +
                "<address type='pci' domain='0x0000' bus='0x00' slot='0x05' function='0x0'/>" +
                "</interface>" +
                "<interface type='" + ifType.toString() + "'>" +
                "<mac address='06:7e:c6:00:05:68'/>" +
                "<source bridge='cloudbr1'/>" +
                "<target dev='vnet3'/>" +
                "<model type='" + ifModel.toString() + "'/>" +
                "<alias name='net3'/>" +
                "<address type='pci' domain='0x0000' bus='0x00' slot='0x06' function='0x0'/>" +
                "</interface>" +
                "<serial type='pty'>" +
                "<source path='/dev/pts/3'/>" +
                "<target port='0'/>" +
                "<alias name='serial0'/>" +
                "</serial>" +
                "<console type='pty' tty='/dev/pts/3'>" +
                "<source path='/dev/pts/3'/>" +
                "<target type='serial' port='0'/>" +
                "<alias name='serial0'/>" +
                "</console>" +
                "<channel type='unix'>" +
                "<source mode='bind' path='/var/lib/libvirt/qemu/s-2970-VM.agent'/>" +
                "<target type='virtio' name='s-2970-VM.vport'/>" +
                "<alias name='channel0'/>" +
                "<address type='virtio-serial' controller='0' bus='0' port='1'/>" +
                "</channel>" +
                "<input type='tablet' bus='usb'>" +
                "<alias name='input0'/>" +
                "</input>" +
                "<input type='mouse' bus='ps2'/>" +
                "<graphics type='vnc' port='" + vncPort + "' autoport='yes' listen='0.0.0.0'>" +
                "<listen type='address' address='0.0.0.0'/>" +
                "</graphics>" +
                "<video>" +
                "<model type='cirrus' vram='9216' heads='1'/>" +
                "<alias name='video0'/>" +
                "<address type='pci' domain='0x0000' bus='0x00' slot='0x02' function='0x0'/>" +
                "</video>" +
                "<memballoon model='virtio'>" +
                "<alias name='balloon0'/>" +
                "<address type='pci' domain='0x0000' bus='0x00' slot='0x09' function='0x0'/>" +
                "</memballoon>" +
                "<rng model='virtio'>" +
                "<backend model='random'>/dev/random</backend>" +
                "</rng>" +
                "<watchdog model='i6300esb' action='reset'/>" +
                "<watchdog model='ib700' action='poweroff'/>" +
                "</devices>" +
                "<seclabel type='none'/>" +
                "</domain>";

        final LibvirtDomainXmlParser parser = new LibvirtDomainXmlParser();
        parser.parseDomainXml(xml);

        assertEquals(vncPort - 5900, (int) parser.getVncPort());

        final List<LibvirtDiskDef> disks = parser.getDisks();
    /* Disk 0 is the first disk, the QCOW2 file backed virto disk */
        final int diskId = 0;

        assertEquals(diskLabel, disks.get(diskId).getDiskLabel());
        assertEquals(diskPath, disks.get(diskId).getDiskPath());
        assertEquals(diskCache, disks.get(diskId).getCacheMode());
        assertEquals(diskBus, disks.get(diskId).getBusType());
        assertEquals(diskType, disks.get(diskId).getDiskType());
        assertEquals(deviceType, disks.get(diskId).getDeviceType());
        assertEquals(diskFormat, disks.get(diskId).getDiskFormatType());

        final List<InterfaceDef> ifs = parser.getInterfaces();
        for (int i = 0; i < ifs.size(); i++) {
            assertEquals(ifModel, ifs.get(i).getModel());
            assertEquals(ifType, ifs.get(i).getNetType());
        }

        final List<RngDef> rngs = parser.getRngs();
        assertEquals("/dev/random", rngs.get(0).getPath());
        assertEquals(RngDef.RngBackendModel.RANDOM, rngs.get(0).getRngBackendModel());

        final List<WatchDogDef> watchDogs = parser.getWatchDogs();
        assertEquals(WatchDogDef.WatchDogModel.I6300ESB, watchDogs.get(0).getModel());
        assertEquals(WatchDogDef.WatchDogAction.RESET, watchDogs.get(0).getAction());
        assertEquals(WatchDogDef.WatchDogModel.IB700, watchDogs.get(1).getModel());
        assertEquals(WatchDogDef.WatchDogAction.POWEROFF, watchDogs.get(1).getAction());
    }
}
