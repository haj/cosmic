package com.cloud.engine.orchestration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.element.DhcpServiceProvider;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.vm.Nic;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.Type;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicSecondaryIpDao;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import junit.framework.TestCase;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;

/**
 * NetworkManagerImpl implements NetworkManager.
 */
public class NetworkOrchestratorTest extends TestCase {
    NetworkOrchestrator testOrchastrator = new NetworkOrchestrator();

    String guruName = "GuestNetworkGuru";
    String dhcpProvider = "VirtualRouter";
    NetworkGuru guru = mock(NetworkGuru.class);

    @Override
    @Before
    public void setUp() {
        // make class-scope mocks
        testOrchastrator._nicDao = mock(NicDao.class);
        testOrchastrator._networksDao = mock(NetworkDao.class);
        testOrchastrator._networkModel = mock(NetworkModel.class);
        testOrchastrator._nicSecondaryIpDao = mock(NicSecondaryIpDao.class);
        testOrchastrator._ntwkSrvcDao = mock(NetworkServiceMapDao.class);
        final DhcpServiceProvider provider = mock(DhcpServiceProvider.class);

        final Map<Network.Capability, String> capabilities = new HashMap<>();
        final Map<Network.Service, Map<Network.Capability, String>> services = new HashMap<>();
        services.put(Network.Service.Dhcp, capabilities);
        when(provider.getCapabilities()).thenReturn(services);
        capabilities.put(Network.Capability.DhcpAccrossMultipleSubnets, "true");

        when(testOrchastrator._ntwkSrvcDao.getProviderForServiceInNetwork(Matchers.anyLong(), Matchers.eq(Service.Dhcp))).thenReturn(dhcpProvider);
        when(testOrchastrator._networkModel.getElementImplementingProvider(dhcpProvider)).thenReturn(provider);

        when(guru.getName()).thenReturn(guruName);
        final List<NetworkGuru> networkGurus = new ArrayList<>();
        networkGurus.add(guru);
        testOrchastrator.networkGurus = networkGurus;
    }

    @Test
    public void testRemoveDhcpServiceWithNic() {
        // make local mocks
        final VirtualMachineProfile vm = mock(VirtualMachineProfile.class);
        final NicVO nic = mock(NicVO.class);
        final NetworkVO network = mock(NetworkVO.class);

        // make sure that release dhcp will be called
        when(vm.getType()).thenReturn(Type.User);
        when(testOrchastrator._networkModel.areServicesSupportedInNetwork(network.getId(), Service.Dhcp)).thenReturn(true);
        when(network.getTrafficType()).thenReturn(TrafficType.Guest);
        when(network.getGuestType()).thenReturn(GuestType.Shared);
        when(testOrchastrator._nicDao.listByNetworkIdTypeAndGatewayAndBroadcastUri(nic.getNetworkId(), VirtualMachine.Type.User, nic.getIPv4Gateway(), nic.getBroadcastUri()))
                .thenReturn(new ArrayList<>());

        when(network.getGuruName()).thenReturn(guruName);
        when(testOrchastrator._networksDao.findById(nic.getNetworkId())).thenReturn(network);

        testOrchastrator.removeNic(vm, nic);

        verify(nic, times(1)).setState(Nic.State.Deallocating);
        verify(testOrchastrator._networkModel, times(2)).getElementImplementingProvider(dhcpProvider);
        verify(testOrchastrator._ntwkSrvcDao, times(2)).getProviderForServiceInNetwork(network.getId(), Service.Dhcp);
        verify(testOrchastrator._networksDao, times(2)).findById(nic.getNetworkId());
    }

    @Test
    public void testDontRemoveDhcpServiceFromDomainRouter() {
        // make local mocks
        final VirtualMachineProfile vm = mock(VirtualMachineProfile.class);
        final NicVO nic = mock(NicVO.class);
        final NetworkVO network = mock(NetworkVO.class);

        // make sure that release dhcp won't be called
        when(vm.getType()).thenReturn(Type.DomainRouter);

        when(network.getGuruName()).thenReturn(guruName);
        when(testOrchastrator._networksDao.findById(nic.getNetworkId())).thenReturn(network);

        testOrchastrator.removeNic(vm, nic);

        verify(nic, times(1)).setState(Nic.State.Deallocating);
        verify(testOrchastrator._networkModel, never()).getElementImplementingProvider(dhcpProvider);
        verify(testOrchastrator._ntwkSrvcDao, never()).getProviderForServiceInNetwork(network.getId(), Service.Dhcp);
        verify(testOrchastrator._networksDao, times(1)).findById(nic.getNetworkId());
    }

    @Test
    public void testDontRemoveDhcpServiceWhenNotProvided() {
        // make local mocks
        final VirtualMachineProfile vm = mock(VirtualMachineProfile.class);
        final NicVO nic = mock(NicVO.class);
        final NetworkVO network = mock(NetworkVO.class);

        // make sure that release dhcp will *not* be called
        when(vm.getType()).thenReturn(Type.User);
        when(testOrchastrator._networkModel.areServicesSupportedInNetwork(network.getId(), Service.Dhcp)).thenReturn(false);

        when(network.getGuruName()).thenReturn(guruName);
        when(testOrchastrator._networksDao.findById(nic.getNetworkId())).thenReturn(network);

        testOrchastrator.removeNic(vm, nic);

        verify(nic, times(1)).setState(Nic.State.Deallocating);
        verify(testOrchastrator._networkModel, never()).getElementImplementingProvider(dhcpProvider);
        verify(testOrchastrator._ntwkSrvcDao, never()).getProviderForServiceInNetwork(network.getId(), Service.Dhcp);
        verify(testOrchastrator._networksDao, times(1)).findById(nic.getNetworkId());
    }
}
